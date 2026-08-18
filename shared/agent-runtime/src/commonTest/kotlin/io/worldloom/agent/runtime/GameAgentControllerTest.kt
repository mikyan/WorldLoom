package io.worldloom.agent.runtime

import io.worldloom.application.ActionResult
import io.worldloom.application.GamePresentation
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
import io.worldloom.world.CommandPermission
import io.worldloom.world.RunId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        assertIs<AgentSessionLoadResult.Success>(
            store.load(
                AgentSessionId("narrator.test.run"),
                AgentIdentity(
                    AgentId("worldloom.agent.narrator"),
                    ActorId("worldloom.actor.narrator"),
                    setOf(CommandPermission.ADJUST_NUMERIC_COMPONENT, CommandPermission.RESOLVE_CHECK),
                ),
                RunId("test.run"),
            ),
        )
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

    private fun readyState(): GameSessionUiState.Ready = GameSessionUiState.Ready(
        GamePresentation(
            worldId = DefinitionId("test.world"),
            title = "测试世界",
            lastSequence = 0,
            fields = emptyList(),
            checks = emptyList(),
            timeline = emptyList(),
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

    private data object EmptyToolGateway : AgentToolGateway {
        override suspend fun availableTools(identity: AgentIdentity) = emptyList<io.worldloom.provider.api.ProviderToolDefinition>()

        override suspend fun validate(identity: AgentIdentity, call: io.worldloom.provider.api.ProviderToolCall) =
            ToolValidationResult.Valid

        override suspend fun invoke(identity: AgentIdentity, call: io.worldloom.provider.api.ProviderToolCall) =
            ToolInvocationResult.Success("{}", false)
    }
}
