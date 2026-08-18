package io.worldloom.ui.game

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.ComposeUIViewController
import io.worldloom.agent.runtime.AgentRuntime
import io.worldloom.agent.runtime.DefaultAgentToolGateway
import io.worldloom.agent.runtime.DefaultGameAgentController
import io.worldloom.agent.runtime.NpcSceneOrchestrator
import io.worldloom.application.DefaultGameSession
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.WorldPackageSource
import io.worldloom.application.SaveCoordinator
import platform.UIKit.UIViewController
import io.worldloom.persistence.IosPersistenceDriverFactory
import io.worldloom.persistence.SqlDelightEventStore
import io.worldloom.persistence.SqlDelightCharacterCreationDraftStore
import io.worldloom.persistence.SqlDelightGameTurnStore
import io.worldloom.persistence.SqlDelightAgentSessionStore
import io.worldloom.persistence.SqlDelightProviderConfigurationStore
import io.worldloom.persistence.SqlDelightBehaviorWorkStore
import io.worldloom.persistence.SqlDelightNpcWorkStore
import io.worldloom.persistence.SqlDelightAgentMemoryStore
import io.worldloom.persistence.SqlDelightRunDirectoryStore
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

fun MainViewController(
    manifestSources: List<String>,
    worldSources: List<String>,
    playableSources: List<String>,
    characterProfileSources: List<String>,
    activityBehaviorSources: List<String>,
    questBehaviorSources: List<String>,
    timedBehaviorSources: List<String>,
): UIViewController {
    val agentBackgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    require(
        listOf(
            worldSources,
            playableSources,
            characterProfileSources,
            activityBehaviorSources,
            questBehaviorSources,
            timedBehaviorSources,
        ).all { it.size == manifestSources.size },
    ) { "Contract world source counts must match" }
    val catalog = loadCatalog(manifestSources.indices.map { index ->
        ContractSources(
            manifestSources[index],
            worldSources[index],
            playableSources[index],
            characterProfileSources[index],
            activityBehaviorSources[index],
            questBehaviorSources[index],
            timedBehaviorSources[index],
        )
    })
    val driver = IosPersistenceDriverFactory().create()
    val database = WorldloomDatabase(driver)
    val eventStore = SqlDelightEventStore(database)
    val session = DefaultGameSession(
        catalog,
        eventStore = eventStore,
        characterDraftStore = SqlDelightCharacterCreationDraftStore(database),
        behaviorWorkStore = SqlDelightBehaviorWorkStore(database),
    )
    val saveCoordinator = SaveCoordinator(session, SqlDelightRunDirectoryStore(database))
    val vault = IosKeychainCredentialVault()
    val providerClient = createOpenAiHttpClient()
    val providerConfiguration = defaultProviderConfiguration()
    val providerCenter = ProviderConfigurationCenter(
        adapters = listOf(OpenAiConfigurableAdapter(providerClient, vault)),
        store = SqlDelightProviderConfigurationStore(database, providerConfiguration),
    )
    val selectedProvider = SelectedProviderLanguageModelProvider(providerCenter)
    val agentSessionStore = SqlDelightAgentSessionStore(database)
    val npcFollowUps = NpcSceneOrchestrator(
        runtime = AgentRuntime(selectedProvider, DefaultAgentToolGateway(session), agentSessionStore),
        gameSession = session,
        workStore = SqlDelightNpcWorkStore(database),
        memoryStoreFactory = { runId -> SqlDelightAgentMemoryStore(database, runId) },
    )
    val playerAndGmTools = DefaultAgentToolGateway(session, npcFollowUps)
    val agentController = DefaultGameAgentController(
        runtime = AgentRuntime(
            selectedProvider,
            playerAndGmTools,
            agentSessionStore,
        ),
        gameSession = session,
        turnStore = SqlDelightGameTurnStore(database),
        directToolGateway = playerAndGmTools,
        memoryStoreFactory = { runId -> SqlDelightAgentMemoryStore(database, runId) },
        backgroundScope = agentBackgroundScope,
    )
    val credentialConfiguration = CredentialConfiguration(vault, OPENAI_API_KEY)
    return ComposeUIViewController {
        DisposableEffect(providerClient) {
            onDispose {
                agentBackgroundScope.cancel()
                providerClient.close()
            }
        }
        WorldloomApp(
            session = session,
            saveCoordinator = saveCoordinator,
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
    val activityBehavior: String,
    val questBehavior: String,
    val timedBehavior: String,
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
                        "behaviors/activity-starts-quest.json" to source.activityBehavior,
                        "behaviors/quest-raises-threat.json" to source.questBehavior,
                        "behaviors/timed-supply.json" to source.timedBehavior,
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
