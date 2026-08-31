package io.worldloom.agent.runtime

import io.worldloom.application.ActionResult
import io.worldloom.application.GamePresentation
import io.worldloom.application.PresentedEvent
import io.worldloom.application.GameSession
import io.worldloom.application.GameSessionAction
import io.worldloom.application.GameSessionCommand
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.LoadResult
import io.worldloom.application.SessionCommandContext
import io.worldloom.application.SessionReplayResult
import io.worldloom.application.WorldCatalogEntry
import io.worldloom.definition.DefinitionId
import io.worldloom.provider.api.ProviderCapabilities
import io.worldloom.provider.api.ProviderRequest
import io.worldloom.provider.api.ProviderResult
import io.worldloom.provider.api.ProviderStreamEvent
import io.worldloom.provider.api.ProviderTurn
import io.worldloom.provider.api.ProviderUsage
import io.worldloom.provider.api.StreamingLanguageModelProvider
import io.worldloom.rules.module.api.RegisteredWorldModules
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.ActorId
import io.worldloom.world.RunId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class GameAgentControllerTest {
    @Test
    fun streamsVisibleTextAndPublishesTheCompletedTurn() = runTest {
        val release = CompletableDeferred<Unit>()
        val provider = GatedStreamingProvider(release)
        val session = StubGameSession(readyState())
        val store = InMemoryAgentSessionStore()
        val controller = DefaultGameAgentController(
            runtime = AgentRuntime(provider, EmptyToolGateway, store),
            gameSession = session,
        )

        val job = launch(start = CoroutineStart.UNDISPATCHED) { controller.send("观察世界") }

        assertEquals("织", assertIs<GameAgentState.Running>(controller.state.value).partialText)
        release.complete(Unit)
        job.join()
        assertEquals("织境回应", assertIs<GameAgentState.Completed>(controller.state.value).text)
        assertTrue(provider.request.messages.first().content.orEmpty().contains("测试世界"))
        val gmSession = assertIs<AgentSessionLoadResult.Success>(
            store.load(
                AgentSessionId("worldloom.gm.test.run"),
                AgentIdentity(
                    AgentId("worldloom.agent.gm"),
                    ActorId("worldloom.actor.gm"),
                    emptySet(),
                ),
                RunId("test.run"),
            ),
        ).snapshot
        assertEquals("织境回应", gmSession.messages.last().content)
    }

    @Test
    fun rejectsInputUntilAWorldIsLoaded() = runTest {
        val controller = DefaultGameAgentController(
            runtime = AgentRuntime(UnusedProvider, EmptyToolGateway),
            gameSession = StubGameSession(GameSessionUiState.Idle, loaded = false),
        )

        controller.send("行动")

        assertEquals("请先加载一个世界。", assertIs<GameAgentState.Failed>(controller.state.value).message)
    }

    @Test
    fun resumeRecoveryProvidesRetryAndReadOnlyNarrationWithoutRepeatingTools() = runTest {
        val session = StubGameSession(
            readyState(
                lastSequence = 2,
                timeline = listOf(PresentedEvent(2, "抵达了新的场景")),
            ),
        )
        val store = InMemoryGameTurnStore()
        val source = GameTurn(
            runId = RunId("test.run"),
            turnId = TurnId("test.run.turn.1"),
            input = "前往出口",
            status = GameTurnStatus.RUNNING,
            revision = 1,
            acceptedSequence = 1,
        )
        assertIs<GameTurnStoreResult.Success>(store.save(source, null))
        val provider = ReadOnlyNarrationProvider()
        val controller = DefaultGameAgentController(
            runtime = AgentRuntime(provider, EmptyToolGateway),
            gameSession = session,
            turnStore = store,
        )

        controller.recoverInterruptedHistory()

        val interrupted = assertNotNull(controller.history.value.items.singleOrNull())
        assertEquals(GameTurnRecoveryKind.NARRATION_REQUIRED, interrupted.recoveryKind)
        controller.recoverNarration(interrupted.turnId)

        assertEquals("你抵达了新的场景。", assertIs<GameAgentState.Completed>(controller.state.value).text)
        assertTrue(provider.request.tools.isEmpty())
        val recovery = assertIs<GameTurn>(store.latest(RunId("test.run")))
        assertEquals(GameTurnRequestKind.NARRATION_RECOVERY, recovery.requestKind)
        assertEquals(source.turnId, recovery.parentTurnId)
        assertEquals(1, recovery.evidenceFromSequenceExclusive)
        assertEquals(2, recovery.evidenceThroughSequenceInclusive)
    }

    @Test
    fun retryUsesNewTurnIdAndKeepsTheOriginalInterruptedRecord() = runTest {
        val session = StubGameSession(readyState())
        val store = InMemoryGameTurnStore()
        val source = GameTurn(
            runId = RunId("test.run"),
            turnId = TurnId("test.run.turn.1"),
            input = "观察",
            status = GameTurnStatus.ACCEPTED,
            revision = 0,
            acceptedSequence = 0,
        )
        assertIs<GameTurnStoreResult.Success>(store.save(source, null))
        val controller = DefaultGameAgentController(
            runtime = AgentRuntime(ImmediateNarrationProvider, EmptyToolGateway),
            gameSession = session,
            turnStore = store,
        )
        controller.recoverInterruptedHistory()

        controller.retry(source.turnId)

        val history = assertIs<GameTurnHistoryResult.Success>(store.history(RunId("test.run"))).page
        assertEquals(2, history.entries.size)
        val original = assertNotNull(history.entries.first().turn)
        val retried = assertNotNull(history.entries.last().turn)
        assertEquals(GameTurnRecoveryKind.RETRY_SAFE, original.recoveryKind)
        assertEquals(GameTurnRequestKind.RETRY, retried.requestKind)
        assertEquals(original.turnId, retried.parentTurnId)
        assertEquals(GameTurnStatus.COMPLETED, retried.status)
    }

    @Test
    fun cancellationPersistsAndRefreshesTheVisibleTurnHistory() = runTest {
        val release = CompletableDeferred<Unit>()
        val store = InMemoryGameTurnStore()
        val controller = DefaultGameAgentController(
            runtime = AgentRuntime(GatedStreamingProvider(release), EmptyToolGateway),
            gameSession = StubGameSession(readyState()),
            turnStore = store,
        )
        val job = launch(start = CoroutineStart.UNDISPATCHED) { controller.send("等待主持") }
        assertIs<GameAgentState.Running>(controller.state.value)

        job.cancelAndJoin()

        val cancelled = assertNotNull(controller.history.value.items.singleOrNull())
        assertEquals(GameTurnStatus.CANCELLED, cancelled.status)
        assertEquals("该回合已取消。", cancelled.safeFailureMessage)
        assertEquals(GameTurnRecoveryKind.RETRY_SAFE, cancelled.recoveryKind)
        assertIs<GameAgentState.Idle>(controller.state.value)

        release.complete(Unit)
        controller.retry(cancelled.turnId)
        assertIs<GameAgentState.Completed>(controller.state.value)
        val retried = controller.history.value.items.last()
        assertEquals(GameTurnStatus.COMPLETED, retried.status)
        assertEquals(2, controller.history.value.items.size)
    }

    @Test
    fun historyReadsCannotInterruptALiveTurnWhenItsEventSequenceAdvances() = runTest {
        val release = CompletableDeferred<Unit>()
        val session = StubGameSession(readyState())
        val store = InMemoryGameTurnStore()
        val controller = DefaultGameAgentController(
            runtime = AgentRuntime(GatedStreamingProvider(release), EmptyToolGateway),
            gameSession = session,
            turnStore = store,
        )
        val job = launch(start = CoroutineStart.UNDISPATCHED) { controller.send("搜寻附近物资") }
        assertIs<GameAgentState.Running>(controller.state.value)

        session.state.value = readyState(
            lastSequence = 1,
            timeline = listOf(PresentedEvent(1, "搜寻结果已提交")),
        )
        controller.refreshHistory()
        controller.recoverInterruptedHistory()

        val running = assertNotNull(store.latest(RunId("test.run")))
        assertEquals(GameTurnStatus.RUNNING, running.status)
        assertEquals(null, controller.history.value.items.single().safeFailureMessage)

        release.complete(Unit)
        job.join()

        val completed = assertNotNull(store.latest(RunId("test.run")))
        assertEquals(GameTurnStatus.COMPLETED, completed.status)
        assertTrue(controller.history.value.items.none { it.status == GameTurnStatus.FAILED })
    }

    private fun readyState(
        lastSequence: Long = 0,
        timeline: List<PresentedEvent> = emptyList(),
    ): GameSessionUiState.Ready = GameSessionUiState.Ready(
        GamePresentation(
            worldId = DefinitionId("test.world"),
            title = "测试世界",
            lastSequence = lastSequence,
            fields = emptyList(),
            checks = emptyList(),
            timeline = timeline,
        ),
    )

    private class StubGameSession(
        initialState: GameSessionUiState,
        private val loaded: Boolean = true,
    ) : GameSession {
        override val availableWorlds: List<WorldCatalogEntry> = emptyList()
        override val state = MutableStateFlow(initialState)

        override suspend fun load(worldId: DefinitionId): LoadResult = LoadResult.Failure(notUsed())

        override suspend fun resume(worldId: DefinitionId, runId: RunId): LoadResult = LoadResult.Failure(notUsed())

        override suspend fun perform(action: GameSessionAction): ActionResult = ActionResult.Failure(notUsed())

        override suspend fun execute(
            command: GameSessionCommand,
            authorization: CommandAuthorization,
        ): ActionResult = ActionResult.Failure(notUsed())

        override suspend fun commandContext(): SessionCommandContext? = if (loaded) {
            SessionCommandContext(
                runId = RunId("test.run"),
                modules = RegisteredWorldModules(emptyList()),
                adjustmentTargets = emptyList(),
                checkProfileIds = emptyList(),
            )
        } else {
            null
        }

        override suspend fun replay(): SessionReplayResult = SessionReplayResult.Failure(notUsed())

        private fun notUsed() = io.worldloom.application.SessionError(
            io.worldloom.application.SessionErrorCode.SESSION_NOT_LOADED,
            "Not used by this test",
        )
    }

    private class GatedStreamingProvider(
        private val release: CompletableDeferred<Unit>,
    ) : StreamingLanguageModelProvider {
        override val capabilities = ProviderCapabilities(toolCalling = false, streaming = true, structuredOutput = false)
        lateinit var request: ProviderRequest

        override suspend fun complete(request: ProviderRequest): ProviderResult = error("Streaming path expected")

        override suspend fun completeStreaming(
            request: ProviderRequest,
            onEvent: suspend (ProviderStreamEvent) -> Unit,
        ): ProviderResult {
            this.request = request
            onEvent(ProviderStreamEvent.TextDelta("织"))
            release.await()
            onEvent(ProviderStreamEvent.TextDelta("境回应"))
            return ProviderResult.Success(ProviderTurn("织境回应", usage = ProviderUsage(4, 3)))
        }
    }

    private data object UnusedProvider : StreamingLanguageModelProvider {
        override val capabilities = ProviderCapabilities(toolCalling = false, streaming = true, structuredOutput = false)

        override suspend fun complete(request: ProviderRequest): ProviderResult = error("Provider must not be called")

        override suspend fun completeStreaming(
            request: ProviderRequest,
            onEvent: suspend (ProviderStreamEvent) -> Unit,
        ): ProviderResult = error("Provider must not be called")
    }

    private class ReadOnlyNarrationProvider : io.worldloom.provider.api.LanguageModelProvider {
        override val capabilities = ProviderCapabilities(toolCalling = true, streaming = false, structuredOutput = false)
        lateinit var request: ProviderRequest

        override suspend fun complete(request: ProviderRequest): ProviderResult {
            this.request = request
            return ProviderResult.Success(ProviderTurn("你抵达了新的场景。", usage = ProviderUsage(5, 4)))
        }
    }

    private data object ImmediateNarrationProvider : io.worldloom.provider.api.LanguageModelProvider {
        override val capabilities = ProviderCapabilities(toolCalling = true, streaming = false, structuredOutput = false)

        override suspend fun complete(request: ProviderRequest): ProviderResult =
            ProviderResult.Success(ProviderTurn("周围暂时安静。", usage = ProviderUsage(4, 3)))
    }

    private data object EmptyToolGateway : AgentToolGateway {
        override suspend fun availableTools(identity: AgentIdentity) = emptyList<io.worldloom.provider.api.ProviderToolDefinition>()

        override suspend fun validate(identity: AgentIdentity, call: io.worldloom.provider.api.ProviderToolCall) =
            ToolValidationResult.Valid

        override suspend fun invoke(identity: AgentIdentity, call: io.worldloom.provider.api.ProviderToolCall) =
            ToolInvocationResult.Success("{}", false)
    }
}
