package io.worldloom.agent.runtime

import io.worldloom.provider.api.ProviderMessage
import io.worldloom.provider.api.ProviderMessageRole
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmInline

private val MEMORY_ID_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9._:-]*$")

@JvmInline
value class AgentMemoryId(val value: String) {
    init {
        require(MEMORY_ID_PATTERN.matches(value)) { "Agent memory id must be stable" }
    }
}

enum class AgentMemoryKind {
    EPISODIC,
    BELIEF,
    GOAL,
    RELATIONSHIP,
}

data class AgentMemoryRecord(
    val id: AgentMemoryId,
    val agentId: AgentId,
    val kind: AgentMemoryKind,
    val content: String,
    val salience: Double,
    val confidence: Double,
    val tags: Set<String> = emptySet(),
    val relatedEntityIds: Set<String> = emptySet(),
    val sourceEventIds: Set<String> = emptySet(),
    val protected: Boolean = false,
    val createdSequence: Long,
    val lastAccessedSequence: Long = createdSequence,
    val dataVersion: Int = 1,
) {
    init {
        require(content.isNotBlank()) { "Agent memory content must not be blank" }
        require(salience in 0.0..1.0) { "Memory salience must be between 0 and 1" }
        require(confidence in 0.0..1.0) { "Memory confidence must be between 0 and 1" }
        require(createdSequence >= 0) { "Memory creation sequence must not be negative" }
        require(lastAccessedSequence >= createdSequence) { "Memory access sequence precedes creation" }
        require(dataVersion > 0) { "Memory data version must be positive" }
        require(tags.none(String::isBlank)) { "Memory tags must not be blank" }
        require(relatedEntityIds.none(String::isBlank)) { "Related entity ids must not be blank" }
        require(sourceEventIds.none(String::isBlank)) { "Source event ids must not be blank" }
    }
}

data class AgentTurnRecord(
    val agentId: AgentId,
    val sessionId: AgentSessionId,
    val sequence: Long,
    val input: String,
    val output: String,
    val tokenCount: Long,
    val sourceEventIds: Set<String> = emptySet(),
) {
    init {
        require(sequence > 0) { "Agent turn sequence must be positive" }
        require(input.isNotBlank()) { "Agent turn input must not be blank" }
        require(output.isNotBlank()) { "Agent turn output must not be blank" }
        require(tokenCount >= 0) { "Agent turn token count must not be negative" }
    }
}

data class ContextCheckpoint(
    val idempotencyKey: String,
    val agentId: AgentId,
    val fromSequence: Long,
    val toSequence: Long,
    val promptVersion: Int,
    val modelId: String,
    val summary: String,
    val sourceEventIds: Set<String>,
    val publishedAtEpochMillis: Long,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "Checkpoint idempotency key must not be blank" }
        require(fromSequence > 0 && toSequence >= fromSequence) { "Checkpoint range is invalid" }
        require(promptVersion > 0) { "Checkpoint prompt version must be positive" }
        require(modelId.isNotBlank()) { "Checkpoint model id must not be blank" }
        require(summary.isNotBlank()) { "Checkpoint summary must not be blank" }
        require(publishedAtEpochMillis >= 0) { "Checkpoint publication time must not be negative" }
    }
}

data class CompactionPublication(
    val checkpoint: ContextCheckpoint,
    val memories: List<AgentMemoryRecord>,
)

interface AgentMemoryStore {
    suspend fun appendTurn(turn: AgentTurnRecord): Boolean

    suspend fun turns(
        agentId: AgentId,
        afterSequence: Long = 0,
        throughSequence: Long = Long.MAX_VALUE,
    ): List<AgentTurnRecord>

    suspend fun upsertMemory(memory: AgentMemoryRecord)

    suspend fun memories(agentId: AgentId): List<AgentMemoryRecord>

    suspend fun latestCheckpoint(agentId: AgentId): ContextCheckpoint?

    /** Publishes a validated checkpoint and its derived memories as one atomic visibility change. */
    suspend fun publish(publication: CompactionPublication): Boolean
}

class InMemoryAgentMemoryStore : AgentMemoryStore {
    private val mutex = Mutex()
    private val turns = mutableMapOf<AgentId, MutableList<AgentTurnRecord>>()
    private val memories = mutableMapOf<AgentId, MutableMap<AgentMemoryId, AgentMemoryRecord>>()
    private val checkpoints = mutableMapOf<AgentId, MutableList<ContextCheckpoint>>()
    private val publishedKeys = mutableSetOf<String>()

    override suspend fun appendTurn(turn: AgentTurnRecord): Boolean = mutex.withLock {
        val history = turns.getOrPut(turn.agentId) { mutableListOf() }
        if (history.any { it.sequence == turn.sequence }) return@withLock false
        require(history.lastOrNull()?.sequence?.let { turn.sequence == it + 1 } != false) {
            "Agent turn history must be contiguous"
        }
        history += turn
        true
    }

    override suspend fun turns(
        agentId: AgentId,
        afterSequence: Long,
        throughSequence: Long,
    ): List<AgentTurnRecord> = mutex.withLock {
        turns[agentId].orEmpty().filter { it.sequence > afterSequence && it.sequence <= throughSequence }
    }

    override suspend fun upsertMemory(memory: AgentMemoryRecord) {
        mutex.withLock {
            val agentMemories = memories.getOrPut(memory.agentId) { mutableMapOf() }
            val duplicate = agentMemories.values.firstOrNull {
                it.kind == memory.kind &&
                    it.sourceEventIds.isNotEmpty() &&
                    it.sourceEventIds == memory.sourceEventIds
            }
            agentMemories[duplicate?.id ?: memory.id] = memory.copy(id = duplicate?.id ?: memory.id)
        }
    }

