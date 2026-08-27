package io.worldloom.provider.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmInline

private val PROVIDER_CONFIG_ID_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9._:-]*$")

@JvmInline
value class ProviderConfigurationId(val value: String) {
    init {
        require(PROVIDER_CONFIG_ID_PATTERN.matches(value)) { "Provider configuration id must be stable" }
    }
}

/**
 * Non-secret runtime configuration. [credentialKey] is a vault lookup key; the credential itself
 * must never be copied into this model, a save, or an Agent request.
 */
data class ProviderConfiguration(
    val id: ProviderConfigurationId,
    val adapterId: String,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val credentialKey: String,
    val maxOutputTokens: Int = 4_096,
    val inputCostMicrounitsPerMillionTokens: Long = 0,
    val outputCostMicrounitsPerMillionTokens: Long = 0,
    val allowInsecureLocalTransport: Boolean = false,
) {
    init {
        require(PROVIDER_CONFIG_ID_PATTERN.matches(adapterId)) { "Provider adapter id must be stable" }
        require(displayName.isNotBlank()) { "Provider display name must not be blank" }
        require(baseUrl.isNotBlank()) { "Provider base URL must not be blank" }
        require(modelId.isNotBlank()) { "Provider model id must not be blank" }
        require(credentialKey.isNotBlank()) { "Credential key must not be blank" }
        require(maxOutputTokens > 0) { "Provider output limit must be positive" }
        require(inputCostMicrounitsPerMillionTokens >= 0) { "Input price must not be negative" }
        require(outputCostMicrounitsPerMillionTokens >= 0) { "Output price must not be negative" }
        require(!baseUrl.contains('@')) { "Provider base URL must not contain user information" }
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
            "Provider base URL must use HTTP or HTTPS"
        }
    }
}

data class ProviderModelDescriptor(
    val id: String,
    val displayName: String = id,
    val contextWindowTokens: Long? = null,
    val capabilities: ProviderCapabilities? = null,
) {
    init {
        require(id.isNotBlank()) { "Provider model id must not be blank" }
        require(displayName.isNotBlank()) { "Provider model display name must not be blank" }
        require(contextWindowTokens == null || contextWindowTokens > 0) {
            "Provider model context window must be positive"
        }
    }
}

sealed interface ProviderConnectionTestResult {
    data class Connected(
        val capabilities: ProviderCapabilities,
        val model: ProviderModelDescriptor?,
    ) : ProviderConnectionTestResult

    data class Failed(
        val code: ProviderFailureCode,
        val message: String,
        val retryable: Boolean,
    ) : ProviderConnectionTestResult
}

sealed interface ProviderModelDiscoveryResult {
    data class Success(val models: List<ProviderModelDescriptor>) : ProviderModelDiscoveryResult

    data class Failure(
        val code: ProviderFailureCode,
        val message: String,
        val retryable: Boolean,
    ) : ProviderModelDiscoveryResult
}

/** Adapter-owned management operations. Implementations may read a vault credential at call time. */
interface ConfigurableProviderAdapter {
    val adapterId: String
    val capabilities: ProviderCapabilities

    suspend fun test(configuration: ProviderConfiguration): ProviderConnectionTestResult

    suspend fun discoverModels(configuration: ProviderConfiguration): ProviderModelDiscoveryResult

    fun create(configuration: ProviderConfiguration): LanguageModelProvider
}

interface ProviderConfigurationStore {
    suspend fun list(): List<ProviderConfiguration>

    suspend fun selected(): ProviderConfigurationId?

    suspend fun put(configuration: ProviderConfiguration)

    suspend fun remove(id: ProviderConfigurationId)

    suspend fun select(id: ProviderConfigurationId?)
}

class InMemoryProviderConfigurationStore(
    initialConfigurations: List<ProviderConfiguration> = emptyList(),
    initialSelection: ProviderConfigurationId? = null,
) : ProviderConfigurationStore {
    private val mutex = Mutex()
    private val configurations = linkedMapOf<ProviderConfigurationId, ProviderConfiguration>()
    private var selectedId: ProviderConfigurationId? = initialSelection

    init {
        require(initialConfigurations.map { it.id }.distinct().size == initialConfigurations.size) {
            "Initial provider configuration ids must be unique"
        }
        configurations.putAll(initialConfigurations.associateBy { it.id })
        require(initialSelection == null || configurations.containsKey(initialSelection)) {
            "Initial provider selection does not exist"
        }
    }

    override suspend fun list(): List<ProviderConfiguration> = mutex.withLock {
        configurations.values.sortedBy { it.id.value }
    }

    override suspend fun selected(): ProviderConfigurationId? = mutex.withLock { selectedId }

    override suspend fun put(configuration: ProviderConfiguration) {
        mutex.withLock { configurations[configuration.id] = configuration }
    }

    override suspend fun remove(id: ProviderConfigurationId) {
        mutex.withLock {
            configurations.remove(id)
            if (selectedId == id) selectedId = null
        }
    }

    override suspend fun select(id: ProviderConfigurationId?) {
        mutex.withLock {
            require(id == null || configurations.containsKey(id)) { "Selected provider configuration does not exist" }
            selectedId = id
        }
    }
}

