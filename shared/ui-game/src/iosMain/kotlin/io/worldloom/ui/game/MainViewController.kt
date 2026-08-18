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
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.platform.credentials.CredentialConfiguration
import io.worldloom.platform.credentials.IosKeychainCredentialVault
import io.worldloom.provider.openai.OPENAI_API_KEY
import io.worldloom.provider.openai.OpenAiChatCompletionsConfig
import io.worldloom.provider.openai.OpenAiChatCompletionsProvider
import io.worldloom.provider.openai.createOpenAiHttpClient

fun MainViewController(
    manifestSources: List<String>,
    worldSources: List<String>,
): UIViewController {
    require(manifestSources.size == worldSources.size) { "Manifest and world source counts must match" }
    val catalog = loadCatalog(manifestSources.zip(worldSources))
    val driver = IosPersistenceDriverFactory().create()
    val session = DefaultGameSession(
        catalog,
        eventStore = SqlDelightEventStore(WorldloomDatabase(driver)),
    )
    val vault = IosKeychainCredentialVault()
    val providerClient = createOpenAiHttpClient()
    val provider = OpenAiChatCompletionsProvider(
        httpClient = providerClient,
        credentialVault = vault,
        config = OpenAiChatCompletionsConfig(model = DEFAULT_OPENAI_MODEL),
    )
    val agentController = DefaultGameAgentController(
        runtime = AgentRuntime(provider, DefaultAgentToolGateway(session)),
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
        )
    }
}

private fun loadCatalog(sources: List<Pair<String, String>>): StaticWorldCatalog =
    when (
        val result = StaticWorldCatalog.fromPackageSources(
            sources.map { (manifest, world) ->
                WorldPackageSource(manifest, mapOf("world.json" to world))
            },
        )
    ) {
        is StaticWorldCatalogResult.Success -> result.catalog
        is StaticWorldCatalogResult.Failure -> error(
            "Invalid contract world resource at index ${result.sourceIndex}: ${result.message}",
        )
    }

private const val DEFAULT_OPENAI_MODEL = "gpt-5.6-luna"
