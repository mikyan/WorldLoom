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
    val status: GameTurnStatus,
    val playerInput: String,
    val outputKind: GameTurnOutputKind,
    val publicOutput: String?,
    val safeFailureMessage: String?,
    val recoveryKind: GameTurnRecoveryKind,
    val evidence: HostedTurnEvidence?,
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
            if (turn.evidenceThroughSequenceInclusive?.let { it > currentEventSequence } == true) {
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
                status = turn.status,
                playerInput = turn.input,
                outputKind = turn.outputKind,
                publicOutput = turn.output?.takeIf {
                    turn.outputKind in setOf(GameTurnOutputKind.NARRATION, GameTurnOutputKind.CLARIFICATION)
                },
                safeFailureMessage = turn.errorCode?.let(::safeFailureMessage),
                recoveryKind = turn.recoveryKind,
                evidence = turn.evidenceFromSequenceExclusive?.let { from ->
                    HostedTurnEvidence(from, requireNotNull(turn.evidenceThroughSequenceInclusive))
                },
            )
        }
        return HostedTurnHistoryResult.Success(
            HostedTurnHistoryPage(items, issues, source.page.hasEarlier),
        )
    }

    private fun safeFailureMessage(code: GameTurnErrorCode): String = when (code) {
        GameTurnErrorCode.INVALID_REQUEST -> "该回合请求无效。"
        GameTurnErrorCode.PROVIDER_FAILURE -> "主持服务暂时不可用。"
        GameTurnErrorCode.TOOL_FAILURE -> "该回合未能完成受权威规则约束的操作。"
        GameTurnErrorCode.STORAGE_FAILURE -> "该回合未能安全保存。"
        GameTurnErrorCode.CANCELLED -> "该回合已取消。"
        GameTurnErrorCode.INTERRUPTED -> "该回合被中断，请按恢复提示继续。"
        GameTurnErrorCode.UNKNOWN -> "该回合未能完成。"
    }

    private fun safeProblemMessage(code: GameTurnHistoryProblemCode): String = when (code) {
        GameTurnHistoryProblemCode.INVALID_SCHEMA -> "一条主持记录使用了不兼容的版本。"
        GameTurnHistoryProblemCode.INVALID_JSON -> "一条主持记录已损坏。"
        GameTurnHistoryProblemCode.IDENTITY_MISMATCH -> "一条主持记录的身份与存储位置不一致。"
        GameTurnHistoryProblemCode.FUTURE_EVIDENCE -> "一条主持记录引用了尚不存在的世界事件。"
    }
}
