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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

const val LEGACY_GM_TURN_SCHEMA_VERSION: Int = 1
const val CURRENT_GM_TURN_SCHEMA_VERSION: Int = 3
const val CURRENT_GM_PROFILE_SCHEMA_VERSION: Int = 1

@Serializable
@JvmInline
value class TurnId(val value: String) {
    init { require(value.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9._:-]*$"))) { "TurnId must be stable" } }
}

@Serializable
enum class GameTurnStatus { ACCEPTED, RUNNING, AWAITING_PLAYER, COMPLETED, CANCELLED, FAILED }

@Serializable
enum class GameTurnOutputKind { NONE, NARRATION, CLARIFICATION, FAILURE }

@Serializable
enum class GameTurnRecoveryKind { NONE, RETRY_SAFE, NARRATION_REQUIRED }

@Serializable
enum class GameTurnRequestKind { PLAYER_ACTION, RETRY, NARRATION_RECOVERY }

@Serializable
enum class GameTurnErrorCode {
    INVALID_REQUEST,
    PROVIDER_FAILURE,
    TOOL_FAILURE,
    STORAGE_FAILURE,
    CANCELLED,
    INTERRUPTED,
    UNKNOWN,
}

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
    val outputKind: GameTurnOutputKind = GameTurnOutputKind.NONE,
    val evidenceFromSequenceExclusive: Long? = null,
    val evidenceThroughSequenceInclusive: Long? = null,
    val recoveryKind: GameTurnRecoveryKind = GameTurnRecoveryKind.NONE,
    val errorCode: GameTurnErrorCode? = null,
    val requestKind: GameTurnRequestKind = GameTurnRequestKind.PLAYER_ACTION,
    val parentTurnId: TurnId? = null,
) {
    init {
        require(schemaVersion in LEGACY_GM_TURN_SCHEMA_VERSION..CURRENT_GM_TURN_SCHEMA_VERSION) {
            "Unsupported GM turn schema"
        }
        require(revision >= 0) { "GM turn revision must not be negative" }
        require(acceptedSequence >= 0) { "Accepted sequence must not be negative" }
        require(deliveredSequence == null || deliveredSequence >= acceptedSequence) {
            "Delivered sequence cannot precede the accepted sequence"
        }
        require((evidenceFromSequenceExclusive == null) == (evidenceThroughSequenceInclusive == null)) {
            "GM turn evidence range must have both boundaries"
        }
        if (evidenceFromSequenceExclusive != null && evidenceThroughSequenceInclusive != null) {
            require(evidenceFromSequenceExclusive >= acceptedSequence) {
                "GM turn evidence cannot start before the accepted sequence"
            }
            require(evidenceThroughSequenceInclusive > evidenceFromSequenceExclusive) {
                "GM turn evidence range must contain at least one event"
            }
            require(deliveredSequence == null || evidenceThroughSequenceInclusive <= deliveredSequence) {
                "GM turn evidence cannot exceed its delivered sequence"
            }
        }
        require(
            requestKind == GameTurnRequestKind.PLAYER_ACTION ||
                parentTurnId != null ||
                status == GameTurnStatus.FAILED,
        ) {
            "Retry and narration-recovery turns must reference their source turn"
        }
    }

    fun toCurrentSchema(): GameTurn {
        if (schemaVersion == CURRENT_GM_TURN_SCHEMA_VERSION) return this
        val migratedOutputKind = when {
            status == GameTurnStatus.AWAITING_PLAYER -> GameTurnOutputKind.CLARIFICATION
            status == GameTurnStatus.COMPLETED && !output.isNullOrBlank() -> GameTurnOutputKind.NARRATION
            status in setOf(GameTurnStatus.CANCELLED, GameTurnStatus.FAILED) -> GameTurnOutputKind.FAILURE
            else -> GameTurnOutputKind.NONE
        }
        val migratedRecoveryKind = when {
            status != GameTurnStatus.FAILED || !error.orEmpty().startsWith("Turn was interrupted") ->
                GameTurnRecoveryKind.NONE
            worldChanged -> GameTurnRecoveryKind.NARRATION_REQUIRED
            else -> GameTurnRecoveryKind.RETRY_SAFE
        }
        return copy(
            schemaVersion = CURRENT_GM_TURN_SCHEMA_VERSION,
            outputKind = migratedOutputKind,
            evidenceFromSequenceExclusive = acceptedSequence.takeIf {
                deliveredSequence != null && deliveredSequence > acceptedSequence
            },
            evidenceThroughSequenceInclusive = deliveredSequence?.takeIf { it > acceptedSequence },
            recoveryKind = migratedRecoveryKind,
            errorCode = when {
                status == GameTurnStatus.CANCELLED -> GameTurnErrorCode.CANCELLED
                status == GameTurnStatus.FAILED -> GameTurnErrorCode.UNKNOWN
                else -> null
            },
        )
    }
}

