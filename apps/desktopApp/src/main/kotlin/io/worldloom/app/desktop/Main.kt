package io.worldloom.app.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import io.worldloom.agent.runtime.AgentRuntime
import io.worldloom.agent.runtime.DefaultAgentToolGateway
import io.worldloom.agent.runtime.DefaultGameAgentController
import io.worldloom.application.DefaultGameSession
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.WorldPackageSource
import io.worldloom.ui.game.WorldloomApp
import io.worldloom.persistence.DesktopPersistenceDriverFactory
import io.worldloom.persistence.SqlDelightEventStore
import io.worldloom.persistence.SqlDelightCharacterCreationDraftStore
import io.worldloom.persistence.SqlDelightGameTurnStore
import io.worldloom.persistence.SqlDelightAgentSessionStore
import io.worldloom.persistence.SqlDelightProviderConfigurationStore
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.platform.credentials.CredentialConfiguration
import io.worldloom.platform.credentials.createDesktopCredentialVault
import io.worldloom.provider.openai.OPENAI_API_KEY
import io.worldloom.provider.openai.OpenAiConfigurableAdapter
import io.worldloom.provider.openai.OPENAI_ADAPTER_ID
import io.worldloom.provider.openai.createOpenAiHttpClient
import io.worldloom.provider.api.ProviderConfiguration
import io.worldloom.provider.api.ProviderConfigurationCenter
import io.worldloom.provider.api.ProviderConfigurationId
import io.worldloom.provider.api.SelectedProviderLanguageModelProvider
import java.nio.file.Files
import java.nio.file.Paths

private val CONTRACT_WORLD_DIRECTORIES = listOf("war-survival", "station-ai")

fun main() {
    val catalog = loadContractWorldCatalog()
    val database = createDatabase()
    val session = DefaultGameSession(
        catalog,
        eventStore = SqlDelightEventStore(database),
        characterDraftStore = SqlDelightCharacterCreationDraftStore(database),
    )
    val dataDirectory = worldloomDataDirectory()
    val vault = createDesktopCredentialVault(dataDirectory.resolve("credentials"))
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
        turnStore = SqlDelightGameTurnStore(database),
    )
    val credentialConfiguration = CredentialConfiguration(vault, OPENAI_API_KEY)

    application {
        Window(
            title = "Worldloom / 织境",
            state = WindowState(width = 1100.dp, height = 820.dp),
            onCloseRequest = {
                providerClient.close()
                exitApplication()
            },
        ) {
            WorldloomApp(
                session = session,
                agentController = agentController,
                credentialConfiguration = credentialConfiguration,
                providerConfigurationCenter = providerCenter,
                providerConfigurationId = providerConfiguration.id,
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

private fun defaultProviderConfiguration() = ProviderConfiguration(
    id = ProviderConfigurationId("openai.primary"),
    adapterId = OPENAI_ADAPTER_ID,
    displayName = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    modelId = DEFAULT_OPENAI_MODEL,
    credentialKey = OPENAI_API_KEY.value,
)

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

private const val DEFAULT_OPENAI_MODEL = "gpt-5.6-luna"