sealed interface SelectedProviderResult {
    data class Success(
        val configuration: ProviderConfiguration,
        val provider: LanguageModelProvider,
    ) : SelectedProviderResult

    data object NotSelected : SelectedProviderResult

    data class InvalidConfiguration(val message: String) : SelectedProviderResult
}

/** Centralizes adapter selection without allowing vendor details to cross provider-api. */
class ProviderConfigurationCenter(
    adapters: List<ConfigurableProviderAdapter>,
    private val store: ProviderConfigurationStore,
) {
    private val adaptersById = adapters.associateBy(ConfigurableProviderAdapter::adapterId).also { indexed ->
        require(indexed.size == adapters.size) { "Provider adapter ids must be unique" }
    }
    val capabilities: ProviderCapabilities = ProviderCapabilities(
        toolCalling = adapters.any { it.capabilities.toolCalling },
        streaming = adapters.any { it.capabilities.streaming },
        structuredOutput = adapters.any { it.capabilities.structuredOutput },
    )

    suspend fun configurations(): List<ProviderConfiguration> = store.list()

    suspend fun upsert(configuration: ProviderConfiguration) {
        require(adaptersById.containsKey(configuration.adapterId)) {
            "Unknown provider adapter: ${configuration.adapterId}"
        }
        store.put(configuration)
    }

    suspend fun remove(id: ProviderConfigurationId) = store.remove(id)

    suspend fun select(id: ProviderConfigurationId?) = store.select(id)

    suspend fun test(id: ProviderConfigurationId): ProviderConnectionTestResult {
        val configuration = requireConfiguration(id)
        return requireAdapter(configuration).test(configuration)
    }

    suspend fun discoverModels(id: ProviderConfigurationId): ProviderModelDiscoveryResult {
        val configuration = requireConfiguration(id)
        return requireAdapter(configuration).discoverModels(configuration)
    }

    suspend fun selectedProvider(): SelectedProviderResult {
        val selected = store.selected() ?: return SelectedProviderResult.NotSelected
        val configuration = store.list().firstOrNull { it.id == selected }
            ?: return SelectedProviderResult.InvalidConfiguration("Selected provider configuration is missing")
        val adapter = adaptersById[configuration.adapterId]
            ?: return SelectedProviderResult.InvalidConfiguration(
                "Selected provider adapter is unavailable: ${configuration.adapterId}",
            )
        return SelectedProviderResult.Success(configuration, adapter.create(configuration))
    }

    private suspend fun requireConfiguration(id: ProviderConfigurationId): ProviderConfiguration =
        store.list().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Provider configuration does not exist: ${id.value}")

    private fun requireAdapter(configuration: ProviderConfiguration): ConfigurableProviderAdapter =
        requireNotNull(adaptersById[configuration.adapterId]) {
            "Provider adapter is unavailable: ${configuration.adapterId}"
        }
}

/** Resolves the currently selected adapter for every call, enabling runtime model switching. */
class SelectedProviderLanguageModelProvider(
    private val center: ProviderConfigurationCenter,
) : StreamingLanguageModelProvider {
    override val capabilities: ProviderCapabilities = center.capabilities

    override suspend fun complete(request: ProviderRequest): ProviderResult = when (val selected = center.selectedProvider()) {
        is SelectedProviderResult.Success -> selected.provider.complete(request)
        SelectedProviderResult.NotSelected -> missingSelection()
        is SelectedProviderResult.InvalidConfiguration -> ProviderResult.Failure(
            ProviderFailureCode.INVALID_REQUEST,
            selected.message,
            retryable = false,
        )
    }

    override suspend fun completeStreaming(
        request: ProviderRequest,
        onEvent: suspend (ProviderStreamEvent) -> Unit,
    ): ProviderResult = when (val selected = center.selectedProvider()) {
        is SelectedProviderResult.Success -> {
            val provider = selected.provider
            if (provider is StreamingLanguageModelProvider && provider.capabilities.streaming) {
                provider.completeStreaming(request, onEvent)
            } else {
                provider.complete(request).also { result ->
                    if (result is ProviderResult.Success) result.turn.text?.let { onEvent(ProviderStreamEvent.TextDelta(it)) }
                }
            }
        }
        SelectedProviderResult.NotSelected -> missingSelection()
        is SelectedProviderResult.InvalidConfiguration -> ProviderResult.Failure(
            ProviderFailureCode.INVALID_REQUEST,
            selected.message,
            retryable = false,
        )
    }

    private fun missingSelection(): ProviderResult.Failure = ProviderResult.Failure(
        ProviderFailureCode.INVALID_REQUEST,
        "No language model provider is selected",
        retryable = false,
    )
}
