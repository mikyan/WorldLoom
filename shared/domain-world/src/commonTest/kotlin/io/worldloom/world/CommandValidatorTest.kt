package io.worldloom.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandValidatorTest {
    @Test
    fun validatesGenericNumericAdjustmentWithoutTopicKnowledge() {
        val world = testWorld(namespace = "station", initialValue = 80, maximum = 100)
        val runId = RunId("run.validation")
        val state = InitialGameStateFactory.create(world.definition, runId)

        val valid = assertIs<CommandValidationResult.Valid>(
            CommandValidator.validate(
                state,
                world.definition,
                developmentAuthorization(),
                adjustmentCommand(world, runId, delta = -10),
            ),
        )
        val adjustment = assertIs<ValidatedCommand.AdjustNumericComponent>(valid.command)

        assertEquals(80, adjustment.previousValue)
        assertEquals(70, adjustment.newValue)
    }

    @Test
    fun rejectsUnauthorizedAndOutOfRangeCommands() {
        val world = testWorld()
        val runId = RunId("run.rejection")
        val state = InitialGameStateFactory.create(world.definition, runId)
        val command = adjustmentCommand(world, runId, delta = -6)

        val unauthorized = assertIs<CommandValidationResult.Invalid>(
            CommandValidator.validate(
                state,
                world.definition,
                CommandAuthorization(command.actorId, emptySet()),
                command,
            ),
        )
        val outOfRange = assertIs<CommandValidationResult.Invalid>(
            CommandValidator.validate(state, world.definition, developmentAuthorization(), command),
        )

        assertEquals(CommandValidationErrorCode.PERMISSION_DENIED, unauthorized.error.code)
        assertEquals(CommandValidationErrorCode.INTEGER_OUT_OF_RANGE, outOfRange.error.code)
    }

    @Test
    fun rejectsSequenceConflictsBeforeDomainValidation() {
        val world = testWorld()
        val runId = RunId("run.sequence")
        val state = InitialGameStateFactory.create(world.definition, runId).copy(lastSequence = 2)

        val invalid = assertIs<CommandValidationResult.Invalid>(
            CommandValidator.validate(
                state,
                world.definition,
                developmentAuthorization(),
                adjustmentCommand(world, runId, delta = -1, expectedSequence = 1),
            ),
        )

        assertEquals(CommandValidationErrorCode.SEQUENCE_CONFLICT, invalid.error.code)
    }
}
