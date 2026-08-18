package io.worldloom.agent.runtime

import io.worldloom.application.GamePresentation
import io.worldloom.application.GameSession
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.SessionCommandContext
import io.worldloom.definition.DefinitionId
import io.worldloom.world.ActorId
import io.worldloom.world.CommandPermission
import io.worldloom.world.RunId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

const val CURRENT_GM_TURN_SCHEMA_VERSION: Int = 1
const val CURRENT_GM_PROFILE_SCHEMA_VERSION: Int = 1

@Serializable
@JvmInline
value class TurnId(val value: String) {
    init { require(value.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9._:-]*$"))) { "TurnId must be stable" } }
}

@Serializable
enum class GameTurnStatus { ACCEPTED, RUNNING, AWAITING_PLAYER, COMPLETED, CANCELLED, FAILED }

@Serializable
data class GameTurn(
    val schemaVersion: Int = CURRENT_GM_TURN_SCHEMA_VERSION,
    val runId: RunId,
    val turnId: TurnId,
    val input: String,
    val status: GameTurnStatus,
    val revision: Long,
    val acceptedSequence: Long,
    val deliveredSequence: Long? = null,
    val output: String? = null,
    val error: String? = null,
    val worldChanged: Boolean = false,
) {
    init {
        require(schemaVersion == CURRENT_GM_TURN_SCHEMA_VERSION) { "Unsupported GM turn schema" }
        require(revision >= 0) { "GM turn revision must not be negative" }
        require(acceptedSequence >= 0) { "Accepted sequence must not be negative" }
        require(deliveredSequence == null || deliveredSequence >= acceptedSequence) {
            "Delivered sequence cannot precede the accepted sequence"
        }
    }
}

sealed interface GameTurnStoreResult {
    data object Success : GameTurnStoreResult
    data object RevisionConflict : GameTurnStoreResult
    data class Failure(val message: String) : GameTurnStoreResult
}

interface GameTurnStore {
    /** Allocates a Run-local ID from durable turn history; an uncommitted allocation may be reused. */
    suspend fun nextTurnId(runId: RunId): TurnId
    suspend fun load(runId: RunId, turnId: TurnId): GameTurn?
    suspend fun save(turn: GameTurn, expectedRevision: Long?): GameTurnStoreResult
}

class InMemoryGameTurnStore : GameTurnStore {
    private val mutex = Mutex()
    private val turns = mutableMapOf<Pair<RunId, TurnId>, GameTurn>()

    override suspend fun nextTurnId(runId: RunId): TurnId = mutex.withLock {
        TurnId("${runId.value}.turn.${turns.keys.count { it.first == runId } + 1}")
    }

    override suspend fun load(runId: RunId, turnId: TurnId): GameTurn? = mutex.withLock {
        turns[runId to turnId]
    }

    override suspend fun save(turn: GameTurn, expectedRevision: Long?): GameTurnStoreResult = mutex.withLock {
        val key = turn.runId to turn.turnId
        val existing = turns[key]
        if (existing?.revision != expectedRevision) return@withLock GameTurnStoreResult.RevisionConflict
        turns[key] = turn
        GameTurnStoreResult.Success
    }
}

@Serializable
data class GmProfile(
    val schemaVersion: Int = CURRENT_GM_PROFILE_SCHEMA_VERSION,
    val id: DefinitionId = DefinitionId("worldloom.gm.default"),
    val clarificationPrefix: String = "CLARIFY:",
    val visibleEventLimit: Int = 12,
    val maxSteps: Int = 8,
    val maxToolCalls: Int = 8,
) {
    init {
        require(schemaVersion == CURRENT_GM_PROFILE_SCHEMA_VERSION)
        require(clarificationPrefix.isNotBlank())
        require(visibleEventLimit > 0 && maxSteps > 0 && maxToolCalls > 0)
    }
}

data class GmProjectedContext(
    val runId: RunId,
    val visibleSequence: Long,
    val systemPrompt: String,
)

object GmContextProjector {
    fun project(
        presentation: GamePresentation,
        context: SessionCommandContext,
        profile: GmProfile,
    ): GmProjectedContext = GmProjectedContext(
        runId = context.runId,
        visibleSequence = presentation.lastSequence,
        systemPrompt = buildString {
            appendLine("你是 Worldloom 的单人跑团主持人。只依据本提示中的玩家可见事实主持当前回合。")
            appendLine("客观变化必须调用当前提供的工具；工具结果和事件序列是权威事实。不得披露隐藏信息或虚构物品、状态、地点、关系与目标变化。")
            appendLine("如果缺少执行行动所必需的目标或选择，只回复 '${profile.clarificationPrefix} <具体问题>'，不要猜测。")
            appendLine("世界：${presentation.title} (${presentation.worldId.value})")
            appendLine("Run：${context.runId.value}；公开事件序列：${presentation.lastSequence}")
            appendLine("主持预算：最多 ${profile.maxSteps} 步、${profile.maxToolCalls} 次工具调用")
            presentation.scene?.let { scene ->
                appendLine("当前场景：${scene.label} (${scene.id.value})")
                if (scene.participantIds.isNotEmpty()) appendLine(
                    "公开参与者：${scene.participantIds.joinToString { it.value }}",
                )
                if (scene.actions.isNotEmpty()) {
                    appendLine("当前可用行动：")
                    scene.actions.forEach { appendLine("- ${it.label} (${it.id.value})") }
                }
            }
            if (presentation.fields.isNotEmpty()) {
                appendLine("玩家可见状态：")
                presentation.fields.forEach { appendLine("- ${it.label}: ${it.value}") }
            }
            if (presentation.completedObjectiveIds.isNotEmpty()) appendLine(
                "已完成目标：${presentation.completedObjectiveIds.sortedBy { it.value }.joinToString { it.value }}",
            )
            presentation.worldTimeMinutes?.let { appendLine("世界时间：第 $it 分钟") }
            if (presentation.activities.isNotEmpty()) {
                appendLine("当前可用活动：")
                presentation.activities.forEach { appendLine("- ${it.label} (${it.id.value})，耗时 ${it.durationMinutes} 分钟") }
            }
            if (presentation.travelRoutes.isNotEmpty()) {
                appendLine("当前可用旅行：")
                presentation.travelRoutes.forEach {
                    appendLine("- ${it.label} (${it.id.value}) → ${it.destinationSceneId.value}，耗时 ${it.durationMinutes} 分钟")
                }
            }
            presentation.adventureState?.let { adventure ->
                if (adventure.inventory.isNotEmpty()) appendLine(
                    "公开库存：${adventure.inventory.joinToString { "${it.label}×${it.quantity}" }}",
                )
                if (adventure.conditions.isNotEmpty()) appendLine(
                    "公开状态：${adventure.conditions.joinToString { "${it.label}(${it.stacks})" }}",
                )
                if (adventure.relationships.isNotEmpty()) appendLine(
                    "玩家可见关系：${adventure.relationships.joinToString { "${it.label}:${it.value}" }}",
                )
                if (adventure.quests.isNotEmpty()) appendLine(
                    "任务：${adventure.quests.joinToString { "${it.label}:${it.status.name}" }}",
                )
                if (adventure.clocks.isNotEmpty()) appendLine(
                    "进度钟：${adventure.clocks.joinToString { "${it.label}:${it.value}/${it.segments}" }}",
                )
            }
            if (presentation.timeline.isNotEmpty()) {
                appendLine("最近公开事件：")
                presentation.timeline.takeLast(profile.visibleEventLimit).forEach {
                    appendLine("- #${it.sequence} ${it.summary}")
                }
            }
        }.trim(),
    )
}

sealed interface GmTurnResult {
    data class Completed(val turn: GameTurn) : GmTurnResult
    data class AwaitingPlayer(val turn: GameTurn) : GmTurnResult
    data class Cancelled(val turn: GameTurn) : GmTurnResult
    data class Failed(val turn: GameTurn) : GmTurnResult
}

class GameTurnOrchestrator(
    private val runtime: AgentRuntime,
    private val gameSession: GameSession,
    private val turnStore: GameTurnStore = InMemoryGameTurnStore(),
    private val profile: GmProfile = GmProfile(),
) {
    private val mutex = Mutex()

    suspend fun submit(
        turnId: TurnId,
        input: String,
        onTextDelta: suspend (String) -> Unit = {},
    ): GmTurnResult = mutex.withLock {
        val context = gameSession.commandContext()
            ?: return@withLock failedWithoutRun(turnId, input, "Run command context is unavailable")
        val normalized = input.trim()
        turnStore.load(context.runId, turnId)?.let { existing ->
            if (existing.input != normalized) {
                return@withLock GmTurnResult.Failed(existing.copy(error = "TurnId was reused with different input"))
            }
            return@withLock recoverOrReturn(existing, context.lastSequence)
        }
        val ready = gameSession.state.value as? GameSessionUiState.Ready
            ?: return@withLock failedWithoutRun(turnId, input, "Run is not active")
        val accepted = GameTurn(
            runId = context.runId,
            turnId = turnId,
            input = normalized,
            status = GameTurnStatus.ACCEPTED,
            revision = 0,
            acceptedSequence = ready.presentation.lastSequence,
        )
        if (normalized.isBlank()) return@withLock persistNewTerminal(
            accepted,
            GameTurnStatus.AWAITING_PLAYER,
            output = "请描述你想做什么。",
        )
        if (turnStore.save(accepted, null) !is GameTurnStoreResult.Success) {
            return@withLock GmTurnResult.Failed(accepted.copy(status = GameTurnStatus.FAILED, error = "Turn could not be stored"))
        }
        val running = accepted.copy(status = GameTurnStatus.RUNNING, revision = 1)
        if (turnStore.save(running, 0) !is GameTurnStoreResult.Success) {
            return@withLock GmTurnResult.Failed(running.copy(status = GameTurnStatus.FAILED, error = "Turn could not start"))
        }
        val projected = GmContextProjector.project(ready.presentation, context, profile)
        val result = try {
            runtime.run(
                AgentRunRequest(
                    sessionId = AgentSessionId("worldloom.gm.${context.runId.value}"),
                    identity = identityFor(context),
                    input = normalized,
                    systemPrompt = projected.systemPrompt,
                    runId = context.runId,
                ),
                onTextDelta,
            )
        } catch (cancelled: CancellationException) {
            persistTerminal(running, GameTurnStatus.CANCELLED, error = "Turn was cancelled")
            throw cancelled
        }
        val deliveredSequence = gameSession.commandContext()?.lastSequence ?: projected.visibleSequence
        when (result) {
            is AgentRunResult.Completed -> {
                val text = result.text.trim()
                if (!result.worldChanged && text.startsWith(profile.clarificationPrefix)) {
                    persistTerminal(
                        running,
                        GameTurnStatus.AWAITING_PLAYER,
                        output = text.removePrefix(profile.clarificationPrefix).trim(),
                        deliveredSequence = deliveredSequence,
                    )
                } else {
                    persistTerminal(
                        running,
                        GameTurnStatus.COMPLETED,
                        output = text,
                        worldChanged = result.worldChanged,
                        deliveredSequence = deliveredSequence,
                    )
                }
            }
            is AgentRunResult.Failure -> persistTerminal(
                running,
                GameTurnStatus.FAILED,
                error = result.error.message,
                worldChanged = result.error.worldChanged,
                deliveredSequence = deliveredSequence,
            )
        }
    }

    private fun identityFor(context: SessionCommandContext): AgentIdentity {
        val permissions = buildSet {
            if (context.adjustmentTargets.isNotEmpty()) add(CommandPermission.ADJUST_NUMERIC_COMPONENT)
            if (context.checkProfileIds.isNotEmpty()) add(CommandPermission.RESOLVE_CHECK)
            if (context.availableActions.isNotEmpty()) {
                add(CommandPermission.APPLY_ACTION_OUTCOME)
                if (context.availableActions.any { it.requiresCheck }) add(CommandPermission.RESOLVE_CHECK)
            }
            if (context.worldTimeMinutes != null) add(CommandPermission.ADVANCE_WORLD_TIME)
            if (context.availableActivities.isNotEmpty()) {
                add(CommandPermission.PERFORM_ACTIVITY)
                if (context.availableActivities.any { it.requiresCheck }) add(CommandPermission.RESOLVE_CHECK)
            }
            if (context.availableTravelRoutes.isNotEmpty()) {
                add(CommandPermission.TRAVEL)
                if (context.availableTravelRoutes.any { it.requiresCheck }) add(CommandPermission.RESOLVE_CHECK)
            }
            context.adventureStateDefinition?.let { adventure ->
                if (adventure.inventory != null) add(CommandPermission.MANAGE_INVENTORY)
                if (adventure.conditions.isNotEmpty()) add(CommandPermission.UPDATE_CONDITION)
                if (adventure.relationships.isNotEmpty()) add(CommandPermission.UPDATE_RELATIONSHIP)
                if (adventure.quests.isNotEmpty()) add(CommandPermission.UPDATE_QUEST)
                if (adventure.clocks.isNotEmpty()) add(CommandPermission.ADVANCE_PROGRESS_CLOCK)
            }
        }
        return AgentIdentity(AgentId("worldloom.agent.gm"), ActorId("worldloom.actor.gm"), permissions)
    }

    private suspend fun persistNewTerminal(
        base: GameTurn,
        status: GameTurnStatus,
        output: String? = null,
    ): GmTurnResult {
        val turn = base.copy(
            status = status,
            revision = 0,
            output = output,
            deliveredSequence = base.acceptedSequence,
        )
        return when (turnStore.save(turn, null)) {
            GameTurnStoreResult.Success -> turn.toResult()
            else -> GmTurnResult.Failed(
                turn.copy(status = GameTurnStatus.FAILED, error = "Turn result could not be stored"),
            )
        }
    }

    private suspend fun persistTerminal(
        base: GameTurn,
        status: GameTurnStatus,
        output: String? = null,
        error: String? = null,
        worldChanged: Boolean = false,
        deliveredSequence: Long? = null,
    ): GmTurnResult {
        val turn = base.copy(
            status = status,
            revision = base.revision + 1,
            output = output,
            error = error,
            worldChanged = worldChanged,
            deliveredSequence = deliveredSequence,
        )
        return when (turnStore.save(turn, base.revision)) {
            GameTurnStoreResult.Success -> turn.toResult()
            else -> GmTurnResult.Failed(
                turn.copy(status = GameTurnStatus.FAILED, error = "Turn result could not be stored"),
            )
        }
    }

    /**
     * An accepted/running row means the process stopped before publishing a terminal result. Facts
     * already committed by tools remain authoritative; retry closes the row without invoking the
     * model or tools a second time.
     */
    private suspend fun recoverOrReturn(existing: GameTurn, visibleSequence: Long): GmTurnResult {
        if (existing.status !in setOf(GameTurnStatus.ACCEPTED, GameTurnStatus.RUNNING)) {
            return existing.toResult()
        }
        val recovered = existing.copy(
            status = GameTurnStatus.FAILED,
            revision = existing.revision + 1,
            deliveredSequence = visibleSequence,
            worldChanged = visibleSequence > existing.acceptedSequence,
            error = if (visibleSequence > existing.acceptedSequence) {
                "Turn was interrupted after authoritative facts were committed; retry with a new TurnId"
            } else {
                "Turn was interrupted before authoritative facts were committed; retry with a new TurnId"
            },
        )
        return when (turnStore.save(recovered, existing.revision)) {
            GameTurnStoreResult.Success -> GmTurnResult.Failed(recovered)
            else -> GmTurnResult.Failed(existing.copy(error = "Interrupted turn could not be recovered"))
        }
    }

    private fun failedWithoutRun(turnId: TurnId, input: String, message: String): GmTurnResult.Failed =
        GmTurnResult.Failed(
            GameTurn(
                runId = RunId("unavailable.run"),
                turnId = turnId,
                input = input,
                status = GameTurnStatus.FAILED,
                revision = 0,
                acceptedSequence = 0,
                error = message,
            ),
        )

    private fun GameTurn.toResult(): GmTurnResult = when (status) {
        GameTurnStatus.AWAITING_PLAYER -> GmTurnResult.AwaitingPlayer(this)
        GameTurnStatus.COMPLETED -> GmTurnResult.Completed(this)
        GameTurnStatus.CANCELLED -> GmTurnResult.Cancelled(this)
        GameTurnStatus.ACCEPTED, GameTurnStatus.RUNNING, GameTurnStatus.FAILED -> GmTurnResult.Failed(this)
    }
}
