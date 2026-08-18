package io.worldloom.agent.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AgentCompactionPolicy(
    val softWatermarkPercent: Int = 50,
    val hardWatermarkPercent: Int = 75,
    val turnThreshold: Int = 12,
    val checkpointMinimumTurns: Int = 4,
    val checkpointMinimumTokens: Long = 2_000,
    val retainedRawTurns: Int = 6,
) {
    init {
        require(softWatermarkPercent in 1..99) { "Soft watermark must be a percentage" }
        require(hardWatermarkPercent in (softWatermarkPercent + 1)..100) {
            "Hard watermark must exceed soft watermark"
        }
        require(turnThreshold > retainedRawTurns) { "Turn threshold must exceed retained raw turns" }
        require(checkpointMinimumTurns > 0 && checkpointMinimumTokens > 0) {
            "Checkpoint minimums must be positive"
        }
        require(retainedRawTurns in 6..8) { "Raw turn retention must remain between 6 and 8" }
    }
}

enum class CompactionUrgency {
    NOT_NEEDED,
    BACKGROUND,
    HARD_WATERMARK,
}

data class CompactionModelOutput(
    val checkpointSummary: String,
    val memories: List<AgentMemoryRecord>,
    val coveredFromSequence: Long,
    val coveredToSequence: Long,
    val sourceEventIds: Set<String>,
)

interface ContextCompactionModel {
    suspend fun compact(
        agentId: AgentId,
        turns: List<AgentTurnRecord>,
        promptVersion: Int,
    ): CompactionModelOutput
}

sealed interface CompactionScheduleResult {
    data object NotNeeded : CompactionScheduleResult
    data class Scheduled(val urgency: CompactionUrgency, val throughSequence: Long) : CompactionScheduleResult
    data class Merged(val urgency: CompactionUrgency, val throughSequence: Long) : CompactionScheduleResult
}

private data class PendingCompaction(
    val throughSequence: Long,
    val forceCheckpoint: Boolean,
    val promptVersion: Int,
    val modelId: String,
    val publishedAtEpochMillis: Long,
)

