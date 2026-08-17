package io.worldloom.world

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventStoreTest {
    @Test
    fun invalidBatchIsRejectedAtomically() = runTest {
        val runId = RunId("run.store")
        val store = InMemoryEventStore()
        val validEvent = event(runId, sequence = 1, eventId = "event.1")
        val invalidEvent = event(runId, sequence = 3, eventId = "event.3")

        val result = store.append(runId, expectedSequence = 0, events = listOf(validEvent, invalidEvent))

        assertIs<EventAppendResult.Failure>(result)
        assertTrue(store.read(runId).isEmpty())
    }

    @Test
    fun optimisticSequencePreventsConcurrentOverwrite() = runTest {
        val runId = RunId("run.conflict")
        val store = InMemoryEventStore()

        assertIs<EventAppendResult.Success>(
            store.append(runId, expectedSequence = 0, events = listOf(event(runId, 1, "event.1"))),
        )
        val conflict = assertIs<EventAppendResult.Failure>(
            store.append(runId, expectedSequence = 0, events = listOf(event(runId, 1, "event.duplicate"))),
        )

        assertEquals(EventStoreErrorCode.SEQUENCE_CONFLICT, conflict.error.code)
        assertEquals(listOf(EventId("event.1")), store.read(runId).map { it.eventId })
    }

    private fun event(
        runId: RunId,
        sequence: Long,
        eventId: String,
    ): EventEnvelope =
        EventEnvelope(
            schemaVersion = CURRENT_EVENT_SCHEMA_VERSION,
            eventId = EventId(eventId),
            runId = runId,
            sequence = sequence,
            causationId = CommandId("command.$sequence"),
            correlationId = "correlation.$sequence",
            payload = NumericComponentAdjustedEvent(
                entityId = EntityId("player"),
                componentId = io.worldloom.definition.DefinitionId("test.status"),
                fieldId = io.worldloom.definition.DefinitionId("test.energy"),
                previousValue = 5,
                delta = -1,
                newValue = 4,
            ),
        )
}
