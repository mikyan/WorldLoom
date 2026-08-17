package io.worldloom.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReplayTest {
    @Test
    fun replayProducesTheSameStateAsLiveReduction() {
        val world = testWorld(initialValue = 7)
        val runId = RunId("run.replay")
        val initialState = InitialGameStateFactory.create(world.definition, runId)
        val validation = assertIs<CommandValidationResult.Valid>(
            CommandValidator.validate(
                initialState,
                world.definition,
                developmentAuthorization(),
                adjustmentCommand(world, runId, delta = -2),
            ),
        )
        val event = WorldEngine.handle(validation.command, EventId("event.replay.1")).single()
        val liveState = assertIs<StateReductionResult.Success>(
            StateReducer.reduce(initialState, world.definition, event),
        ).state

        val replayedState = assertIs<ReplayResult.Success>(
            EventReplayer.replay(initialState, world.definition, listOf(event)),
        ).state

        assertEquals(liveState, replayedState)
        assertEquals(1, replayedState.lastSequence)
    }

    @Test
    fun reducerRejectsTamperedEventArithmetic() {
        val world = testWorld(initialValue = 7)
        val runId = RunId("run.tampered")
        val initialState = InitialGameStateFactory.create(world.definition, runId)
        val event = EventEnvelope(
            schemaVersion = CURRENT_EVENT_SCHEMA_VERSION,
            eventId = EventId("event.tampered.1"),
            runId = runId,
            sequence = 1,
            causationId = CommandId("command.tampered.1"),
            correlationId = "correlation.tampered.1",
            payload = NumericComponentAdjustedEvent(
                entityId = world.entityId,
                componentId = world.componentId,
                fieldId = world.fieldId,
                previousValue = 7,
                delta = -1,
                newValue = 2,
            ),
        )

        val failure = assertIs<StateReductionResult.Failure>(
            StateReducer.reduce(initialState, world.definition, event),
        )

        assertEquals(StateReductionErrorCode.INVALID_EVENT_ARITHMETIC, failure.error.code)
    }
}
