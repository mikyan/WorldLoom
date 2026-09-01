package io.worldloom.agent.runtime

import io.worldloom.world.RunId

data class HostedTurnEvidence(
    val fromSequenceExclusive: Long,
    val throughSequenceInclusive: Long,
)

data class HostedTurnHistoryItem(
    val runId: RunId,
    val turnId: TurnId,
    val ordinal: Long,
    val acceptedSequence: Long,
    val status: GameTurnStatus,
    val playerInput: String,
    val outputKind: GameTurnOutputKind,
    val publicOutput: String?,
    val safeFailureMessage: String?,
    val recoveryKind: GameTurnRecoveryKind,
    val evidence: HostedTurnEvidence?,
    val pendingCheck: PendingPlayerCheck? = null,
)

data class HostedTurnHistoryIssue(
    val turnId: TurnId,
    val code: GameTurnHistoryProblemCode,
    val message: String,
)

data class HostedTurnHistoryPage(
    val items: List<HostedTurnHistoryItem>,
    val issues: List<HostedTurnHistoryIssue>,
    val hasEarlier: Boolean,
)

sealed interface HostedTurnHistoryResult {
    data class Success(val page: HostedTurnHistoryPage) : HostedTurnHistoryResult
    data class Failure(val message: String) : HostedTurnHistoryResult
}

data class GameTurnRecoveryReport(
    val recovered: List<GameTurn>,
    val historyProblems: List<GameTurnHistoryProblem>,
)

sealed interface GameTurnRecoveryResult {
    data class Completed(val report: GameTurnRecoveryReport) : GameTurnRecoveryResult
    data class Failure(val message: String) : GameTurnRecoveryResult
}

class GameTurnRecoveryCoordinator(private val store: GameTurnStore) {
    suspend fun recover(runId: RunId, currentEventSequence: Long): GameTurnRecoveryResult {
        val entries = mutableListOf<GameTurnHistoryEntry>()
        var before: Long? = null
        var hasEarlier: Boolean
        do {
            val page = when (val result = store.history(runId, beforeOrdinalExclusive = before, limit = 200)) {
                is GameTurnHistoryResult.Success -> result.page
                is GameTurnHistoryResult.Failure -> return GameTurnRecoveryResult.Failure(result.message)
            }
            entries += page.entries
            before = page.entries.minOfOrNull(GameTurnHistoryEntry::ordinal)
            hasEarlier = page.hasEarlier
            if (hasEarlier && before == null) {
                return GameTurnRecoveryResult.Failure("GM history pagination did not advance")
            }
        } while (hasEarlier)

        val recovered = mutableListOf<GameTurn>()
        entries.mapNotNull(GameTurnHistoryEntry::turn)
            .filter { it.status in setOf(GameTurnStatus.ACCEPTED, GameTurnStatus.RUNNING) }
            .filter { it.acceptedSequence <= currentEventSequence }
            .forEach { interrupted ->
                val terminal = interrupted.asInterruptedRecovery(currentEventSequence)
                when (store.save(terminal, interrupted.revision)) {
                    GameTurnStoreResult.Success -> recovered += terminal
                    GameTurnStoreResult.RevisionConflict -> Unit
                    is GameTurnStoreResult.Failure -> return GameTurnRecoveryResult.Failure(
                        "Unable to recover interrupted GM history",
                    )
                }
            }
        return GameTurnRecoveryResult.Completed(
            GameTurnRecoveryReport(
                recovered = recovered,
                historyProblems = entries.mapNotNull(GameTurnHistoryEntry::problem),
            ),
        )
    }
}

fun GameTurn.asInterruptedRecovery(visibleSequence: Long): GameTurn {
    require(status in setOf(GameTurnStatus.ACCEPTED, GameTurnStatus.RUNNING)) {
        "Only active GM turns can be recovered as interrupted"
    }
    require(visibleSequence >= acceptedSequence) {
        "Interrupted GM turn cannot be recovered against an older EventLog projection"
    }
    val factsCommitted = visibleSequence > acceptedSequence
    return copy(
        schemaVersion = CURRENT_GM_TURN_SCHEMA_VERSION,
        status = GameTurnStatus.FAILED,
        revision = revision + 1,
        deliveredSequence = visibleSequence,
        worldChanged = factsCommitted,
        outputKind = GameTurnOutputKind.FAILURE,
        evidenceFromSequenceExclusive = acceptedSequence.takeIf { factsCommitted },
        evidenceThroughSequenceInclusive = visibleSequence.takeIf { factsCommitted },
        recoveryKind = if (factsCommitted) {
            GameTurnRecoveryKind.NARRATION_REQUIRED
        } else {
            GameTurnRecoveryKind.RETRY_SAFE
        },
        errorCode = GameTurnErrorCode.INTERRUPTED,
        error = if (factsCommitted) {
            "Turn was interrupted after authoritative facts were committed"
        } else {
            "Turn was interrupted before authoritative facts were committed"
        },
    )
}

