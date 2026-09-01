package io.worldloom.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.worldloom.platform.credentials.CredentialKey
import io.worldloom.platform.credentials.CredentialReadResult
import io.worldloom.platform.credentials.CredentialVault
import io.worldloom.provider.api.ConfigurableProviderAdapter
import io.worldloom.provider.api.LanguageModelProvider
import io.worldloom.provider.api.ProviderCapabilities
import io.worldloom.provider.api.ProviderConfiguration
import io.worldloom.provider.api.ProviderConnectionTestResult
import io.worldloom.provider.api.ProviderFailureCode
import io.worldloom.provider.api.ProviderMessage
import io.worldloom.provider.api.ProviderMessageRole
import io.worldloom.provider.api.ProviderModelDescriptor
import io.worldloom.provider.api.ProviderModelDiscoveryResult
import io.worldloom.provider.api.ProviderRequest
import io.worldloom.provider.api.ProviderResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

const val OPENAI_ADAPTER_ID: String = "openai-chat-completions"

/** Management and factory boundary for OpenAI-compatible Chat Completions endpoints. */
class OpenAiConfigurableAdapter(
    private val httpClient: HttpClient,
    private val credentialVault: CredentialVault,
) : ConfigurableProviderAdapter {
    override val adapterId: String = OPENAI_ADAPTER_ID
    override val capabilities: ProviderCapabilities = CAPABILITIES

    override suspend fun test(configuration: ProviderConfiguration): ProviderConnectionTestResult =
        when (
            val result = create(configuration).complete(
                ProviderRequest(
                    messages = listOf(ProviderMessage(ProviderMessageRole.USER, "Reply with exactly OK.")),
                    maxOutputTokens = CONNECTION_TEST_MAX_OUTPUT_TOKENS,
                ),
            )
        ) {
            is ProviderResult.Success -> ProviderConnectionTestResult.Connected(
                capabilities = CAPABILITIES,
                model = ProviderModelDescriptor(configuration.modelId),
            )
            is ProviderResult.Failure -> ProviderConnectionTestResult.Failed(
                code = result.code,
                message = result.message,
                retryable = result.retryable,
            )
        }

    override suspend fun discoverModels(configuration: ProviderConfiguration): ProviderModelDiscoveryResult =
        withCredential(configuration) { credential ->
            try {
                val response = httpClient.get("${configuration.baseUrl.trimEnd('/')}/models") {
                    bearerAuth(credential)
                }
                if (response.status != HttpStatusCode.OK) {
                    return@withCredential failureForStatus(response.status)
                }
                val models = try {
                    decodeModelList(response.bodyAsText())
                } catch (_: Exception) {
                    return@withCredential ProviderModelDiscoveryResult.Failure(
                        ProviderFailureCode.INVALID_RESPONSE,
                        "Provider model discovery returned an invalid response",
                        retryable = false,
                    )
                }
                ProviderModelDiscoveryResult.Success(models)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProviderModelDiscoveryResult.Failure(
                    ProviderFailureCode.NETWORK,
                    "Provider model discovery failed",
                    retryable = true,
                )
            }
        }

    override fun create(configuration: ProviderConfiguration): LanguageModelProvider =
        OpenAiChatCompletionsProvider(
            httpClient = httpClient,
            credentialVault = credentialVault,
            config = OpenAiChatCompletionsConfig(
                model = configuration.modelId,
                baseUrl = configuration.baseUrl,
                inputCostMicrounitsPerMillionTokens = configuration.inputCostMicrounitsPerMillionTokens,
                outputCostMicrounitsPerMillionTokens = configuration.outputCostMicrounitsPerMillionTokens,
                allowInsecureTransport = configuration.allowInsecureLocalTransport ||
                    configuration.baseUrl.startsWith("http://"),
            ),
            credentialKey = CredentialKey(configuration.credentialKey),
        )

    private suspend fun withCredential(
        configuration: ProviderConfiguration,
        block: suspend (String) -> ProviderModelDiscoveryResult,
    ): ProviderModelDiscoveryResult = when (
        val credential = credentialVault.read(CredentialKey(configuration.credentialKey))
    ) {
        is CredentialReadResult.Success -> credential.secret.access(block)
        is CredentialReadResult.Failure -> ProviderModelDiscoveryResult.Failure(
            ProviderFailureCode.AUTHENTICATION,
            "Provider credential is not configured",
            retryable = false,
        )
    }

    private fun failureForStatus(status: HttpStatusCode): ProviderModelDiscoveryResult.Failure = when (status) {
        HttpStatusCode.Unauthorized,
        HttpStatusCode.Forbidden,
        -> ProviderModelDiscoveryResult.Failure(
            ProviderFailureCode.AUTHENTICATION,
            "Provider rejected the configured credential",
            retryable = false,
        )
        HttpStatusCode.TooManyRequests -> ProviderModelDiscoveryResult.Failure(
            ProviderFailureCode.RATE_LIMITED,
            "Provider model discovery was rate limited",
            retryable = true,
        )
        else -> ProviderModelDiscoveryResult.Failure(
            if (status.value >= 500) ProviderFailureCode.UNAVAILABLE else ProviderFailureCode.INVALID_REQUEST,
            "Provider model discovery failed with HTTP ${status.value}",
            retryable = status.value >= 500,
        )
    }

    private companion object {
        const val CONNECTION_TEST_MAX_OUTPUT_TOKENS = 64
        val CAPABILITIES = ProviderCapabilities(
            toolCalling = true,
            streaming = true,
            structuredOutput = false,
        )
    }
}

private fun decodeModelList(body: String): List<ProviderModelDescriptor> {
    val root = Json.parseToJsonElement(body)
    val entries = when (root) {
        is JsonArray -> root
        is JsonObject -> listOf("data", "models")
            .firstNotNullOfOrNull { field -> root[field] as? JsonArray }
            ?: error("Model discovery response has no model collection")
        else -> error("Model discovery response must be an object or array")
    }
    return entries.mapNotNull { entry ->
        val model = entry as? JsonObject ?: return@mapNotNull null
        val id = model.string("id")
            ?: model.string("model")
            ?: return@mapNotNull null
        if (id.isBlank()) return@mapNotNull null
        val displayName = model.string("name")
            ?.takeIf(String::isNotBlank)
            ?: model.string("display_name")
                ?.takeIf(String::isNotBlank)
            ?: id
        val contextWindow = listOf("context_window", "context_length", "max_context_length")
            .firstNotNullOfOrNull { field -> model.long(field) }
            ?.takeIf { it > 0 }
        ProviderModelDescriptor(
            id = id,
            displayName = displayName,
            contextWindowTokens = contextWindow,
        )
    }.distinctBy(ProviderModelDescriptor::id).sortedBy(ProviderModelDescriptor::id)
}

private fun JsonObject.string(field: String): String? =
    (this[field] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(field: String): Long? =
    (this[field] as? JsonPrimitive)?.longOrNull
