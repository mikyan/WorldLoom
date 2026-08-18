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
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.provider.openai.OPENAI_API_KEY
import io.worldloom.provider.openai.OpenAiChatCompletionsConfig
import io.worldloom.provider.openai.OpenAiChatCompletionsProvider
import io.worldloom.provider.openai.createOpenAiHttpClient

private val CONTRACT_WORLD_DIRECTORIES = listOf("war-survival", "station-ai")

class MainActivity : ComponentActivity() {
    private var providerClient: HttpClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val driver = AndroidPersistenceDriverFactory(applicationContext).create()
        val session = DefaultGameSession(
            catalog = loadContractWorldCatalog(),
            eventStore = SqlDelightEventStore(WorldloomDatabase(driver)),
        )
        val vault = AndroidKeystoreCredentialVault(applicationContext)
        val client = createOpenAiHttpClient()
        providerClient = client
        val provider = OpenAiChatCompletionsProvider(
            httpClient = client,
            credentialVault = vault,
            config = OpenAiChatCompletionsConfig(model = DEFAULT_OPENAI_MODEL),
        )
        val agentController = DefaultGameAgentController(
            runtime = AgentRuntime(provider, DefaultAgentToolGateway(session)),
            gameSession = session,
        )
        val credentialConfiguration = CredentialConfiguration(vault, OPENAI_API_KEY)
        setContent {
            WorldloomApp(
                session = session,
                agentController = agentController,
                credentialConfiguration = credentialConfiguration,
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
                files = mapOf("world.json" to readAsset("$directory/world.json")),
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

private const val DEFAULT_OPENAI_MODEL = "gpt-5.6-luna"
