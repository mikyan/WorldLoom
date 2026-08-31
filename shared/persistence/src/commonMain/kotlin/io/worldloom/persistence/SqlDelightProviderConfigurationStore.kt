package io.worldloom.persistence

import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.provider.api.ProviderConfiguration
import io.worldloom.provider.api.ProviderConfigurationId
import io.worldloom.provider.api.ProviderConfigurationStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persists only non-secret Provider settings; [ProviderConfiguration.credentialKey] remains a vault reference. */
class SqlDelightProviderConfigurationStore(
    database: WorldloomDatabase,
    initialConfigurations: List<ProviderConfiguration> = emptyList(),
) : ProviderConfigurationStore {
    private val queries = database.worldloomQueries
    private val mutex = Mutex()

    init {
        require(initialConfigurations.map { it.id }.distinct().size == initialConfigurations.size) {
            "Initial provider configuration ids must be unique"
        }
        queries.ensureProviderSettings()
        initialConfigurations.forEach { configuration ->
            if (queries.selectProviderConfiguration(configuration.id.value).executeAsOneOrNull() == null) {
                insert(configuration)
            }
        }
        if (queries.selectSelectedProviderConfiguration().executeAsOneOrNull()?.selected_configuration_id == null) {
            initialConfigurations.firstOrNull()?.let { configuration ->
                queries.updateSelectedProviderConfiguration(configuration.id.value)
            }
        }
    }

    constructor(database: WorldloomDatabase, initialConfiguration: ProviderConfiguration) :
        this(database, listOf(initialConfiguration))

    override suspend fun list(): List<ProviderConfiguration> = mutex.withLock {
        queries.selectProviderConfigurations().executeAsList().map { row ->
            ProviderConfiguration(
                id = ProviderConfigurationId(row.configuration_id),
                adapterId = row.adapter_id,
                displayName = row.display_name,
                baseUrl = row.base_url,
                modelId = row.model_id,
                credentialKey = row.credential_key,
                maxOutputTokens = row.max_output_tokens.toInt(),
                inputCostMicrounitsPerMillionTokens = row.input_cost_microunits_per_million_tokens,
                outputCostMicrounitsPerMillionTokens = row.output_cost_microunits_per_million_tokens,
                allowInsecureLocalTransport = row.allow_insecure_local_transport != 0L,
            )
        }
    }

    override suspend fun selected(): ProviderConfigurationId? = mutex.withLock {
        queries.selectSelectedProviderConfiguration()
            .executeAsOneOrNull()
            ?.selected_configuration_id
            ?.let(::ProviderConfigurationId)
    }

    override suspend fun put(configuration: ProviderConfiguration) {
        mutex.withLock {
            if (queries.selectProviderConfiguration(configuration.id.value).executeAsOneOrNull() == null) {
                insert(configuration)
            } else {
                queries.updateProviderConfiguration(
                    adapter_id = configuration.adapterId,
                    display_name = configuration.displayName,
                    base_url = configuration.baseUrl,
                    model_id = configuration.modelId,
                    credential_key = configuration.credentialKey,
                    max_output_tokens = configuration.maxOutputTokens.toLong(),
                    input_cost_microunits_per_million_tokens = configuration.inputCostMicrounitsPerMillionTokens,
                    output_cost_microunits_per_million_tokens = configuration.outputCostMicrounitsPerMillionTokens,
                    allow_insecure_local_transport = if (configuration.allowInsecureLocalTransport) 1 else 0,
                    configuration_id = configuration.id.value,
                )
            }
        }
    }

    override suspend fun remove(id: ProviderConfigurationId) {
        mutex.withLock { queries.deleteProviderConfiguration(id.value) }
    }

    override suspend fun select(id: ProviderConfigurationId?) {
        mutex.withLock {
            require(id == null || queries.selectProviderConfiguration(id.value).executeAsOneOrNull() != null) {
                "Selected provider configuration does not exist"
            }
            queries.updateSelectedProviderConfiguration(id?.value)
        }
    }

    private fun insert(configuration: ProviderConfiguration) {
        queries.insertProviderConfiguration(
            configuration_id = configuration.id.value,
            adapter_id = configuration.adapterId,
            display_name = configuration.displayName,
            base_url = configuration.baseUrl,
            model_id = configuration.modelId,
            credential_key = configuration.credentialKey,
            max_output_tokens = configuration.maxOutputTokens.toLong(),
            input_cost_microunits_per_million_tokens = configuration.inputCostMicrounitsPerMillionTokens,
            output_cost_microunits_per_million_tokens = configuration.outputCostMicrounitsPerMillionTokens,
            allow_insecure_local_transport = if (configuration.allowInsecureLocalTransport) 1 else 0,
        )
    }
}