    override suspend fun memories(agentId: AgentId): List<AgentMemoryRecord> = mutex.withLock {
        memories[agentId].orEmpty().values.sortedBy { it.id.value }
    }

    override suspend fun latestCheckpoint(agentId: AgentId): ContextCheckpoint? = mutex.withLock {
        checkpoints[agentId].orEmpty().maxByOrNull(ContextCheckpoint::toSequence)
    }

    override suspend fun publish(publication: CompactionPublication): Boolean = mutex.withLock {
        val checkpoint = publication.checkpoint
        if (checkpoint.idempotencyKey in publishedKeys) return@withLock true
        if (publication.memories.any { it.agentId != checkpoint.agentId }) return@withLock false
        val latest = checkpoints[checkpoint.agentId].orEmpty().maxByOrNull(ContextCheckpoint::toSequence)
        if (latest != null && checkpoint.fromSequence != latest.toSequence + 1) return@withLock false
        val history = turns[checkpoint.agentId].orEmpty()
        val covered = history.filter { it.sequence in checkpoint.fromSequence..checkpoint.toSequence }
        if (covered.size.toLong() != checkpoint.toSequence - checkpoint.fromSequence + 1) return@withLock false
        val agentMemories = memories.getOrPut(checkpoint.agentId) { mutableMapOf() }
        publication.memories.forEach { memory ->
            val duplicate = agentMemories.values.firstOrNull {
                it.kind == memory.kind &&
                    it.sourceEventIds.isNotEmpty() &&
                    it.sourceEventIds == memory.sourceEventIds
            }
            agentMemories[duplicate?.id ?: memory.id] = memory.copy(id = duplicate?.id ?: memory.id)
        }
        checkpoints.getOrPut(checkpoint.agentId) { mutableListOf() } += checkpoint
        publishedKeys += checkpoint.idempotencyKey
        true
    }
}

data class AgentContextRequest(
    val agentId: AgentId,
    val identity: String,
    val currentPerception: String,
    val activeGoals: List<String> = emptyList(),
    val relationships: List<String> = emptyList(),
    val relatedEntityIds: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val estimatedContextTokens: Long,
    val contextBudgetTokens: Long,
) {
    init {
        require(identity.isNotBlank()) { "Agent identity context must not be blank" }
        require(currentPerception.isNotBlank()) { "Current perception must not be blank" }
        require(estimatedContextTokens >= 0) { "Estimated context tokens must not be negative" }
        require(contextBudgetTokens > 0) { "Context budget must be positive" }
    }
}

data class BuiltAgentContext(
    val systemPrompt: String,
    val recentMessages: List<ProviderMessage>,
    val checkpoint: ContextCheckpoint?,
    val recalledMemories: List<AgentMemoryRecord>,
    val hardWatermarkApplied: Boolean,
)

class AgentContextBuilder(
    private val store: AgentMemoryStore,
) {
    suspend fun build(request: AgentContextRequest): BuiltAgentContext {
        val checkpoint = store.latestCheckpoint(request.agentId)
        val allTurns = store.turns(request.agentId)
        val hardWatermark = request.estimatedContextTokens * 4 >= request.contextBudgetTokens * 3
        val retainedTurns = allTurns.takeLast(if (hardWatermark) 6 else 8)
        val recallLimit = if (hardWatermark) 4 else 10
        val currentSequence = allTurns.lastOrNull()?.sequence ?: 0
        val recalled = store.memories(request.agentId)
            .sortedWith(
                compareByDescending<AgentMemoryRecord> { it.recallScore(request, currentSequence) }
                    .thenBy { it.id.value },
            )
            .take(recallLimit)
        val prompt = buildString {
            appendLine("Identity:")
            appendLine(request.identity)
            appendLine("Current perception:")
            appendLine(request.currentPerception)
            if (request.activeGoals.isNotEmpty()) appendLine("Goals:\n${request.activeGoals.joinToString("\n")}")
            if (request.relationships.isNotEmpty()) {
                appendLine("Relationships:\n${request.relationships.joinToString("\n")}")
            }
            checkpoint?.let { appendLine("Published checkpoint (${it.fromSequence}-${it.toSequence}):\n${it.summary}") }
            if (recalled.isNotEmpty()) {
                appendLine("Relevant private memories:")
                recalled.forEach { appendLine("- [${it.kind}] ${it.content}") }
            }
        }.trim()
        val messages = retainedTurns.flatMap { turn ->
            listOf(
                ProviderMessage(ProviderMessageRole.USER, turn.input.clipIfRequired(hardWatermark)),
                ProviderMessage(ProviderMessageRole.ASSISTANT, turn.output.clipIfRequired(hardWatermark)),
            )
        }
        return BuiltAgentContext(prompt, messages, checkpoint, recalled, hardWatermark)
    }

    private fun AgentMemoryRecord.recallScore(request: AgentContextRequest, currentSequence: Long): Double {
        val entityMatches = relatedEntityIds.count(request.relatedEntityIds::contains)
        val tagMatches = tags.count(request.tags::contains)
        val recency = 1.0 / (1.0 + (currentSequence - lastAccessedSequence).coerceAtLeast(0))
        return salience * 100 + confidence * 20 + entityMatches * 30 + tagMatches * 20 + recency * 10 +
            if (protected) 25 else 0
    }
}

private fun String.clipIfRequired(required: Boolean): String =
    if (!required || length <= 512) this else take(509) + "..."
