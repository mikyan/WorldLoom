package io.worldloom.world

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class EventStoreErrorCode {
    EMPTY_APPEND,
    SEQUENCE_CONFLICT,
    INVALID_EVENT_SEQUENCE,
    RUN_MISMATCH,
}

data class EventStoreError(
    val code: EventStoreErrorCode,
    val message: String,
)

sealed interface EventAppendResult {
    data class Success(val lastSequence: Long) : EventAppendResult

    data class Failure(val error: EventStoreError) : EventAppendResult
}

interface EventStore {
    suspend fun append(
        runId: RunId,
        expectedSequence: Long,
        events: List<EventEnvelope>,
    ): EventAppendResult

    suspend fun read(
        runId: RunId,
        afterSequence: Long = 0,
    ): List<EventEnvelope>
}

class InMemoryEventStore : EventStore {
    private val mutex = Mutex()
    private val eventsByRun = mutableMapOf<RunId, List<EventEnvelope>>()

    override suspend fun append(
        runId: RunId,
        expectedSequence: Long,
        events: List<EventEnvelope>,
    ): EventAppendResult = mutex.withLock {
        if (events.isEmpty()) {
            return@withLock failure(EventStoreErrorCode.EMPTY_APPEND, "At least one event is required")
        }

        val existing = eventsByRun[runId].orEmpty()
        val currentSequence = existing.lastOrNull()?.sequence ?: 0
        if (currentSequence != expectedSequence) {
            return@withLock failure(
                EventStoreErrorCode.SEQUENCE_CONFLICT,
                "Expected sequence $expectedSequence, current sequence is $currentSequence",
            )
        }

        events.forEachIndexed { index, event ->
            if (event.runId != runId) {
                return@withLock failure(EventStoreErrorCode.RUN_MISMATCH, "Event run does not match append target")
            }
            val requiredSequence = expectedSequence + index + 1
            if (event.sequence != requiredSequence) {
                return@withLock failure(
                    EventStoreErrorCode.INVALID_EVENT_SEQUENCE,
                    "Event sequence ${event.sequence} is not the required sequence $requiredSequence",
                )
            }
        }

        val updated = existing + events
        eventsByRun[runId] = updated
        EventAppendResult.Success(updated.last().sequence)
    }

    override suspend fun read(
        runId: RunId,
        afterSequence: Long,
    ): List<EventEnvelope> = mutex.withLock {
        eventsByRun[runId].orEmpty().filter { it.sequence > afterSequence }.sortedBy { it.sequence }
    }

    private fun failure(
        code: EventStoreErrorCode,
        message: String,
    ): EventAppendResult.Failure = EventAppendResult.Failure(EventStoreError(code, message))
}
