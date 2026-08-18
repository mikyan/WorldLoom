package io.worldloom.ui.game

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.ComposeUIViewController
import io.worldloom.agent.runtime.AgentRuntime
import io.worldloom.agent.runtime.DefaultAgentToolGateway
import io.worldloom.agent.runtime.DefaultGameAgentController
import io.worldloom.application.DefaultGameSession
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.WorldPackageSource
import platform.UIKit.UIViewController
import io.worldloom.persistence.IosPersistenceDriverFactory
import io.worldloom.persistence.SqlDelightEventStore
import io.worldloom.persistence.SqlDelightCharacterCreationDraftStore
import io.worldloom.persistence.SqlDelightAgentSessionStore
import io.worldloom.persistence.SqlDelightProviderConfigurationStore
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.platform.credentials.CredentialConfiguration
import io.worldloom.platform.credentials.IosKeychainCredentialVault
import io.worldloom.provider.openai.OPENAI_API_KEY
import io.worldloom.provider.openai.OpenAiConfigurableAdapter
import io.worldloom.provider.openai.OPENAI_ADAPTER_ID
import io.worldloom.provider.openai.createOpenAiHttpClient
import io.worldloom.provider.api.ProviderConfiguration
import io.worldloom.provider.api.ProviderConfigurationCenter
import io.worldloom.provider.api.ProviderConfigurationId
import io.worldloom.provider.api.SelectedProviderLanguageModelProvider

fun MainViewController(
    manifestSources: List<String>,
    worldSources: List<String>,
    playableSources: List<String>,
    characterProfileSources: List<String>,
): UIViewController {
    require(
        listOf(worldSources, playableSources, characterProfileSources).all { it.size == manifestSources.size },
    ) { "Contract world source counts must match" }
    val catalog = loadCatalog(manifestSources.indices.map { index ->
        ContractSources(
            manifestSources[index],
            worldSources[index],
            playableSources[index],
            characterProfileSources[index],
        )
    })
    val driver = IosPersistenceDriverFactory().create()
    val database = WorldloomDatabase(driver)
    val session = DefaultGameSession(
        catalog,
        eventStore = SqlDelightEventStore(database),
        characterDraftStore = SqlDelightCharacterCreationDraftStore(database),
    )
    val vault = IosKeychainCredentialVault()
    val providerClient = createOpenAiHttpClient()
    val providerConfiguration = defaultProviderConfiguration()
    val providerCenter = ProviderConfigurationCenter(
        adapters = listOf(OpenAiConfigurableAdapter(providerClient, vault)),
        store = SqlDelightProviderConfigurationStore(database, providerConfiguration),
    )
    val agentController = DefaultGameAgentController(
        runtime = AgentRuntime(
            SelectedProviderLanguageModelProvider(providerCenter),
            DefaultAgentToolGateway(session),
            SqlDelightAgentSessionStore(database),
        ),
        gameSession = session,
    )
    val credentialConfiguration = CredentialConfiguration(vault, OPENAI_API_KEY)
    return ComposeUIViewController {
        DisposableEffect(providerClient) {
            onDispose { providerClient.close() }
        }
        WorldloomApp(
            session = session,
            agentController = agentController,
            credentialConfiguration = credentialConfiguration,
            providerConfigurationCenter = providerCenter,
            providerConfigurationId = providerConfiguration.id,
        )
    }
}

private data class ContractSources(
    val manifest: String,
    val world: String,
    val playable: String,
    val characterProfile: String,
)

private fun loadCatalog(sources: List<ContractSources>): StaticWorldCatalog =
    when (
        val result = StaticWorldCatalog.fromPackageSources(
            sources.map { source ->
                WorldPackageSource(
                    source.manifest,
                    mapOf(
                        "world.json" to source.world,
                        "playable-world.json" to source.playable,
                        "character-profile.json" to source.characterProfile,
                    ),
                )
            },
        )
    ) {
        is StaticWorldCatalogResult.Success -> result.catalog
        is StaticWorldCatalogResult.Failure -> error(
            "Invalid contract world resource at index ${result.sourceIndex}: ${result.message}",
        )
    }

private const val DEFAULT_OPENAI_MODEL = "gpt-5.6-luna"

private fun defaultProviderConfiguration() = ProviderConfiguration(
    id = ProviderConfigurationId("openai.primary"),
    adapterId = OPENAI_ADAPTER_ID,
    displayName = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    modelId = DEFAULT_OPENAI_MODEL,
    credentialKey = OPENAI_API_KEY.value,
)