enum class GameTurnHistoryProblemCode { INVALID_SCHEMA, INVALID_JSON, IDENTITY_MISMATCH, FUTURE_EVIDENCE }

data class GameTurnHistoryProblem(
    val turnId: TurnId,
    val code: GameTurnHistoryProblemCode,
    val message: String,
)

data class GameTurnHistoryEntry(
    val ordinal: Long,
    val turn: GameTurn? = null,
    val problem: GameTurnHistoryProblem? = null,
) {
    init { require((turn == null) != (problem == null)) { "History entry must contain a turn or a problem" } }
}

data class GameTurnHistoryPage(
    val entries: List<GameTurnHistoryEntry>,
    val hasEarlier: Boolean,
)

sealed interface GameTurnHistoryResult {
    data class Success(val page: GameTurnHistoryPage) : GameTurnHistoryResult
    data class Failure(val message: String) : GameTurnHistoryResult
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
    suspend fun history(
        runId: RunId,
        beforeOrdinalExclusive: Long? = null,
        limit: Int = 50,
    ): GameTurnHistoryResult

    suspend fun latest(runId: RunId): GameTurn? = when (val result = history(runId, limit = 1)) {
        is GameTurnHistoryResult.Success -> result.page.entries.singleOrNull()?.turn
        is GameTurnHistoryResult.Failure -> null
    }
}