/** Maps durable presentation records without treating model prose as an EventLog fact. */
object HostedTurnHistoryProjector {
    fun project(
        source: GameTurnHistoryResult,
        currentEventSequence: Long,
    ): HostedTurnHistoryResult {
        if (source is GameTurnHistoryResult.Failure) return HostedTurnHistoryResult.Failure(source.message)
        source as GameTurnHistoryResult.Success
        val issues = mutableListOf<HostedTurnHistoryIssue>()
        val items = source.page.entries.mapNotNull { entry ->
            entry.problem?.let { problem ->
                issues += HostedTurnHistoryIssue(problem.turnId, problem.code, safeProblemMessage(problem.code))
                return@mapNotNull null
            }
            val turn = requireNotNull(entry.turn).toCurrentSchema()
            if (
                turn.acceptedSequence > currentEventSequence ||
                turn.evidenceThroughSequenceInclusive?.let { it > currentEventSequence } == true
            ) {
                issues += HostedTurnHistoryIssue(
                    turn.turnId,
                    GameTurnHistoryProblemCode.FUTURE_EVIDENCE,
                    safeProblemMessage(GameTurnHistoryProblemCode.FUTURE_EVIDENCE),
                )
                return@mapNotNull null
            }
            HostedTurnHistoryItem(
                runId = turn.runId,
                turnId = turn.turnId,
                ordinal = entry.ordinal,
                acceptedSequence = turn.acceptedSequence,
                status = turn.status,
                playerInput = turn.input,
                outputKind = turn.outputKind,
                publicOutput = turn.output?.takeIf {
                    turn.outputKind in setOf(GameTurnOutputKind.NARRATION, GameTurnOutputKind.CLARIFICATION)
                },
                safeFailureMessage = turn.errorCode?.let(::publicGameTurnFailureMessage),
                recoveryKind = turn.recoveryKind,
                evidence = turn.evidenceFromSequenceExclusive?.let { from ->
                    HostedTurnEvidence(from, requireNotNull(turn.evidenceThroughSequenceInclusive))
                },
                pendingCheck = turn.pendingCheck,
            )
        }
        return HostedTurnHistoryResult.Success(
            HostedTurnHistoryPage(items, issues, source.page.hasEarlier),
        )
    }

    private fun safeProblemMessage(code: GameTurnHistoryProblemCode): String = when (code) {
        GameTurnHistoryProblemCode.INVALID_SCHEMA -> "一条主持记录使用了不兼容的版本。"
        GameTurnHistoryProblemCode.INVALID_JSON -> "一条主持记录已损坏。"
        GameTurnHistoryProblemCode.IDENTITY_MISMATCH -> "一条主持记录的身份与存储位置不一致。"
        GameTurnHistoryProblemCode.FUTURE_EVIDENCE -> "一条主持记录引用了尚不存在的世界事件。"
    }
}

fun publicGameTurnFailureMessage(code: GameTurnErrorCode): String = when (code) {
    GameTurnErrorCode.INVALID_REQUEST -> "该回合请求无效。"
    GameTurnErrorCode.PROVIDER_FAILURE -> "主持服务暂时不可用。"
    GameTurnErrorCode.TOOL_FAILURE -> "该回合未能完成受权威规则约束的操作。"
    GameTurnErrorCode.STORAGE_FAILURE -> "该回合未能安全保存。"
    GameTurnErrorCode.CANCELLED -> "该回合已取消。"
    GameTurnErrorCode.INTERRUPTED -> "该回合被中断，请按恢复提示继续。"
    GameTurnErrorCode.UNKNOWN -> "该回合未能完成。"
}
