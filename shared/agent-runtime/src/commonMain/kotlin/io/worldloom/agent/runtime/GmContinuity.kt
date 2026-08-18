package io.worldloom.agent.runtime

import io.worldloom.application.GameSession
import io.worldloom.application.SessionCommittedEvent
import io.worldloom.world.RunId

const val CURRENT_GM_CONTINUITY_SCHEMA_VERSION: Int = 1
val GM_AGENT_ID: AgentId = AgentId("worldloom.agent.gm")

fun gmSessionId(runId: RunId): AgentSessionId = AgentSessionId("worldloom.gm.${runId.value}")

data class GmContinuityPolicy(
    val schemaVersion: Int = CURRENT_GM_CONTINUITY_SCHEMA_VERSION,
    val contextBudgetTokens: Long = 64_000,
    val maxUncompactedTurnsInPrompt: Int = 12,
    val compactionPromptVersion: Int = 1,
    val compactionModelId: String = "worldloom.gm-public-continuity.v1",
) {
    init {
        require(schemaVersion == CURRENT_GM_CONTINUITY_SCHEMA_VERSION)
        require(contextBudgetTokens > 0)
        require(maxUncompactedTurnsInPrompt in 6..20)
        require(compactionPromptVersion > 0)
        require(compactionModelId.isNotBlank())
    }
}

data class GmContinuityContext(
    val checkpoint: ContextCheckpoint? = null,
    val uncompactedTurns: List<AgentTurnRecord> = emptyList(),
    val rawTailTruncated: Boolean = false,
) {
    init {
        require(checkpoint == null || checkpoint.agentId == GM_AGENT_ID)
        require(uncompactedTurns.all { it.agentId == GM_AGENT_ID })
    }
}

sealed interface GmContinuitySyncResult {
    data class Success(val appendedTurns: Int) : GmContinuitySyncResult
    data class Failure(val message: String) : GmContinuitySyncResult
}

/**
 * Repairs the public GM continuity stream from durable terminal turns, then exposes only the GM
 * partition. Failure never blocks play because this stream is narrative context, not world state.
 */
