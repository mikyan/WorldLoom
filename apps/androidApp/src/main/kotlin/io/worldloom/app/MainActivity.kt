package io.worldloom.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.ktor.client.HttpClient
import io.worldloom.agent.runtime.AgentRuntime
import io.worldloom.agent.runtime.DefaultAgentToolGateway
import io.worldloom.agent.runtime.DefaultGameAgentController
import io.worldloom.application.DefaultGameSession
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.WorldPackageSource
import io.worldloom.ui.game.WorldloomApp
import io.worldloom.platform.credentials.AndroidKeystoreCredentialVault
import io.worldloom.platform.credentials.CredentialConfiguration
import io.worldloom.persistence.AndroidPersistenceDriverFactory
import io.worldloom.persistence.SqlDelightEventStore
import io.worldloom.persistence.SqlDelightCharacterCreationDraftStore
import io.worldloom.persistence.SqlDelightAgentSessionStore
import io.worldloom.persistence.SqlDelightProviderConfigurationStore
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.provider.openai.OPENAI_API_KEY
import io.worldloom.provider.openai.OpenAiConfigurableAdapter
import io.worldloom.provider.openai.OPENAI_ADAPTER_ID
import io.worldloom.provider.openai.createOpenAiHttpClient
import io.worldloom.provider.api.ProviderConfiguration
import io.worldloom.provider.api.ProviderConfigurationCenter
import io.worldloom.provider.api.ProviderConfigurationId
import io.worldloom.provider.api.SelectedProviderLanguageModelProvider

private val CONTRACT_WORLD_DIRECTORIES = listOf("war-survival", "station-ai")

class MainActivity : ComponentActivity() {
    private var providerClient: HttpClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val driver = AndroidPersistenceDriverFactory(applicationContext).create()
        val database = WorldloomDatabase(driver)
        val session = DefaultGameSession(
            catalog = loadContractWorldCatalog(),
            eventStore = SqlDelightEventStore(database),
            characterDraftStore = SqlDelightCharacterCreationDraftStore(database),
        )
        val vault = AndroidKeystoreCredentialVault(applicationContext)
        val client = createOpenAiHttpClient()
        providerClient = client
        val providerConfiguration = defaultProviderConfiguration()
        val providerCenter = ProviderConfigurationCenter(
            adapters = listOf(OpenAiConfigurableAdapter(client, vault)),
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
        setContent {
            WorldloomApp(
                session = session,
                agentController = agentController,
                credentialConfiguration = credentialConfiguration,
                providerConfigurationCenter = providerCenter,
                providerConfigurationId = providerConfiguration.id,
            )
        }
    }

    override fun onDestroy() {
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

private fun defaultProviderConfiguration() = ProviderConfiguration(
    id = ProviderConfigurationId("openai.primary"),
    adapterId = OPENAI_ADAPTER_ID,
    displayName = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    modelId = DEFAULT_OPENAI_MODEL,
    credentialKey = OPENAI_API_KEY.value,
)

private const val DEFAULT_OPENAI_MODEL = "gpt-5.6-luna"