/** Runs candidate generation off the foreground path and only exposes atomically validated publications. */
class AgentCompactionCoordinator(
    private val scope: CoroutineScope,
    private val store: AgentMemoryStore,
    private val model: ContextCompactionModel,
    private val policy: AgentCompactionPolicy = AgentCompactionPolicy(),
) {
    private val mutex = Mutex()
    private val active = mutableMapOf<AgentId, PendingCompaction>()
    private val queued = mutableMapOf<AgentId, PendingCompaction>()
    private val jobs = mutableMapOf<AgentId, Job>()

    suspend fun schedule(
        agentId: AgentId,
        estimatedContextTokens: Long,
        contextBudgetTokens: Long,
        checkpointRequested: Boolean,
        promptVersion: Int,
        modelId: String,
        publishedAtEpochMillis: Long,
    ): CompactionScheduleResult {
        require(estimatedContextTokens >= 0 && contextBudgetTokens > 0) { "Context pressure is invalid" }
        val checkpoint = store.latestCheckpoint(agentId)
        val tail = store.turns(agentId, afterSequence = checkpoint?.toSequence ?: 0)
        val tokenCount = tail.sumOf(AgentTurnRecord::tokenCount)
        val urgency = urgency(
            estimatedContextTokens,
            contextBudgetTokens,
            tail.size,
            tokenCount,
            checkpointRequested,
        )
        if (urgency == CompactionUrgency.NOT_NEEDED || tail.isEmpty()) return CompactionScheduleResult.NotNeeded
        val requested = PendingCompaction(
            throughSequence = tail.last().sequence,
            forceCheckpoint = checkpointRequested,
            promptVersion = promptVersion,
            modelId = modelId,
            publishedAtEpochMillis = publishedAtEpochMillis,
        )
        return mutex.withLock {
            val running = active[agentId]
            if (running != null) {
                val alreadyQueued = queued[agentId]
                queued[agentId] = requested.copy(
                    throughSequence = maxOf(alreadyQueued?.throughSequence ?: 0, requested.throughSequence),
                    forceCheckpoint = alreadyQueued?.forceCheckpoint == true || requested.forceCheckpoint,
                )
                CompactionScheduleResult.Merged(urgency, requested.throughSequence)
            } else {
                active[agentId] = requested
                jobs[agentId] = scope.launch { process(agentId, requested) }
                CompactionScheduleResult.Scheduled(urgency, requested.throughSequence)
            }
        }
    }

    suspend fun awaitIdle(agentId: AgentId) {
        while (true) {
            val job = mutex.withLock { jobs[agentId] } ?: return
            job.join()
            if (mutex.withLock { jobs[agentId] == null }) return
        }
    }

    fun urgency(
        estimatedContextTokens: Long,
        contextBudgetTokens: Long,
        uncompactedTurns: Int,
        uncompactedTokens: Long,
        checkpointRequested: Boolean,
    ): CompactionUrgency {
        val percent = if (contextBudgetTokens == 0L) 100 else estimatedContextTokens * 100 / contextBudgetTokens
        if (percent >= policy.hardWatermarkPercent) return CompactionUrgency.HARD_WATERMARK
        val explicit = checkpointRequested &&
            (uncompactedTurns >= policy.checkpointMinimumTurns || uncompactedTokens >= policy.checkpointMinimumTokens)
        return if (percent >= policy.softWatermarkPercent || uncompactedTurns >= policy.turnThreshold || explicit) {
            CompactionUrgency.BACKGROUND
        } else {
            CompactionUrgency.NOT_NEEDED
        }
    }

    private suspend fun process(
        agentId: AgentId,
        initialRequest: PendingCompaction,
    ) {
        try {
            var request = initialRequest
            while (true) {
                val latest = store.latestCheckpoint(agentId)
                val tail = store.turns(agentId, latest?.toSequence ?: 0, request.throughSequence)
                val countToCompact = when {
                    tail.size > policy.retainedRawTurns -> tail.size - policy.retainedRawTurns
                    request.forceCheckpoint -> tail.size
                    else -> 0
                }
                if (countToCompact > 0) {
                    val frozen = tail.take(countToCompact)
                    publishCandidate(agentId, request, frozen)
                }
                val next = mutex.withLock {
                    val newest = queued.remove(agentId)
                    if (newest == null || newest.throughSequence <= request.throughSequence) {
                        active.remove(agentId)
                        jobs.remove(agentId)
                        null
                    } else {
                        active[agentId] = newest
                        newest
                    }
                }
                request = next ?: return
            }
        } catch (_: Exception) {
            mutex.withLock {
                active.remove(agentId)
                queued.remove(agentId)
                jobs.remove(agentId)
            }
        }
    }

    private suspend fun publishCandidate(
        agentId: AgentId,
        request: PendingCompaction,
        frozen: List<AgentTurnRecord>,
    ) {
        val output = model.compact(agentId, frozen, request.promptVersion)
        val from = frozen.first().sequence
        val to = frozen.last().sequence
        if (output.coveredFromSequence != from || output.coveredToSequence != to) return
        if (output.checkpointSummary.isBlank() || output.memories.any { it.agentId != agentId }) return
        val sourceEvents = frozen.flatMap(AgentTurnRecord::sourceEventIds).toSet()
        if (!sourceEvents.containsAll(output.sourceEventIds)) return
        store.publish(
            CompactionPublication(
                checkpoint = ContextCheckpoint(
                    idempotencyKey = "${agentId.value}:$from:$to:${request.promptVersion}",
                    agentId = agentId,
                    fromSequence = from,
                    toSequence = to,
                    promptVersion = request.promptVersion,
                    modelId = request.modelId,
                    summary = output.checkpointSummary,
                    sourceEventIds = output.sourceEventIds,
                    publishedAtEpochMillis = request.publishedAtEpochMillis,
                ),
                memories = output.memories,
            ),
        )
    }
}
