package io.worldloom.app.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
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
import io.worldloom.persistence.DesktopPersistenceDriverFactory
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
import io.worldloom.platform.credentials.createDesktopCredentialVault
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
import java.nio.file.Files
import java.nio.file.Paths

private val CONTRACT_WORLD_DIRECTORIES = listOf("war-survival", "station-ai")

fun main() {
    val agentBackgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val catalog = loadContractWorldCatalog()
    val database = createDatabase()
    val eventStore = SqlDelightEventStore(database)
    val session = DefaultGameSession(
        catalog,
        eventStore = eventStore,
        characterDraftStore = SqlDelightCharacterCreationDraftStore(database),
        behaviorWorkStore = SqlDelightBehaviorWorkStore(database),
    )
    val saveCoordinator = SaveCoordinator(session, SqlDelightRunDirectoryStore(database))
    val dataDirectory = worldloomDataDirectory()
    val vault = createDesktopCredentialVault(dataDirectory.resolve("credentials"))
    val providerClient = createOpenAiHttpClient()
    val providerSources = OpenAiSubscriptionSources.all
    val providerCenter = ProviderConfigurationCenter(
        adapters = listOf(OpenAiConfigurableAdapter(providerClient, vault)),
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

    application {
        Window(
            title = "Worldloom / 织境",
            state = WindowState(width = 1100.dp, height = 820.dp),
            onCloseRequest = {
                agentBackgroundScope.cancel()
                providerClient.close()
                exitApplication()
            },
        ) {
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
}

private fun createDatabase(): WorldloomDatabase {
    val dataDirectory = worldloomDataDirectory()
    Files.createDirectories(dataDirectory)
    val driver = DesktopPersistenceDriverFactory(dataDirectory.resolve("worldloom.db").toString()).create()
    return WorldloomDatabase(driver)
}

private fun worldloomDataDirectory() = Paths.get(
    System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
    "Worldloom",
)

private fun loadContractWorldCatalog(): StaticWorldCatalog {
    val classLoader = checkNotNull(Thread.currentThread().contextClassLoader) {
        "Desktop resource class loader is unavailable"
    }
    val sources = CONTRACT_WORLD_DIRECTORIES.map { directory ->
        WorldPackageSource(
            manifestJson = classLoader.readResource("$directory/manifest.json"),
            files = mapOf(
                "world.json" to classLoader.readResource("$directory/world.json"),
                "playable-world.json" to classLoader.readResource("$directory/playable-world.json"),
                "character-profile.json" to classLoader.readResource("$directory/character-profile.json"),
                "behaviors/activity-starts-quest.json" to classLoader.readResource("$directory/behaviors/activity-starts-quest.json"),
                "behaviors/quest-raises-threat.json" to classLoader.readResource("$directory/behaviors/quest-raises-threat.json"),
                "behaviors/timed-supply.json" to classLoader.readResource("$directory/behaviors/timed-supply.json"),
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

private fun ClassLoader.readResource(path: String): String =
    checkNotNull(getResource(path)) { "Missing contract world resource: $path" }.readText()