class GmContinuityCoordinator(
    private val runId: RunId,
    private val turnStore: GameTurnStore,
    private val gameSession: GameSession,
    private val memoryStore: AgentMemoryStore,
    private val compactionCoordinator: AgentCompactionCoordinator? = null,
    private val policy: GmContinuityPolicy = GmContinuityPolicy(),
    private val publicationClock: () -> Long = { 0L },
) {
    suspend fun prepare(visibleSequence: Long): GmContinuityContext {
        val synchronized = runCatching { synchronize(visibleSequence) }.getOrNull()
        if (synchronized !is GmContinuitySyncResult.Success) return GmContinuityContext()
        return runCatching {
            val checkpoint = memoryStore.latestCheckpoint(GM_AGENT_ID)
            val allTail = memoryStore.turns(GM_AGENT_ID, afterSequence = checkpoint?.toSequence ?: 0)
                .filter { it.sessionId == gmSessionId(runId) }
            GmContinuityContext(
                checkpoint = checkpoint,
                uncompactedTurns = allTail.takeLast(policy.maxUncompactedTurnsInPrompt),
                rawTailTruncated = allTail.size > policy.maxUncompactedTurnsInPrompt,
            )
        }.getOrElse { GmContinuityContext() }
    }

    suspend fun synchronize(visibleSequence: Long): GmContinuitySyncResult {
        if (visibleSequence < 0) return GmContinuitySyncResult.Failure("Visible sequence is invalid")
        val history = when (val result = terminalHistory()) {
            is TerminalHistoryResult.Success -> result.entries
            is TerminalHistoryResult.Failure -> return GmContinuitySyncResult.Failure(result.message)
        }
        val publicEvents = gameSession.committedEvents(0, visibleSequence)
        val visibleHistory = history.filter { turn ->
            turn.acceptedSequence <= visibleSequence && (turn.deliveredSequence ?: turn.acceptedSequence) <= visibleSequence
        }
        val expected = visibleHistory.mapIndexed { index, turn ->
            turn.toContinuityRecord(index + 1L, publicEvents)
        }
        val existing = memoryStore.turns(GM_AGENT_ID)
        if (existing.size > expected.size || existing.indices.any { existing[it] != expected[it] }) {
            return GmContinuitySyncResult.Failure("GM continuity history does not match durable public turns")
        }
        var appended = 0
        expected.drop(existing.size).forEach { record ->
            if (!memoryStore.appendTurn(record)) {
                val repaired = memoryStore.turns(GM_AGENT_ID).getOrNull(record.sequence.toInt() - 1)
                if (repaired != record) {
                    return GmContinuitySyncResult.Failure("GM continuity turn could not be appended")
                }
            } else {
                appended += 1
            }
        }
        return GmContinuitySyncResult.Success(appended)
    }

    suspend fun scheduleCompaction(visibleSequence: Long, currentPresentationTokens: Long) {
        runCatching {
            if (synchronize(visibleSequence) !is GmContinuitySyncResult.Success) return@runCatching
            val checkpoint = memoryStore.latestCheckpoint(GM_AGENT_ID)
            val tail = memoryStore.turns(GM_AGENT_ID, afterSequence = checkpoint?.toSequence ?: 0)
            compactionCoordinator?.schedule(
                agentId = GM_AGENT_ID,
                estimatedContextTokens = tail.sumOf(AgentTurnRecord::tokenCount) + currentPresentationTokens,
                contextBudgetTokens = policy.contextBudgetTokens,
                checkpointRequested = false,
                promptVersion = policy.compactionPromptVersion,
                modelId = policy.compactionModelId,
                publishedAtEpochMillis = publicationClock(),
            )
        }
    }

    private suspend fun terminalHistory(): TerminalHistoryResult {
        val entries = mutableListOf<GameTurnHistoryEntry>()
        var before: Long? = null
        do {
            val page = when (val result = turnStore.history(runId, beforeOrdinalExclusive = before, limit = 200)) {
                is GameTurnHistoryResult.Success -> result.page
                is GameTurnHistoryResult.Failure -> return TerminalHistoryResult.Failure(result.message)
            }
            entries += page.entries
            before = page.entries.minOfOrNull(GameTurnHistoryEntry::ordinal)
            if (!page.hasEarlier) break
            if (before == null) return TerminalHistoryResult.Failure("GM turn history pagination did not advance")
        } while (true)
        return TerminalHistoryResult.Success(
            entries.sortedBy(GameTurnHistoryEntry::ordinal).mapNotNull { entry ->
                entry.turn?.takeIf { turn ->
                    turn.runId == runId &&
                        turn.status in setOf(GameTurnStatus.COMPLETED, GameTurnStatus.AWAITING_PLAYER) &&
                        turn.outputKind in setOf(GameTurnOutputKind.NARRATION, GameTurnOutputKind.CLARIFICATION) &&
                        turn.input.isNotBlank() && !turn.output.isNullOrBlank()
                }
            },
        )
    }

    private fun GameTurn.toContinuityRecord(
        sequence: Long,
        publicEvents: List<SessionCommittedEvent>,
    ): AgentTurnRecord {
        val delivered = deliveredSequence ?: acceptedSequence
        val evidence = publicEvents.filter { it.sequence > acceptedSequence && it.sequence <= delivered }
        val publicInput = buildString {
            appendLine("公开主持回合 ${turnId.value}")
            appendLine("玩家输入：$input")
            if (evidence.isNotEmpty()) {
                appendLine("已提交公开事件：")
                evidence.forEach { event ->
                    appendLine("- #${event.sequence} ${event.eventType.value}: ${event.publicInput ?: event.eventId}")
                }
            }
        }.trim()
        val publicOutput = requireNotNull(output).trim()
        return AgentTurnRecord(
            agentId = GM_AGENT_ID,
            sessionId = gmSessionId(runId),
            sequence = sequence,
            input = publicInput,
            output = publicOutput,
            tokenCount = ((publicInput.length + publicOutput.length + 2) / 3).toLong().coerceAtLeast(1),
            sourceEventIds = evidence.mapTo(mutableSetOf()) { it.eventId },
        )
    }

    private sealed interface TerminalHistoryResult {
        data class Success(val entries: List<GameTurn>) : TerminalHistoryResult
        data class Failure(val message: String) : TerminalHistoryResult
    }
}

/** Deterministic, public-only fallback compactor used before adding a provider-specific summarizer. */
class GmPublicContinuityCompactionModel : ContextCompactionModel {
    override suspend fun compact(
        agentId: AgentId,
        turns: List<AgentTurnRecord>,
        promptVersion: Int,
    ): CompactionModelOutput {
        require(agentId == GM_AGENT_ID) { "GM compactor cannot read another agent partition" }
        require(turns.isNotEmpty()) { "GM compaction range must not be empty" }
        require(turns.all { it.agentId == GM_AGENT_ID }) { "GM compaction input crossed an agent partition" }
        val sourceEventIds = turns.flatMapTo(mutableSetOf(), AgentTurnRecord::sourceEventIds)
        val summary = buildString {
            appendLine("公开剧情检查点 v$promptVersion（回合 ${turns.first().sequence}-${turns.last().sequence}）")
            turns.forEach { turn ->
                appendLine("- ${turn.input.publicSnippet(320)}")
                appendLine("  主持叙事：${turn.output.publicSnippet(480)}")
            }
        }.trim().take(12_000)
        return CompactionModelOutput(
            checkpointSummary = summary,
            memories = listOf(
                AgentMemoryRecord(
                    id = AgentMemoryId("gm-plot:${turns.first().sequence}:${turns.last().sequence}"),
                    agentId = GM_AGENT_ID,
                    kind = AgentMemoryKind.EPISODIC,
                    content = summary,
                    salience = 0.85,
                    confidence = 1.0,
                    tags = setOf("gm-public-plot", "prompt-v$promptVersion"),
                    sourceEventIds = sourceEventIds,
                    protected = true,
                    createdSequence = turns.last().sequence,
                ),
            ),
            coveredFromSequence = turns.first().sequence,
            coveredToSequence = turns.last().sequence,
            sourceEventIds = sourceEventIds,
        )
    }
}

private fun String.publicSnippet(limit: Int): String {
    val normalized = lineSequence().joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").trim()
    return if (normalized.length <= limit) normalized else normalized.take(limit - 3) + "..."
}
