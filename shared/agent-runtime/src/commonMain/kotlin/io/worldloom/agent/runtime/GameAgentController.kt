package io.worldloom.agent.runtime

import io.worldloom.application.GameSession
import io.worldloom.application.GameSessionUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

sealed interface GameAgentState {
    data object Idle : GameAgentState

    data class Running(val partialText: String) : GameAgentState

    data class Completed(val text: String) : GameAgentState

    data class AwaitingPlayer(val question: String) : GameAgentState

    data class Failed(
        val message: String,
        val worldChanged: Boolean = false,
    ) : GameAgentState
}

interface GameAgentController {
    val state: StateFlow<GameAgentState>

    suspend fun send(input: String)

    fun reset()
}

/** Bridges visible game projections to one private, Run-scoped GM session and the bounded Agent Runtime. */
class DefaultGameAgentController(
    private val runtime: AgentRuntime,
    private val gameSession: GameSession,
    private val turnStore: GameTurnStore = InMemoryGameTurnStore(),
) : GameAgentController {
    private val runMutex = Mutex()
    private val mutableState = MutableStateFlow<GameAgentState>(GameAgentState.Idle)
    private val orchestrator = GameTurnOrchestrator(runtime, gameSession, turnStore)

    override val state: StateFlow<GameAgentState> = mutableState.asStateFlow()

    override suspend fun send(input: String) {
        if (input.isBlank()) {
            mutableState.value = GameAgentState.Failed("请输入要执行的行动。")
            return
        }
        if (!runMutex.tryLock()) {
            return
        }
        try {
            val ready = gameSession.state.value as? GameSessionUiState.Ready
            val context = gameSession.commandContext()
            if (ready == null || context == null) {
                mutableState.value = GameAgentState.Failed("请先加载一个世界。")
                return
            }
            val partial = StringBuilder()
            mutableState.value = GameAgentState.Running("")
            val result = orchestrator.submit(
                turnId = turnStore.nextTurnId(context.runId),
                input = input,
            ) { delta ->
                partial.append(delta)
                mutableState.value = GameAgentState.Running(partial.toString())
            }
            mutableState.value = when (result) {
                is GmTurnResult.Completed -> GameAgentState.Completed(result.turn.output.orEmpty())
                is GmTurnResult.AwaitingPlayer -> GameAgentState.AwaitingPlayer(result.turn.output.orEmpty())
                is GmTurnResult.Cancelled -> GameAgentState.Idle
                is GmTurnResult.Failed -> GameAgentState.Failed(
                    message = result.turn.error ?: "主持回合失败",
                    worldChanged = result.turn.worldChanged,
                )
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = GameAgentState.Idle
            throw cancelled
        } finally {
            runMutex.unlock()
        }
    }

    override fun reset() {
        if (!runMutex.isLocked) mutableState.value = GameAgentState.Idle
    }

}
