package io.worldloom.persistence

import io.worldloom.definition.DefinitionId
import io.worldloom.rules.CheckId
import io.worldloom.rules.CheckRecord
import io.worldloom.rules.CheckResolvedEvent
import io.worldloom.rules.DiceRandomRequest
import io.worldloom.rules.RANDOM_ALGORITHM_VERSION
import io.worldloom.rules.RandomRecord
import io.worldloom.rules.RandomRecordId
import io.worldloom.world.CommandId
import io.worldloom.world.CURRENT_EVENT_SCHEMA_VERSION
import io.worldloom.world.EventEnvelope
import io.worldloom.world.EventId
import io.worldloom.world.GameState
import io.worldloom.world.RunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PersistenceCodecTest {
    @Test
    fun roundTripsPolymorphicCheckEventAndGenericState() {
        val runId = RunId("run.codec")
        val event = EventEnvelope(
            schemaVersion = CURRENT_EVENT_SCHEMA_VERSION,
            eventId = EventId("event.codec"),
            runId = runId,
            sequence = 1,
            causationId = CommandId("command.codec"),
            correlationId = "correlation.codec",
            payload = CheckResolvedEvent(
                CheckRecord(
                    checkId = CheckId("check.codec"),
                    profileId = DefinitionId("test.check.primary"),
                    baseValue = 0,
                    modifier = 0,
                    randomRecord = RandomRecord(
                        RandomRecordId("random.codec"),
                        RANDOM_ALGORITHM_VERSION,
                        DiceRandomRequest(2, 6),
                        listOf(3, 4),
                    ),
                    total = 7,
                    outcomeId = DefinitionId("test.outcome.cost"),
                ),
            ),
        )
        val state = GameState(
            runId,
            DefinitionId("contract.codec"),
            lastSequence = 1,
            entities = emptyMap(),
            variables = emptyMap(),
            moduleStates = emptyMap(),
        )

        assertEquals(
            event,
            assertIs<PersistenceDecodeResult.Success<EventEnvelope>>(
                PersistenceCodec.decodeEvent(PersistenceCodec.encodeEvent(event)),
            ).value,
        )
        assertEquals(
            state,
            assertIs<PersistenceDecodeResult.Success<GameState>>(
                PersistenceCodec.decodeState(PersistenceCodec.encodeState(state)),
            ).value,
        )
    }
}
