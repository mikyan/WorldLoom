package io.worldloom.agent.runtime

import io.worldloom.application.GameSession
import io.worldloom.application.GameSessionUiState
import io.worldloom.definition.DefinitionId
import io.worldloom.provider.api.ProviderToolCall
import io.worldloom.world.ActorId
import io.worldloom.world.CommandPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.worldloom.world.RunId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface GameAgentState {
    data object Idle : GameAgentState

    data class Running(val partialText: String, val turnId: TurnId? = null) : GameAgentState

    data class Completed(val text: String, val turnId: TurnId? = null) : GameAgentState

    data class AwaitingPlayer(val question: String, val turnId: TurnId? = null) : GameAgentState

    data class Failed(
        val message: String,
        val worldChanged: Boolean = false,
        val turnId: TurnId? = null,
        val recoveryKind: GameTurnRecoveryKind = GameTurnRecoveryKind.NONE,
    ) : GameAgentState
}

data class GameAgentHistoryState(
    val runId: RunId? = null,
    val items: List<HostedTurnHistoryItem> = emptyList(),
    val issues: List<HostedTurnHistoryIssue> = emptyList(),
    val hasEarlier: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

sealed interface NpcDialogueResult {
    data class Committed(val worldChanged: Boolean) : NpcDialogueResult
    data class Failed(val message: String, val worldChanged: Boolean = false) : NpcDialogueResult
}

interface GameAgentController {
    val state: StateFlow<GameAgentState>
    val history: StateFlow<GameAgentHistoryState>

    suspend fun send(input: String)
    suspend fun refreshHistory()
    suspend fun loadEarlierHistory()
    suspend fun retry(turnId: TurnId)
    suspend fun recoverNarration(turnId: TurnId)
    suspend fun addressNpc(npcId: DefinitionId, content: String, idempotencyKey: String): NpcDialogueResult

    fun reset()
}

/** Bridges visible game projections to one private, Run-scoped GM session and the bounded Agent Runtime. */
class DefaultGameAgentController(
    private val runtime: AgentRuntime,
    private val gameSession: GameSession,
    private val turnStore: GameTurnStore = InMemoryGameTurnStore(),
    private val directToolGateway: AgentToolGateway? = null,
) : GameAgentController {
    private val runMutex = Mutex()
    private val historyMutex = Mutex()
    private val mutableState = MutableStateFlow<GameAgentState>(GameAgentState.Idle)
    private val mutableHistory = MutableStateFlow(GameAgentHistoryState())
    private val orchestrator = GameTurnOrchestrator(runtime, gameSession, turnStore)
    private val recoveryCoordinator = GameTurnRecoveryCoordinator(turnStore)
    private var oldestLoadedOrdinal: Long? = null

    override val state: StateFlow<GameAgentState> = mutableState.asStateFlow()
    override val history: StateFlow<GameAgentHistoryState> = mutableHistory.asStateFlow()

    override suspend fun send(input: String) = sendInternal(input, GameTurnRequestKind.PLAYER_ACTION, null)

    private suspend fun sendInternal(
        input: String,
        requestKind: GameTurnRequestKind,
        parentTurnId: TurnId?,
    ) {
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
            val turnId = turnStore.nextTurnId(context.runId)
            val partial = StringBuilder()
            mutableState.value = GameAgentState.Running("", turnId)
            val result = orchestrator.submit(
                turnId = turnId,
                input = input,
                onTextDelta = { delta ->
                    partial.append(delta)
                    mutableState.value = GameAgentState.Running(partial.toString(), turnId)
                },
                requestKind = requestKind,
                parentTurnId = parentTurnId,
            )
            mutableState.value = when (result) {
                is GmTurnResult.Completed -> GameAgentState.Completed(result.turn.output.orEmpty(), result.turn.turnId)
                is GmTurnResult.AwaitingPlayer -> GameAgentState.AwaitingPlayer(
                    result.turn.output.orEmpty(),
                    result.turn.turnId,
                )
                is GmTurnResult.Cancelled -> GameAgentState.Idle
                is GmTurnResult.Failed -> GameAgentState.Failed(
                    message = result.turn.errorCode?.let(::publicGameTurnFailureMessage) ?: "主持回合失败",
                    worldChanged = result.turn.worldChanged,
                    turnId = result.turn.turnId,
                    recoveryKind = result.turn.recoveryKind,
                )
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = GameAgentState.Idle
            throw cancelled
        } finally {
            runMutex.unlock()
            withContext(NonCancellable) { refreshHistory() }
        }
    }

    override suspend fun refreshHistory() = historyMutex.withLock {
        val context = gameSession.commandContext()
        val ready = gameSession.state.value as? GameSessionUiState.Ready
        if (context == null || ready == null) {
            oldestLoadedOrdinal = null
            mutableHistory.value = GameAgentHistoryState()
            return@withLock
        }
        mutableHistory.value = mutableHistory.value.copy(runId = context.runId, loading = true, error = null)
        when (val recovery = recoveryCoordinator.recover(context.runId, ready.presentation.lastSequence)) {
            is GameTurnRecoveryResult.Failure -> {
                mutableHistory.value = mutableHistory.value.copy(loading = false, error = recovery.message)
                return@withLock
            }
            is GameTurnRecoveryResult.Completed -> Unit
        }
        val raw = when (val result = turnStore.history(context.runId, limit = 50)) {
            is GameTurnHistoryResult.Success -> result.page
            is GameTurnHistoryResult.Failure -> {
                mutableHistory.value = mutableHistory.value.copy(loading = false, error = result.message)
                return@withLock
            }
        }
        oldestLoadedOrdinal = raw.entries.minOfOrNull(GameTurnHistoryEntry::ordinal)
        mutableHistory.value = when (
            val projection = HostedTurnHistoryProjector.project(
                GameTurnHistoryResult.Success(raw),
                ready.presentation.lastSequence,
            )
        ) {
            is HostedTurnHistoryResult.Success -> GameAgentHistoryState(
                runId = context.runId,
                items = projection.page.items,
                issues = projection.page.issues,
                hasEarlier = projection.page.hasEarlier,
            )
            is HostedTurnHistoryResult.Failure -> GameAgentHistoryState(
                runId = context.runId,
                error = projection.message,
            )
        }
    }

    override suspend fun loadEarlierHistory() = historyMutex.withLock {
        val context = gameSession.commandContext() ?: return@withLock
        val ready = gameSession.state.value as? GameSessionUiState.Ready ?: return@withLock
        val boundary = oldestLoadedOrdinal ?: return@withLock
        mutableHistory.value = mutableHistory.value.copy(loading = true, error = null)
        val raw = when (val result = turnStore.history(context.runId, boundary, 50)) {
            is GameTurnHistoryResult.Success -> result.page
            is GameTurnHistoryResult.Failure -> {
                mutableHistory.value = mutableHistory.value.copy(loading = false, error = result.message)
                return@withLock
            }
        }
        oldestLoadedOrdinal = raw.entries.minOfOrNull(GameTurnHistoryEntry::ordinal) ?: boundary
        when (
            val projection = HostedTurnHistoryProjector.project(
                GameTurnHistoryResult.Success(raw),
                ready.presentation.lastSequence,
            )
        ) {
            is HostedTurnHistoryResult.Success -> mutableHistory.value = mutableHistory.value.copy(
                items = (projection.page.items + mutableHistory.value.items).distinctBy { it.turnId },
                issues = (projection.page.issues + mutableHistory.value.issues)
                    .distinctBy { it.turnId to it.code },
                hasEarlier = projection.page.hasEarlier,
                loading = false,
            )
            is HostedTurnHistoryResult.Failure -> mutableHistory.value = mutableHistory.value.copy(
                loading = false,
                error = projection.message,
            )
        }
    }

    override suspend fun retry(turnId: TurnId) {
        val context = gameSession.commandContext()
        val source = context?.let { turnStore.load(it.runId, turnId) }
        if (source?.recoveryKind != GameTurnRecoveryKind.RETRY_SAFE) {
            mutableState.value = GameAgentState.Failed("该回合不能安全重试。", turnId = turnId)
            return
        }
        sendInternal(source.input, GameTurnRequestKind.RETRY, source.turnId)
    }

    override suspend fun recoverNarration(turnId: TurnId) {
        if (!runMutex.tryLock()) return
        try {
            val context = gameSession.commandContext()
            if (context == null) {
                mutableState.value = GameAgentState.Failed("请先加载一个世界。")
                return
            }
            val newTurnId = turnStore.nextTurnId(context.runId)
            val partial = StringBuilder()
            mutableState.value = GameAgentState.Running("", newTurnId)
            val result = orchestrator.recoverNarration(turnId, newTurnId) { delta ->
                partial.append(delta)
                mutableState.value = GameAgentState.Running(partial.toString(), newTurnId)
            }
            mutableState.value = when (result) {
                is GmTurnResult.Completed -> GameAgentState.Completed(result.turn.output.orEmpty(), result.turn.turnId)
                is GmTurnResult.AwaitingPlayer -> GameAgentState.AwaitingPlayer(
                    result.turn.output.orEmpty(),
                    result.turn.turnId,
                )
                is GmTurnResult.Cancelled -> GameAgentState.Idle
                is GmTurnResult.Failed -> GameAgentState.Failed(
                    result.turn.errorCode?.let(::publicGameTurnFailureMessage) ?: "补叙述失败。",
                    result.turn.worldChanged,
                    result.turn.turnId,
                    result.turn.recoveryKind,
                )
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = GameAgentState.Idle
            throw cancelled
        } finally {
            runMutex.unlock()
            withContext(NonCancellable) { refreshHistory() }
        }
    }

    override suspend fun addressNpc(
        npcId: DefinitionId,
        content: String,
        idempotencyKey: String,
    ): NpcDialogueResult {
        val gateway = directToolGateway ?: return NpcDialogueResult.Failed("NPC 对话入口尚未配置。")
        if (content.trim().length !in 1..500) return NpcDialogueResult.Failed("对话内容需为 1 到 500 个字符。")
        if (!runMutex.tryLock()) return NpcDialogueResult.Failed("主持人正在处理上一项操作。")
        return try {
            val result = gateway.invoke(
                AgentIdentity(
                    agentId = AgentId("worldloom.agent.player-dialogue"),
                    actorId = ActorId("system.player"),
                    permissions = setOf(CommandPermission.ADDRESS_NPC),
                ),
                ProviderToolCall(
                    id = idempotencyKey,
                    name = NPC_ADDRESS_TOOL_ID.value,
                    arguments = buildJsonObject {
                        put("npcId", npcId.value)
                        put("content", content)
                    },
                ),
            )
            when (result) {
                is ToolInvocationResult.Success -> NpcDialogueResult.Committed(result.worldChanged)
                is ToolInvocationResult.Failure -> NpcDialogueResult.Failed(
                    result.error.message,
                    result.worldChanged,
                )
            }
        } finally {
            runMutex.unlock()
        }
    }

    override fun reset() {
        if (!runMutex.isLocked) mutableState.value = GameAgentState.Idle
    }

}