class InMemoryGameTurnStore : GameTurnStore {
    private val mutex = Mutex()
    private val turns = mutableMapOf<Pair<RunId, TurnId>, GameTurn>()
    private val ordinals = mutableMapOf<Pair<RunId, TurnId>, Long>()

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
        turns[key] = turn.toCurrentSchema()
        ordinals.getOrPut(key) { ordinals.filterKeys { it.first == turn.runId }.values.maxOrNull()?.plus(1) ?: 1 }
        GameTurnStoreResult.Success
    }

    override suspend fun history(
        runId: RunId,
        beforeOrdinalExclusive: Long?,
        limit: Int,
    ): GameTurnHistoryResult = mutex.withLock {
        if (limit !in 1..200) return@withLock GameTurnHistoryResult.Failure("History limit must be within 1..200")
        val boundary = beforeOrdinalExclusive ?: Long.MAX_VALUE
        val eligible = turns.mapNotNull { (key, turn) ->
            if (key.first != runId) return@mapNotNull null
            val ordinal = ordinals.getValue(key)
            if (ordinal >= boundary) return@mapNotNull null
            GameTurnHistoryEntry(ordinal, turn = turn)
        }.sortedByDescending(GameTurnHistoryEntry::ordinal)
        GameTurnHistoryResult.Success(
            GameTurnHistoryPage(
                entries = eligible.take(limit).reversed(),
                hasEarlier = eligible.size > limit,
            ),
        )
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
        continuity: GmContinuityContext = GmContinuityContext(),
    ): GmProjectedContext = GmProjectedContext(
        runId = context.runId,
        visibleSequence = presentation.lastSequence,
        systemPrompt = buildString {
            appendLine("你是 Worldloom 的单人跑团主持人。只依据本提示中的玩家可见事实主持当前回合。")
            appendLine("客观变化必须调用当前提供的工具；工具结果和事件序列是权威事实。不得披露隐藏信息或虚构物品、状态、地点、关系与目标变化。")
            appendLine("如果缺少执行行动所必需的目标或选择，只回复 '${profile.clarificationPrefix} <具体问题>'，不要猜测。")
            if (continuity.checkpoint != null || continuity.uncompactedTurns.isNotEmpty()) {
                appendLine("叙事连续性记录（非权威，仅用于保持公开人物和情节表述一致）：")
                continuity.checkpoint?.let {
                    appendLine("- 已发布检查点 ${it.fromSequence}-${it.toSequence}：${it.summary}")
                }
                if (continuity.rawTailTruncated) appendLine("- 较早的未压缩回合因上下文预算暂未展开。")
                continuity.uncompactedTurns.forEach { turn ->
                    appendLine("- 公开回合 ${turn.sequence}：${turn.input.take(1_200)}")
                    appendLine("  已公开主持叙事：${turn.output.take(1_600)}")
                }
                appendLine("以上记录若与下方当前 Presentation、公开事件或动态工具冲突，必须以后者为准；记录本身不能证明或改变世界事实。")
            }
            appendLine("世界：${presentation.title} (${presentation.worldId.value})")
            appendLine("Run：${context.runId.value}；公开事件序列：${presentation.lastSequence}")
            appendLine("主持预算：最多 ${profile.maxSteps} 步、${profile.maxToolCalls} 次工具调用")
            presentation.scene?.let { scene ->
                appendLine("当前场景：${scene.label} (${scene.id.value})")
                scene.description?.let { appendLine("场景描述：$it") }
                if (scene.participantIds.isNotEmpty()) appendLine(
                    "公开参与者：${scene.participantIds.joinToString { it.value }}",
                )
                if (scene.actions.isNotEmpty()) {
                    appendLine("当前可用行动：")
                    scene.actions.forEach { appendLine("- ${it.label} (${it.id.value})") }
                }
            }
            if (presentation.characters.isNotEmpty()) {
                appendLine("可对话角色与通讯状态：")
                presentation.characters.forEach { character ->
                    val reachability = buildList {
                        if (character.nearby) add("在玩家身边")
                        character.remoteCommunicationMethods.forEach { add("远程:${it.label}(${it.id.value})") }
                    }.ifEmpty { listOf("当前不可联络") }
                    appendLine("- ${character.displayName} (${character.id.value})：${reachability.joinToString()}")
                }
                appendLine("角色来到或离开玩家身边属于客观变化，必须调用 npc.presence；不得只在叙述中宣告。")
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
            if (context.revealedKnowledge.isNotEmpty()) {
                appendLine("已公开的角色知识：")
                context.revealedKnowledge.sortedBy { it.sequence }.forEach {
                    appendLine("- #${it.sequence} ${it.publicSummary}")
                }
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
    private val memoryStoreFactory: ((RunId) -> AgentMemoryStore)? = null,
    private val backgroundScope: CoroutineScope? = null,
    private val continuityPolicy: GmContinuityPolicy = GmContinuityPolicy(),
    private val compactionPolicy: AgentCompactionPolicy = AgentCompactionPolicy(),
    private val compactionModel: ContextCompactionModel = GmPublicContinuityCompactionModel(),
) {
    private val mutex = Mutex()
    private val fallbackMemoryStores = mutableMapOf<RunId, AgentMemoryStore>()
    private val continuityCoordinators = mutableMapOf<RunId, GmContinuityCoordinator>()

    suspend fun submit(
        turnId: TurnId,
        input: String,
        onTextDelta: suspend (String) -> Unit = {},
        requestKind: GameTurnRequestKind = GameTurnRequestKind.PLAYER_ACTION,
        parentTurnId: TurnId? = null,
    ): GmTurnResult = mutex.withLock {
        val context = gameSession.commandContext()
            ?: return@withLock failedWithoutRun(turnId, input, "Run command context is unavailable")
        val normalized = input.trim()
        if (requestKind == GameTurnRequestKind.NARRATION_RECOVERY) {
            return@withLock invalidRequest(
                context.runId,
                turnId,
                normalized,
                requestKind,
                parentTurnId,
                "Narration recovery must use recoverNarration",
            )
        }
        if (requestKind == GameTurnRequestKind.PLAYER_ACTION && parentTurnId != null) {
            return@withLock invalidRequest(
                context.runId,
                turnId,
                normalized,
                requestKind,
                parentTurnId,
                "A new player action cannot reference a previous turn",
            )
        }
        if (requestKind == GameTurnRequestKind.RETRY) {
            val parent = parentTurnId?.let { turnStore.load(context.runId, it) }
            if (
                parent == null ||
                parent.recoveryKind != GameTurnRecoveryKind.RETRY_SAFE ||
                parent.input != normalized
            ) {
                return@withLock invalidRequest(
                    context.runId,
                    turnId,
                    normalized,
                    requestKind,
                    parentTurnId,
                    "Only an unchanged input from a retry-safe turn can be retried",
                )
            }
        }
        turnStore.load(context.runId, turnId)?.let { existing ->
            if (existing.input != normalized) {
                return@withLock GmTurnResult.Failed(
                    existing.copy(
                        error = "TurnId was reused with different input",
                        outputKind = GameTurnOutputKind.FAILURE,
                        errorCode = GameTurnErrorCode.INVALID_REQUEST,
                    ),
                )
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
            requestKind = requestKind,
            parentTurnId = parentTurnId,
        )
        if (normalized.isBlank()) return@withLock persistNewTerminal(
            accepted,
            GameTurnStatus.AWAITING_PLAYER,
            output = "请描述你想做什么。",
        )
        if (turnStore.save(accepted, null) !is GameTurnStoreResult.Success) {
            return@withLock GmTurnResult.Failed(
                accepted.copy(
                    status = GameTurnStatus.FAILED,
                    error = "Turn could not be stored",
                    outputKind = GameTurnOutputKind.FAILURE,
                    errorCode = GameTurnErrorCode.STORAGE_FAILURE,
                ),
            )
        }
        val running = accepted.copy(status = GameTurnStatus.RUNNING, revision = 1)
        if (turnStore.save(running, 0) !is GameTurnStoreResult.Success) {
            return@withLock GmTurnResult.Failed(
                running.copy(
                    status = GameTurnStatus.FAILED,
                    error = "Turn could not start",
                    outputKind = GameTurnOutputKind.FAILURE,
                    errorCode = GameTurnErrorCode.STORAGE_FAILURE,
                ),
            )
        }
        val continuity = continuityFor(context.runId).prepare(ready.presentation.lastSequence)
        val projected = GmContextProjector.project(ready.presentation, context, profile, continuity)
        val result = try {
            runtime.run(
                AgentRunRequest(
                    sessionId = gmSessionId(context.runId),
                    identity = identityFor(context),
                    input = normalized,
                    systemPrompt = projected.systemPrompt,
                    runId = context.runId,
                ),
                onTextDelta,
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val cancelledSequence = gameSession.commandContext()?.lastSequence ?: running.acceptedSequence
                val factsCommitted = cancelledSequence > running.acceptedSequence
                persistTerminal(
                    running,
                    GameTurnStatus.CANCELLED,
                    error = "Turn was cancelled",
                    worldChanged = factsCommitted,
                    deliveredSequence = cancelledSequence,
                    errorCode = GameTurnErrorCode.CANCELLED,
                    recoveryKind = if (factsCommitted) {
                        GameTurnRecoveryKind.NARRATION_REQUIRED
                    } else {
                        GameTurnRecoveryKind.RETRY_SAFE
                    },
                )
            }
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
                        outputKind = GameTurnOutputKind.CLARIFICATION,
                    )
                } else {
                    persistTerminal(
                        running,
                        GameTurnStatus.COMPLETED,
                        output = text,
                        worldChanged = result.worldChanged,
                        deliveredSequence = deliveredSequence,
                        outputKind = GameTurnOutputKind.NARRATION,
                    )
                }
            }
            is AgentRunResult.Failure -> persistTerminal(
                running,
                GameTurnStatus.FAILED,
                error = result.error.message,
                worldChanged = result.error.worldChanged,
                deliveredSequence = deliveredSequence,
                errorCode = result.error.code.toGameTurnErrorCode(),
                recoveryKind = if (result.error.worldChanged || deliveredSequence > running.acceptedSequence) {
                    GameTurnRecoveryKind.NARRATION_REQUIRED
                } else {
                    GameTurnRecoveryKind.RETRY_SAFE
                },
            )
        }
    }

    suspend fun recoverNarration(
        sourceTurnId: TurnId,
        newTurnId: TurnId,
        onTextDelta: suspend (String) -> Unit = {},
    ): GmTurnResult = mutex.withLock {
        val context = gameSession.commandContext()
            ?: return@withLock failedWithoutRun(newTurnId, "", "Run command context is unavailable")
        val ready = gameSession.state.value as? GameSessionUiState.Ready
            ?: return@withLock invalidRequest(
                context.runId,
                newTurnId,
                "",
                GameTurnRequestKind.NARRATION_RECOVERY,
                sourceTurnId,
                "Run is not active",
            )
        val source = turnStore.load(context.runId, sourceTurnId)
        val through = source?.evidenceThroughSequenceInclusive
        if (
            source == null ||
            source.recoveryKind != GameTurnRecoveryKind.NARRATION_REQUIRED ||
            source.evidenceFromSequenceExclusive == null ||
            through == null ||
            through > ready.presentation.lastSequence
        ) {
            return@withLock invalidRequest(
                context.runId,
                newTurnId,
                source?.input.orEmpty(),
                GameTurnRequestKind.NARRATION_RECOVERY,
                sourceTurnId,
                "Turn does not have a valid narration-recovery evidence range",
            )
        }
        turnStore.load(context.runId, newTurnId)?.let { existing ->
            if (
                existing.requestKind != GameTurnRequestKind.NARRATION_RECOVERY ||
                existing.parentTurnId != sourceTurnId
            ) {
                return@withLock GmTurnResult.Failed(
                    existing.copy(
                        error = "TurnId was reused for a different narration recovery",
                        outputKind = GameTurnOutputKind.FAILURE,
                        errorCode = GameTurnErrorCode.INVALID_REQUEST,
                    ),
                )
            }
            return@withLock recoverOrReturn(existing, context.lastSequence)
        }
        val visibleEvidence = ready.presentation.timeline.filter {
            it.sequence > source.evidenceFromSequenceExclusive && it.sequence <= through
        }
        if (visibleEvidence.isEmpty()) {
            return@withLock invalidRequest(
                context.runId,
                newTurnId,
                source.input,
                GameTurnRequestKind.NARRATION_RECOVERY,
                sourceTurnId,
                "No public evidence is available for narration recovery",
            )
        }
        val accepted = GameTurn(
            runId = context.runId,
            turnId = newTurnId,
            input = source.input,
            status = GameTurnStatus.ACCEPTED,
            revision = 0,
            acceptedSequence = source.acceptedSequence,
            requestKind = GameTurnRequestKind.NARRATION_RECOVERY,
            parentTurnId = sourceTurnId,
        )
        if (turnStore.save(accepted, null) !is GameTurnStoreResult.Success) {
            return@withLock GmTurnResult.Failed(
                accepted.copy(
                    status = GameTurnStatus.FAILED,
                    outputKind = GameTurnOutputKind.FAILURE,
                    error = "Narration recovery could not be stored",
                    errorCode = GameTurnErrorCode.STORAGE_FAILURE,
                ),
            )
        }
        val running = accepted.copy(status = GameTurnStatus.RUNNING, revision = 1)
        if (turnStore.save(running, 0) !is GameTurnStoreResult.Success) {
            return@withLock GmTurnResult.Failed(
                running.copy(
                    status = GameTurnStatus.FAILED,
                    outputKind = GameTurnOutputKind.FAILURE,
                    error = "Narration recovery could not start",
                    errorCode = GameTurnErrorCode.STORAGE_FAILURE,
                ),
            )
        }
        val continuity = continuityFor(context.runId).prepare(ready.presentation.lastSequence)
        val projected = GmContextProjector.project(ready.presentation, context, profile, continuity)
        val recoveryPrompt = buildString {
            appendLine(projected.systemPrompt)
            appendLine()
            appendLine("这是只读补叙述回合。不得调用工具、不得改变事实，只能根据下列已提交的玩家可见事件补写简洁叙述。")
            appendLine("原玩家输入：${source.input}")
            appendLine("证据范围：(${source.evidenceFromSequenceExclusive}, $through]")
            visibleEvidence.forEach { appendLine("- #${it.sequence} ${it.summary}") }
        }.trim()
        val result = try {
            runtime.run(
                AgentRunRequest(
                    sessionId = AgentSessionId("worldloom.gm.recovery.${context.runId.value}"),
                    identity = AgentIdentity(
                        GM_AGENT_ID,
                        ActorId("worldloom.actor.gm"),
                        emptySet(),
                    ),
                    input = "补写已提交事实的玩家可见叙述。",
                    systemPrompt = recoveryPrompt,
                    runId = context.runId,
                ),
                onTextDelta,
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                persistTerminal(
                    running,
                    GameTurnStatus.CANCELLED,
                    error = "Narration recovery was cancelled",
                    errorCode = GameTurnErrorCode.CANCELLED,
                )
            }
            throw cancelled
        }
        when (result) {
            is AgentRunResult.Completed -> {
                val text = result.text.trim()
                if (result.worldChanged || text.isBlank()) {
                    persistTerminal(
                        running,
                        GameTurnStatus.FAILED,
                        error = "Narration recovery returned an invalid result",
                        deliveredSequence = through,
                        errorCode = GameTurnErrorCode.TOOL_FAILURE,
                    )
                } else {
                    persistTerminal(
                        running,
                        GameTurnStatus.COMPLETED,
                        output = text,
                        deliveredSequence = through,
                        outputKind = GameTurnOutputKind.NARRATION,
                    )
                }
            }
            is AgentRunResult.Failure -> persistTerminal(
                running,
                GameTurnStatus.FAILED,
                error = result.error.message,
                deliveredSequence = through,
                errorCode = result.error.code.toGameTurnErrorCode(),
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
            if (context.npcProfiles.any { it.canSpeak &&
                    (it.entityId in context.currentSceneParticipantIds || it.remoteCommunicationMethodIds.isNotEmpty())
                }
            ) {
                add(CommandPermission.ADDRESS_NPC)
            }
            if (context.npcProfiles.isNotEmpty() && context.currentSceneId != null) {
                add(CommandPermission.MANAGE_NPC_PRESENCE)
            }
            context.adventureStateDefinition?.let { adventure ->
                if (adventure.inventory != null) add(CommandPermission.MANAGE_INVENTORY)
                if (adventure.conditions.isNotEmpty()) add(CommandPermission.UPDATE_CONDITION)
                if (adventure.relationships.isNotEmpty()) add(CommandPermission.UPDATE_RELATIONSHIP)
                if (adventure.quests.isNotEmpty()) add(CommandPermission.UPDATE_QUEST)
                if (adventure.clocks.isNotEmpty()) add(CommandPermission.ADVANCE_PROGRESS_CLOCK)
            }
        }
        return AgentIdentity(GM_AGENT_ID, ActorId("worldloom.actor.gm"), permissions)
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
            outputKind = if (status == GameTurnStatus.AWAITING_PLAYER) {
                GameTurnOutputKind.CLARIFICATION
            } else {
                GameTurnOutputKind.NONE
            },
        )
        return when (turnStore.save(turn, null)) {
            GameTurnStoreResult.Success -> turn.toResult()
            else -> GmTurnResult.Failed(
                turn.copy(
                    status = GameTurnStatus.FAILED,
                    error = "Turn result could not be stored",
                    outputKind = GameTurnOutputKind.FAILURE,
                    errorCode = GameTurnErrorCode.STORAGE_FAILURE,
                ),
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
        outputKind: GameTurnOutputKind = if (error == null) {
            GameTurnOutputKind.NONE
        } else {
            GameTurnOutputKind.FAILURE
        },
        errorCode: GameTurnErrorCode? = null,
        recoveryKind: GameTurnRecoveryKind = GameTurnRecoveryKind.NONE,
    ): GmTurnResult {
        val evidenceThrough = deliveredSequence?.takeIf { it > base.acceptedSequence }
        val turn = base.copy(
            status = status,
            revision = base.revision + 1,
            output = output,
            error = error,
            worldChanged = worldChanged,
            deliveredSequence = deliveredSequence,
            outputKind = outputKind,
            evidenceFromSequenceExclusive = base.acceptedSequence.takeIf { evidenceThrough != null },
            evidenceThroughSequenceInclusive = evidenceThrough,
            errorCode = errorCode,
            recoveryKind = recoveryKind,
        )
        return when (turnStore.save(turn, base.revision)) {
            GameTurnStoreResult.Success -> {
                continuityFor(turn.runId).scheduleCompaction(
                    visibleSequence = deliveredSequence ?: base.acceptedSequence,
                    currentPresentationTokens = ((turn.input.length + turn.output.orEmpty().length + 2) / 3).toLong(),
                )
                turn.toResult()
            }
            else -> GmTurnResult.Failed(
                turn.copy(
                    status = GameTurnStatus.FAILED,
                    error = "Turn result could not be stored",
                    outputKind = GameTurnOutputKind.FAILURE,
                    errorCode = GameTurnErrorCode.STORAGE_FAILURE,
                ),
            )
        }
    }

    private fun continuityFor(runId: RunId): GmContinuityCoordinator =
        continuityCoordinators.getOrPut(runId) {
            val store = memoryStoreFactory?.invoke(runId)
                ?: fallbackMemoryStores.getOrPut(runId) { InMemoryAgentMemoryStore() }
            val compactor = backgroundScope?.let { scope ->
                AgentCompactionCoordinator(scope, store, compactionModel, compactionPolicy)
            }
            GmContinuityCoordinator(
                runId = runId,
                turnStore = turnStore,
                gameSession = gameSession,
                memoryStore = store,
                compactionCoordinator = compactor,
                policy = continuityPolicy,
            )
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
        if (visibleSequence < existing.acceptedSequence) {
            return GmTurnResult.Failed(
                existing.copy(
                    status = GameTurnStatus.FAILED,
                    outputKind = GameTurnOutputKind.FAILURE,
                    errorCode = GameTurnErrorCode.STORAGE_FAILURE,
                    error = "Interrupted turn references EventLog facts unavailable in this run",
                ),
            )
        }
        val recovered = existing.asInterruptedRecovery(visibleSequence)
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
                outputKind = GameTurnOutputKind.FAILURE,
                errorCode = GameTurnErrorCode.INVALID_REQUEST,
            ),
        )

    private fun invalidRequest(
        runId: RunId,
        turnId: TurnId,
        input: String,
        requestKind: GameTurnRequestKind,
        parentTurnId: TurnId?,
        message: String,
    ): GmTurnResult.Failed = GmTurnResult.Failed(
        GameTurn(
            runId = runId,
            turnId = turnId,
            input = input,
            status = GameTurnStatus.FAILED,
            revision = 0,
            acceptedSequence = 0,
            outputKind = GameTurnOutputKind.FAILURE,
            error = message,
            errorCode = GameTurnErrorCode.INVALID_REQUEST,
            requestKind = requestKind,
            parentTurnId = parentTurnId,
        ),
    )

    private fun GameTurn.toResult(): GmTurnResult = when (status) {
        GameTurnStatus.AWAITING_PLAYER -> GmTurnResult.AwaitingPlayer(this)
        GameTurnStatus.COMPLETED -> GmTurnResult.Completed(this)
        GameTurnStatus.CANCELLED -> GmTurnResult.Cancelled(this)
        GameTurnStatus.ACCEPTED, GameTurnStatus.RUNNING, GameTurnStatus.FAILED -> GmTurnResult.Failed(this)
    }

    private fun AgentRunErrorCode.toGameTurnErrorCode(): GameTurnErrorCode = when (this) {
        AgentRunErrorCode.SESSION_STORAGE_FAILURE -> GameTurnErrorCode.STORAGE_FAILURE
        AgentRunErrorCode.PROVIDER_CAPABILITY_UNAVAILABLE,
        AgentRunErrorCode.PROVIDER_FAILURE,
        AgentRunErrorCode.INVALID_PROVIDER_RESPONSE,
        AgentRunErrorCode.TIMEOUT,
        -> GameTurnErrorCode.PROVIDER_FAILURE
        AgentRunErrorCode.TOOL_REJECTED,
        AgentRunErrorCode.TOOL_LOOP_DETECTED,
        AgentRunErrorCode.STEP_LIMIT_EXCEEDED,
        AgentRunErrorCode.TOOL_CALL_LIMIT_EXCEEDED,
        AgentRunErrorCode.INPUT_TOKEN_BUDGET_EXCEEDED,
        AgentRunErrorCode.OUTPUT_TOKEN_BUDGET_EXCEEDED,
        AgentRunErrorCode.COST_BUDGET_EXCEEDED,
        -> GameTurnErrorCode.TOOL_FAILURE
        AgentRunErrorCode.SESSION_OWNERSHIP_MISMATCH,
        AgentRunErrorCode.SESSION_CONFLICT,
        -> GameTurnErrorCode.INVALID_REQUEST
    }
}
