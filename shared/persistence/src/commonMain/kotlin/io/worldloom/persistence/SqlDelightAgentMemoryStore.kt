package io.worldloom.persistence

import io.worldloom.agent.runtime.AgentId
import io.worldloom.agent.runtime.AgentMemoryId
import io.worldloom.agent.runtime.AgentMemoryKind
import io.worldloom.agent.runtime.AgentMemoryRecord
import io.worldloom.agent.runtime.AgentMemoryStore
import io.worldloom.agent.runtime.AgentSessionId
import io.worldloom.agent.runtime.AgentTurnRecord
import io.worldloom.agent.runtime.CompactionPublication
import io.worldloom.agent.runtime.ContextCheckpoint
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.RunId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** SQLDelight-backed private memory partition. All caller-supplied timestamps and sequences remain explicit. */
class SqlDelightAgentMemoryStore(
    private val database: WorldloomDatabase,
    private val runId: RunId,
) : AgentMemoryStore {
    private val queries = database.worldloomQueries
    private val mutex = Mutex()
    private val json = Json
    private val stringListSerializer = ListSerializer(String.serializer())

    override suspend fun appendTurn(turn: AgentTurnRecord): Boolean = mutex.withLock {
        try {
            val existing = queries.selectAgentTurns(
                run_id = runId.value,
                agent_id = turn.agentId.value,
                sequence = 0,
                sequence_ = Long.MAX_VALUE,
            ).executeAsList()
            if (existing.any { it.sequence == turn.sequence }) return@withLock false
            if (existing.lastOrNull()?.sequence?.let { turn.sequence != it + 1 } == true) return@withLock false
            queries.insertAgentTurn(
                run_id = runId.value,
                agent_id = turn.agentId.value,
                sequence = turn.sequence,
                session_id = turn.sessionId.value,
                input_text = turn.input,
                output_text = turn.output,
                token_count = turn.tokenCount,
                source_event_ids_json = encodeStrings(turn.sourceEventIds),
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun turns(
        agentId: AgentId,
        afterSequence: Long,
        throughSequence: Long,
    ): List<AgentTurnRecord> = mutex.withLock {
        queries.selectAgentTurns(runId.value, agentId.value, afterSequence, throughSequence)
            .executeAsList()
            .map { row ->
                AgentTurnRecord(
                    agentId = AgentId(row.agent_id),
                    sessionId = AgentSessionId(row.session_id),
                    sequence = row.sequence,
                    input = row.input_text,
                    output = row.output_text,
                    tokenCount = row.token_count,
                    sourceEventIds = decodeStrings(row.source_event_ids_json),
                )
            }
    }

    override suspend fun upsertMemory(memory: AgentMemoryRecord) {
        mutex.withLock {
            database.transaction { upsertMemoryInsideTransaction(memory) }
        }
    }

    override suspend fun memories(agentId: AgentId): List<AgentMemoryRecord> = mutex.withLock {
        queries.selectAgentMemories(runId.value, agentId.value).executeAsList().map { row ->
            AgentMemoryRecord(
                id = AgentMemoryId(row.memory_id),
                agentId = agentId,
                kind = AgentMemoryKind.valueOf(row.kind),
                content = row.content,
                salience = row.salience,
                confidence = row.confidence,
                tags = decodeStrings(row.tags_json),
                relatedEntityIds = decodeStrings(row.related_entity_ids_json),
                sourceEventIds = links(row.memory_id, agentId, "memory"),
                protected = row.protected_ != 0L,
                createdSequence = row.created_sequence,
                lastAccessedSequence = row.last_accessed_sequence,
                dataVersion = row.data_version.toInt(),
            )
        }
    }

    override suspend fun latestCheckpoint(agentId: AgentId): ContextCheckpoint? = mutex.withLock {
        queries.selectLatestContextCheckpoint(runId.value, agentId.value).executeAsOneOrNull()?.let { row ->
            ContextCheckpoint(
                idempotencyKey = row.idempotency_key,
                agentId = agentId,
                fromSequence = row.from_sequence,
                toSequence = row.to_sequence,
                promptVersion = row.prompt_version.toInt(),
                modelId = row.model_id,
                summary = row.summary,
                sourceEventIds = decodeStrings(row.source_event_ids_json),
                publishedAtEpochMillis = row.published_at_epoch_millis,
            )
        }
    }

    override suspend fun publish(publication: CompactionPublication): Boolean = mutex.withLock {
        try {
            var published = false
            database.transaction {
                val checkpoint = publication.checkpoint
                if (queries.selectCheckpointByKey(
                        runId.value,
                        checkpoint.agentId.value,
                        checkpoint.idempotencyKey,
                    ).executeAsOneOrNull() != null
                ) {
                    published = true
                    return@transaction
                }
                if (publication.memories.any { it.agentId != checkpoint.agentId }) return@transaction
                val previous = queries.selectLatestContextCheckpoint(
                    runId.value,
                    checkpoint.agentId.value,
                ).executeAsOneOrNull()
                if (previous != null && checkpoint.fromSequence != previous.to_sequence + 1) return@transaction
                val count = queries.countAgentTurnsInRange(
                    runId.value,
                    checkpoint.agentId.value,
                    checkpoint.fromSequence,
                    checkpoint.toSequence,
                ).executeAsOne()
                if (count != checkpoint.toSequence - checkpoint.fromSequence + 1) return@transaction
                queries.insertContextCheckpoint(
                    run_id = runId.value,
                    agent_id = checkpoint.agentId.value,
                    idempotency_key = checkpoint.idempotencyKey,
                    from_sequence = checkpoint.fromSequence,
                    to_sequence = checkpoint.toSequence,
                    prompt_version = checkpoint.promptVersion.toLong(),
                    model_id = checkpoint.modelId,
                    summary = checkpoint.summary,
                    source_event_ids_json = encodeStrings(checkpoint.sourceEventIds),
                    published_at_epoch_millis = checkpoint.publishedAtEpochMillis,
                )
                replaceLinks(checkpoint.agentId, checkpoint.idempotencyKey, "checkpoint", checkpoint.sourceEventIds)
                publication.memories.forEach(::upsertMemoryInsideTransaction)
                published = true
            }
            published
        } catch (_: Exception) {
            false
        }
    }

    private fun upsertMemoryInsideTransaction(memory: AgentMemoryRecord) {
        val existingKind = queries.selectAgentMemory(
            runId.value,
            memory.agentId.value,
            memory.id.value,
        ).executeAsOneOrNull()
        require(existingKind == null || existingKind == memory.kind.name) { "Memory kind is immutable" }
        queries.upsertAgentMemory(
            run_id = runId.value,
            agent_id = memory.agentId.value,
            memory_id = memory.id.value,
            kind = memory.kind.name,
            content = memory.content,
            salience = memory.salience,
            confidence = memory.confidence,
            tags_json = encodeStrings(memory.tags),
            related_entity_ids_json = encodeStrings(memory.relatedEntityIds),
            protected_ = if (memory.protected) 1 else 0,
            created_sequence = memory.createdSequence,
            last_accessed_sequence = memory.lastAccessedSequence,
            data_version = memory.dataVersion.toLong(),
        )
        when (memory.kind) {
            AgentMemoryKind.EPISODIC -> Unit
            AgentMemoryKind.BELIEF -> queries.insertAgentBelief(runId.value, memory.agentId.value, memory.id.value)
            AgentMemoryKind.GOAL -> queries.insertAgentGoal(runId.value, memory.agentId.value, memory.id.value)
            AgentMemoryKind.RELATIONSHIP -> queries.insertRelationshipState(
                runId.value,
                memory.agentId.value,
                memory.id.value,
            )
        }
        replaceLinks(memory.agentId, memory.id.value, "memory", memory.sourceEventIds)
    }

    private fun replaceLinks(
        agentId: AgentId,
        recordId: String,
        kind: String,
        eventIds: Set<String>,
    ) {
        queries.deleteMemoryEventLinks(runId.value, agentId.value, recordId, kind)
        eventIds.sorted().forEach { eventId ->
            queries.insertMemoryEventLink(runId.value, agentId.value, recordId, eventId, kind)
        }
    }

    private fun links(recordId: String, agentId: AgentId, kind: String): Set<String> =
        queries.selectMemoryEventLinks(runId.value, agentId.value, recordId, kind).executeAsList().toSet()

    private fun encodeStrings(values: Set<String>): String =
        json.encodeToString(stringListSerializer, values.sorted())

    private fun decodeStrings(source: String): Set<String> =
        json.decodeFromString(stringListSerializer, source).toSet()
}
