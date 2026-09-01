package io.worldloom.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.ktor.client.HttpClient
import io.worldloom.agent.runtime.AgentRuntime
import io.worldloom.agent.runtime.DefaultAgentToolGateway
import io.worldloom.agent.runtime.DefaultGameAgentController
import io.worldloom.agent.runtime.NpcSceneOrchestrator
import io.worldloom.application.DefaultGameSession
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.WorldPackageSource
import io.worldloom.application.SaveCoordinator
import io.worldloom.ui.game.WorldloomApp
import io.worldloom.platform.credentials.AndroidKeystoreCredentialVault
import io.worldloom.platform.credentials.CredentialConfiguration
import io.worldloom.persistence.AndroidPersistenceDriverFactory
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
import io.worldloom.provider.openai.OpenAiConfigurableAdapter
import io.worldloom.provider.openai.OpenAiSubscriptionSource
import io.worldloom.provider.openai.OpenAiSubscriptionSources
import io.worldloom.provider.openai.createOpenAiHttpClient
import io.worldloom.provider.api.ProviderConfigurationCenter
import io.worldloom.provider.api.SelectedProviderLanguageModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

private val CONTRACT_WORLD_DIRECTORIES = listOf("war-survival", "station-ai")

class MainActivity : ComponentActivity() {
    private var providerClient: HttpClient? = null
    private val agentBackgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val driver = AndroidPersistenceDriverFactory(applicationContext).create()
        val database = WorldloomDatabase(driver)
        val eventStore = SqlDelightEventStore(database)
        val session = DefaultGameSession(
            catalog = loadContractWorldCatalog(),
            eventStore = eventStore,
            characterDraftStore = SqlDelightCharacterCreationDraftStore(database),
            behaviorWorkStore = SqlDelightBehaviorWorkStore(database),
        )
        val saveCoordinator = SaveCoordinator(session, SqlDelightRunDirectoryStore(database))
        val vault = AndroidKeystoreCredentialVault(applicationContext)
        val client = createOpenAiHttpClient()
        providerClient = client
        val providerSources = OpenAiSubscriptionSources.all
        val providerCenter = ProviderConfigurationCenter(
            adapters = listOf(OpenAiConfigurableAdapter(client, vault)),
            store = SqlDelightProviderConfigurationStore(
                database,
                providerSources.map(OpenAiSubscriptionSource::defaultConfiguration),
            ),
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
        val credentialConfigurations = providerSources.associate { source ->
            source.configurationId to CredentialConfiguration(vault, source.credentialKey)
        }
        setContent {
            WorldloomApp(
                session = session,
                saveCoordinator = saveCoordinator,
                agentController = agentController,
                providerConfigurationCenter = providerCenter,
                providerSources = providerSources,
                providerCredentialConfigurations = credentialConfigurations,
            )
        }
    }

    override fun onDestroy() {
        agentBackgroundScope.cancel()
        providerClient?.close()
        providerClient = null
        super.onDestroy()
    }

    private fun loadContractWorldCatalog(): StaticWorldCatalog {
        val sources = CONTRACT_WORLD_DIRECTORIES.map { directory ->
            WorldPackageSource(
                manifestJson = readAsset("$directory/manifest.json"),
                files = mapOf(
                    "world.json" to readAsset("$directory/world.json"),
                    "playable-world.json" to readAsset("$directory/playable-world.json"),
                    "character-profile.json" to readAsset("$directory/character-profile.json"),
                    "behaviors/activity-starts-quest.json" to readAsset("$directory/behaviors/activity-starts-quest.json"),
                    "behaviors/quest-raises-threat.json" to readAsset("$directory/behaviors/quest-raises-threat.json"),
                    "behaviors/timed-supply.json" to readAsset("$directory/behaviors/timed-supply.json"),
                ),
            )
        }
        return when (val result = StaticWorldCatalog.fromPackageSources(sources)) {
            is StaticWorldCatalogResult.Success -> result.catalog
            is StaticWorldCatalogResult.Failure -> error(
                "Invalid contract world package at index ${result.sourceIndex}: ${result.message}",
            )
        }
    }

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
