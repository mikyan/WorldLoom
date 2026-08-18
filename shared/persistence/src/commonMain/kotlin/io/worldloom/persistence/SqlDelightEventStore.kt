package io.worldloom.persistence

import io.worldloom.definition.DefinitionId
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.CURRENT_SAVE_DATA_SCHEMA_VERSION
import io.worldloom.world.DurableEventStore
import io.worldloom.world.DurableStoreError
import io.worldloom.world.DurableStoreErrorCode
import io.worldloom.world.DurableStoreLoadResult
import io.worldloom.world.DurableStoreWriteResult
import io.worldloom.world.EventAppendResult
import io.worldloom.world.EventEnvelope
import io.worldloom.world.EventStoreError
import io.worldloom.world.EventStoreErrorCode
import io.worldloom.world.GameState
import io.worldloom.world.PersistedRun
import io.worldloom.world.RunId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val CURRENT_STATE_SNAPSHOT_SCHEMA_VERSION: Int = 1

class SqlDelightEventStore(
    private val database: WorldloomDatabase,
) : DurableEventStore {
    private val mutex = Mutex()
    private val queries = database.worldloomQueries

    override suspend fun initialize(
        initialState: GameState,
        worldContentVersion: Int,
    ): DurableStoreWriteResult = mutex.withLock {
        if (worldContentVersion <= 0) {
            return@withLock durableFailure(
                DurableStoreErrorCode.STORAGE_FAILURE,
                "World content version must be positive",
            )
        }
        try {
            val existing = queries.selectRun(initialState.runId.value).executeAsOneOrNull()
            if (existing != null) {
                return@withLock durableFailure(
                    DurableStoreErrorCode.RUN_ALREADY_EXISTS,
                    "Run already exists: ${initialState.runId.value}",
                )
            }
            database.transaction {
                queries.insertRun(
                    initialState.runId.value,
                    initialState.worldDefinitionId.value,
                    CURRENT_SAVE_DATA_SCHEMA_VERSION.toLong(),
                )
                queries.setRunContentVersion(worldContentVersion.toLong(), initialState.runId.value)
                queries.upsertSnapshot(
                    run_id = initialState.runId.value,
                    sequence = initialState.lastSequence,
                    state_schema_version = CURRENT_STATE_SNAPSHOT_SCHEMA_VERSION.toLong(),
                    state_json = PersistenceCodec.encodeState(initialState),
                )
            }
            DurableStoreWriteResult.Success
        } catch (_: Exception) {
            durableFailure(DurableStoreErrorCode.STORAGE_FAILURE, "Unable to initialize the persistent run")
        }
    }

    override suspend fun append(
        runId: RunId,
        expectedSequence: Long,
        events: List<EventEnvelope>,
    ): EventAppendResult = mutex.withLock {
        validateBatch(runId, expectedSequence, events)?.let { return@withLock it }
        try {
            // This metadata write is deliberately outside the EventLog transaction. If it fails,
            // facts may still commit and the directory later derives a repairable pending state.
            runCatching { queries.markRunEventWriting(runId.value) }
            var failure: EventAppendResult.Failure? = null
            database.transaction {
                if (queries.selectRun(runId.value).executeAsOneOrNull() == null) {
                    failure = eventFailure(EventStoreErrorCode.STORAGE_FAILURE, "Persistent run is not initialized")
                    return@transaction
                }
                val currentSequence = queries.currentSequence(runId.value).executeAsOne()
                if (currentSequence != expectedSequence) {
                    failure = eventFailure(
                        EventStoreErrorCode.SEQUENCE_CONFLICT,
                        "Expected sequence $expectedSequence, current sequence is $currentSequence",
                    )
                    return@transaction
                }
                events.forEach { event ->
                    queries.insertEvent(
                        run_id = runId.value,
                        sequence = event.sequence,
                        event_id = event.eventId.value,
                        event_schema_version = event.schemaVersion.toLong(),
                        event_json = PersistenceCodec.encodeEvent(event),
                    )
                }
            }
            failure ?: run {
                runCatching { queries.markRunEventPersisted(events.last().sequence, runId.value) }
                EventAppendResult.Success(events.last().sequence)
            }
        } catch (_: Exception) {
            eventFailure(EventStoreErrorCode.STORAGE_FAILURE, "Unable to append the event batch atomically")
        }
    }

    override suspend fun read(
        runId: RunId,
        afterSequence: Long,
    ): List<EventEnvelope> = mutex.withLock {
        queries.selectEventsAfter(runId.value, afterSequence).executeAsList().map { source ->
            when (val decoded = PersistenceCodec.decodeEvent(source)) {
                is PersistenceDecodeResult.Success -> decoded.value
                is PersistenceDecodeResult.Failure -> throw CorruptPersistenceException(decoded.message)
            }
        }
    }

    override suspend fun writeSnapshot(state: GameState): DurableStoreWriteResult = mutex.withLock {
        try {
            val run = queries.selectRun(state.runId.value).executeAsOneOrNull()
                ?: return@withLock durableFailure(DurableStoreErrorCode.RUN_NOT_FOUND, "Run is not stored")
            if (run.world_definition_id != state.worldDefinitionId.value) {
                return@withLock durableFailure(
                    DurableStoreErrorCode.WORLD_MISMATCH,
                    "Snapshot world does not match the stored run",
                )
            }
            val currentSequence = queries.currentSequence(state.runId.value).executeAsOne()
            if (currentSequence != state.lastSequence) {
                return@withLock durableFailure(
                    DurableStoreErrorCode.SEQUENCE_CONFLICT,
                    "Snapshot sequence ${state.lastSequence} does not match EventLog sequence $currentSequence",
                )
            }
            queries.upsertSnapshot(
                run_id = state.runId.value,
                sequence = state.lastSequence,
                state_schema_version = CURRENT_STATE_SNAPSHOT_SCHEMA_VERSION.toLong(),
                state_json = PersistenceCodec.encodeState(state),
            )
            DurableStoreWriteResult.Success
        } catch (_: Exception) {
            durableFailure(DurableStoreErrorCode.STORAGE_FAILURE, "Unable to publish the state snapshot")
        }
    }

    override suspend fun loadRun(runId: RunId): DurableStoreLoadResult = mutex.withLock {
        try {
            val run = queries.selectRun(runId.value).executeAsOneOrNull()
                ?: return@withLock loadFailure(DurableStoreErrorCode.RUN_NOT_FOUND, "Run is not stored")
            if (run.data_schema_version != CURRENT_SAVE_DATA_SCHEMA_VERSION.toLong()) {
                return@withLock loadFailure(
                    DurableStoreErrorCode.CORRUPT_DATA,
                    "Unsupported save data schema version: ${run.data_schema_version}",
                )
            }
            val snapshotRow = queries.selectSnapshot(runId.value).executeAsOneOrNull()
            var snapshotFallbackReason: String? = null
            val snapshot = when {
                snapshotRow == null -> null
                snapshotRow.state_schema_version != CURRENT_STATE_SNAPSHOT_SCHEMA_VERSION.toLong() -> {
                    snapshotFallbackReason = "Unsupported snapshot schema; rebuilt from EventLog"
                    null
                }
                else -> when (val decoded = PersistenceCodec.decodeState(snapshotRow.state_json)) {
                    is PersistenceDecodeResult.Failure -> {
                        snapshotFallbackReason = "Invalid snapshot; rebuilt from EventLog"
                        null
                    }
                    is PersistenceDecodeResult.Success -> decoded.value.takeIf { state ->
                        state.runId == runId &&
                            state.worldDefinitionId.value == run.world_definition_id &&
                            state.lastSequence == snapshotRow.sequence
                    } ?: run {
                        snapshotFallbackReason = "Snapshot identity mismatch; rebuilt from EventLog"
                        null
                    }
                }
            }
            val afterSequence = snapshot?.lastSequence ?: 0
            val events = try {
                queries.selectEventsAfter(runId.value, afterSequence).executeAsList().map { source ->
                    when (val decoded = PersistenceCodec.decodeEvent(source)) {
                        is PersistenceDecodeResult.Success -> decoded.value
                        is PersistenceDecodeResult.Failure -> throw CorruptPersistenceException(decoded.message)
                    }
                }
            } catch (error: CorruptPersistenceException) {
                return@withLock loadFailure(DurableStoreErrorCode.CORRUPT_DATA, error.message ?: "Stored event is invalid")
            }
            DurableStoreLoadResult.Success(
                PersistedRun(
                    runId = runId,
                    worldDefinitionId = DefinitionId(run.world_definition_id),
                    snapshot = snapshot,
                    eventsAfterSnapshot = events,
                    worldContentVersion = run.world_content_version.toInt(),
                    snapshotFallbackReason = snapshotFallbackReason,
                ),
            )
        } catch (_: Exception) {
            loadFailure(DurableStoreErrorCode.STORAGE_FAILURE, "Unable to load the persistent run")
        }
    }

    private fun validateBatch(
        runId: RunId,
        expectedSequence: Long,
        events: List<EventEnvelope>,
    ): EventAppendResult.Failure? {
        if (events.isEmpty()) return eventFailure(EventStoreErrorCode.EMPTY_APPEND, "At least one event is required")
        events.forEachIndexed { index, event ->
            if (event.runId != runId) {
                return eventFailure(EventStoreErrorCode.RUN_MISMATCH, "Event run does not match append target")
            }
            val requiredSequence = expectedSequence + index + 1
            if (event.sequence != requiredSequence) {
                return eventFailure(
                    EventStoreErrorCode.INVALID_EVENT_SEQUENCE,
                    "Event sequence ${event.sequence} is not the required sequence $requiredSequence",
                )
            }
        }
        return null
    }

    private fun eventFailure(
        code: EventStoreErrorCode,
        message: String,
    ): EventAppendResult.Failure = EventAppendResult.Failure(EventStoreError(code, message))

    private fun durableFailure(
        code: DurableStoreErrorCode,
        message: String,
    ): DurableStoreWriteResult.Failure = DurableStoreWriteResult.Failure(DurableStoreError(code, message))

    private fun loadFailure(
        code: DurableStoreErrorCode,
        message: String,
    ): DurableStoreLoadResult.Failure = DurableStoreLoadResult.Failure(DurableStoreError(code, message))

    private class CorruptPersistenceException(message: String) : Exception(message)
}
