package io.worldloom.agent.runtime

import io.worldloom.application.DefaultGameSession
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.LoadResult
import io.worldloom.application.SequentialSessionIdSource
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.WorldPackageSource
import io.worldloom.definition.DefinitionId
import io.worldloom.provider.api.LanguageModelProvider
import io.worldloom.provider.api.ProviderCapabilities
import io.worldloom.provider.api.ProviderFailureCode
import io.worldloom.provider.api.ProviderRequest
import io.worldloom.provider.api.ProviderResult
import io.worldloom.provider.api.ProviderToolCall
import io.worldloom.provider.api.ProviderTurn
import io.worldloom.provider.api.ProviderUsage
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GameTurnOrchestratorContractTest {
    @Test
    fun gmProjectsVisibleSceneRunsDynamicActionAndDeduplicatesTurn() = runTest {
        val session = playableSession("gm-success")
        val provider = ActionThenNarrateProvider()
        val followUps = RecordingFollowUpDispatcher()
        val turnStore = InMemoryGameTurnStore()
        val orchestrator = GameTurnOrchestrator(
            runtime = AgentRuntime(
                provider,
                DefaultAgentToolGateway(session, followUps),
                InMemoryAgentSessionStore(),
            ),
            gameSession = session,
            turnStore = turnStore,
        )
        val turnId = TurnId("player-turn.1")

        val first = assertIs<GmTurnResult.Completed>(orchestrator.submit(turnId, "我搜索附近的补给"))
        val ready = assertIs<GameSessionUiState.Ready>(session.state.value)

        assertEquals(9, ready.presentation.lastSequence)
        assertEquals(9, first.turn.deliveredSequence)
        assertTrue(first.turn.worldChanged)
        assertEquals(2, provider.requests.size)
        assertTrue(provider.requests.first().messages.first().content.orEmpty().contains("废墟边缘"))
        val actionTool = assertNotNull(provider.requests.first().tools.singleOrNull { it.name == PERFORM_ACTION_TOOL_ID.value })
        assertEquals(
            listOf("war.action.search-supplies"),
            actionTool.parameters.single { it.name == "actionId" }.allowedValues,
        )
        assertEquals(1, followUps.requests.size)
        assertEquals(5, followUps.requests.single().afterSequence)
        assertEquals(9, followUps.requests.single().committedThroughSequence)
        assertTrue(provider.requests.last().messages.any { it.content.orEmpty().contains("foregroundResults") })

        val duplicate = assertIs<GmTurnResult.Completed>(orchestrator.submit(turnId, "我搜索附近的补给"))
        assertEquals(first.turn, duplicate.turn)
        assertEquals(2, provider.requests.size)
        assertEquals(9, assertIs<GameSessionUiState.Ready>(session.state.value).presentation.lastSequence)

        val collision = assertIs<GmTurnResult.Failed>(orchestrator.submit(turnId, "改成另一件事"))
        assertTrue(collision.turn.error.orEmpty().contains("different input"))
    }

    @Test
    fun clarificationDoesNotCommitFactsAndIsIdempotent() = runTest {
        val session = playableSession("gm-clarify")
        val provider = SingleTurnProvider(
            ProviderResult.Success(
                ProviderTurn("CLARIFY: 你要搜索哪个区域？", usage = ProviderUsage(3, 2)),
            ),
        )
        val store = InMemoryGameTurnStore()
        val orchestrator = GameTurnOrchestrator(
            AgentRuntime(provider, DefaultAgentToolGateway(session)),
            session,
            store,
        )

        val result = assertIs<GmTurnResult.AwaitingPlayer>(
            orchestrator.submit(TurnId("player-turn.clarify"), "我搜索一下"),
        )

        assertEquals("你要搜索哪个区域？", result.turn.output)
        assertEquals(5, result.turn.deliveredSequence)
        assertEquals(5, assertIs<GameSessionUiState.Ready>(session.state.value).presentation.lastSequence)
        assertIs<GmTurnResult.AwaitingPlayer>(
            orchestrator.submit(TurnId("player-turn.clarify"), "我搜索一下"),
        )
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun providerFailureAfterToolCommitPersistsRecoverablePartialTurn() = runTest {
        val session = playableSession("gm-partial")
        val provider = ActionThenFailProvider()
        val store = InMemoryGameTurnStore()
        val turnId = TurnId("player-turn.partial")
        val orchestrator = GameTurnOrchestrator(
            AgentRuntime(provider, DefaultAgentToolGateway(session)),
            session,
            store,
        )

        val failed = assertIs<GmTurnResult.Failed>(orchestrator.submit(turnId, "搜索补给"))

        assertTrue(failed.turn.worldChanged)
        assertEquals(9, failed.turn.deliveredSequence)
        assertEquals(GameTurnStatus.FAILED, assertNotNull(store.load(failed.turn.runId, turnId)).status)
        assertEquals(9, assertIs<GameSessionUiState.Ready>(session.state.value).presentation.lastSequence)

        val recoveredWithoutCallingProvider = GameTurnOrchestrator(
            AgentRuntime(UnusedProvider, DefaultAgentToolGateway(session)),
            session,
            store,
        )
        val duplicate = assertIs<GmTurnResult.Failed>(recoveredWithoutCallingProvider.submit(turnId, "搜索补给"))
        assertTrue(duplicate.turn.worldChanged)
        assertEquals(9, assertIs<GameSessionUiState.Ready>(session.state.value).presentation.lastSequence)
    }

    @Test
    fun illegalDynamicActionIsRejectedBeforeWorldChanges() = runTest {
        val session = playableSession("gm-illegal")
        val provider = SingleTurnProvider(
            ProviderResult.Success(
                ProviderTurn(
                    toolCalls = listOf(actionCall("war.action.signal-convoy")),
                    usage = ProviderUsage(3, 1),
                ),
            ),
        )
        val result = GameTurnOrchestrator(
            AgentRuntime(provider, DefaultAgentToolGateway(session)),
            session,
        ).submit(TurnId("player-turn.illegal"), "直接联络车队")

        val failure = assertIs<GmTurnResult.Failed>(result)
        assertTrue(!failure.turn.worldChanged)
        assertEquals(5, assertIs<GameSessionUiState.Ready>(session.state.value).presentation.lastSequence)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.playableSession(prefix: String): DefaultGameSession {
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(listOf(loadPackage("war-survival"))),
        ).catalog
        return DefaultGameSession(
            catalog,
            idSource = SequentialSessionIdSource(prefix),
            workerDispatcher = StandardTestDispatcher(testScheduler),
        ).also { session ->
            assertIs<LoadResult.Success>(session.load(DefinitionId("contract.war-survival")))
            assertIs<io.worldloom.application.ActionResult.Success>(session.confirmCharacter())
        }
    }

    private fun loadPackage(directory: String): WorldPackageSource = WorldPackageSource(
        manifestJson = resourceText("$directory/manifest.json"),
        files = mapOf(
            "world.json" to resourceText("$directory/world.json"),
            "playable-world.json" to resourceText("$directory/playable-world.json"),
            "character-profile.json" to resourceText("$directory/character-profile.json"),
            "behaviors/activity-starts-quest.json" to resourceText("$directory/behaviors/activity-starts-quest.json"),
            "behaviors/quest-raises-threat.json" to resourceText("$directory/behaviors/quest-raises-threat.json"),
            "behaviors/timed-supply.json" to resourceText("$directory/behaviors/timed-supply.json"),
        ),
    )

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "Missing test resource: $path" }
            .bufferedReader()
            .use { it.readText() }

    private class RecordingFollowUpDispatcher : GameTurnFollowUpDispatcher {
        val requests = mutableListOf<GameTurnFollowUpRequest>()

        override suspend fun dispatch(request: GameTurnFollowUpRequest): GameTurnFollowUpResult {
            requests += request
            return GameTurnFollowUpResult.Completed(
                listOf(
                    PublicFollowUp("behavior", "远处响起警报"),
                    PublicFollowUp("npc", "同伴示意你压低声音"),
                ),
            )
        }
    }

    private class ActionThenNarrateProvider : LanguageModelProvider {
        override val capabilities = ProviderCapabilities(toolCalling = true, streaming = false, structuredOutput = false)
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun complete(request: ProviderRequest): ProviderResult {
            requests += request
            return when (requests.size) {
                1 -> ProviderResult.Success(
                    ProviderTurn(toolCalls = listOf(actionCall("war.action.search-supplies")), usage = ProviderUsage(4, 1)),
                )
                2 -> ProviderResult.Success(ProviderTurn("局势已经改变。", usage = ProviderUsage(4, 2)))
                else -> error("Unexpected provider call")
            }
        }
    }

    private class ActionThenFailProvider : LanguageModelProvider {
        override val capabilities = ProviderCapabilities(toolCalling = true, streaming = false, structuredOutput = false)
        private var calls = 0

        override suspend fun complete(request: ProviderRequest): ProviderResult = when (++calls) {
            1 -> ProviderResult.Success(
                ProviderTurn(toolCalls = listOf(actionCall("war.action.search-supplies")), usage = ProviderUsage(4, 1)),
            )
            else -> ProviderResult.Failure(ProviderFailureCode.NETWORK, "connection lost", retryable = true)
        }
    }

    private class SingleTurnProvider(private val result: ProviderResult) : LanguageModelProvider {
        override val capabilities = ProviderCapabilities(toolCalling = true, streaming = false, structuredOutput = false)
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun complete(request: ProviderRequest): ProviderResult {
            requests += request
            return result
        }
    }

    private data object UnusedProvider : LanguageModelProvider {
        override val capabilities = ProviderCapabilities(toolCalling = true, streaming = false, structuredOutput = false)
        override suspend fun complete(request: ProviderRequest): ProviderResult = error("Provider must not be called")
    }

    companion object {
        private fun actionCall(actionId: String) = ProviderToolCall(
            id = "action-call",
            name = PERFORM_ACTION_TOOL_ID.value,
            arguments = JsonObject(mapOf("actionId" to JsonPrimitive(actionId))),
        )
    }
}
