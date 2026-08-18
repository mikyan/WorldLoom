package io.worldloom.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.worldloom.agent.runtime.AgentRuntime
import io.worldloom.agent.runtime.DefaultAgentToolGateway
import io.worldloom.agent.runtime.DefaultGameAgentController
import io.worldloom.agent.runtime.GameAgentState
import io.worldloom.application.DefaultGameSession
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.LoadResult
import io.worldloom.application.SequentialSessionIdSource
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.WorldPackageSource
import io.worldloom.platform.credentials.CredentialWriteResult
import io.worldloom.platform.credentials.SecretValue
import io.worldloom.platform.credentials.SessionCredentialVault
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiAgentVerticalSliceTest {
    @Test
    fun openAiStreamDrivesToolCommandEventAndVisibleProjection() = runBlocking {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += assertIs<TextContent>(request.body).text
            val content = if (requests.size == 1) TOOL_CALL_STREAM else FINAL_TEXT_STREAM
            respond(
                content = ByteReadChannel(content),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
        val vault = SessionCredentialVault()
        assertIs<CredentialWriteResult.Success>(vault.write(OPENAI_API_KEY, SecretValue.create("fixture-secret")))
        val provider = OpenAiChatCompletionsProvider(
            httpClient = HttpClient(engine),
            credentialVault = vault,
            config = OpenAiChatCompletionsConfig(
                model = "fixture-model",
                baseUrl = "http://localhost/v1",
                allowInsecureTransport = true,
            ),
        )
        val catalog = stationCatalog()
        val session = DefaultGameSession(
            catalog = catalog,
            idSource = SequentialSessionIdSource("provider-e2e"),
        )
        assertIs<LoadResult.Success>(session.load(catalog.entries.single().id))
        val controller = DefaultGameAgentController(
            runtime = AgentRuntime(provider, DefaultAgentToolGateway(session)),
            gameSession = session,
        )

        controller.send("降低十点能源")

        val agentState = controller.state.value
        assertTrue(agentState is GameAgentState.Completed, agentState.toString())
        assertEquals("能源已降低。", agentState.text)
        val ready = assertIs<GameSessionUiState.Ready>(session.state.value)
        assertEquals(70, ready.presentation.fields.single().value)
        assertEquals(1, ready.presentation.lastSequence)
        assertEquals(1, ready.presentation.timeline.size)
        assertEquals(2, requests.size)
        assertTrue(requests[1].contains("\"role\":\"tool\""))
        assertTrue(requests[1].contains("70"))
        assertFalse(requests.any { it.contains("fixture-secret") })
    }

    private fun stationCatalog(): StaticWorldCatalog {
        val loader = checkNotNull(Thread.currentThread().contextClassLoader)
        val manifest = checkNotNull(loader.getResource("station-ai/manifest.json")).readText()
        val world = checkNotNull(loader.getResource("station-ai/world.json")).readText()
        return assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(
                listOf(WorldPackageSource(manifest, mapOf("world.json" to world))),
            ),
        ).catalog
    }

    private companion object {
        val TOOL_CALL_STREAM = """
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"worldloom.tool.numeric.adjust","arguments":"{\"entityId\":\"player-ai\",\"componentId\":\"station.capacity\",\"fieldId\":\"station.energy\",\"delta\":-10}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]

        """.trimIndent()

        val FINAL_TEXT_STREAM = """
            data: {"choices":[{"index":0,"delta":{"content":"能源已降低。"},"finish_reason":"stop"}]}

            data: {"choices":[],"usage":{"prompt_tokens":20,"completion_tokens":6}}

            data: [DONE]

        """.trimIndent()
    }
}
