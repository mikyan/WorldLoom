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

    @Test
    fun validatesCurrentSceneNpcAddressAndReplaysItsPublicEvent() {
        val world = testWorld(namespace = "dialogue")
        val runId = RunId("run.dialogue")
        val sceneId = io.worldloom.definition.DefinitionId("dialogue.scene.room")
        val npcId = io.worldloom.definition.DefinitionId("dialogue.npc.guide")
        val state = InitialGameStateFactory.create(world.definition, runId).copy(
            lifecycle = RunLifecycle.ACTIVE,
            playerEntityId = world.entityId,
            currentSceneId = sceneId,
            sceneParticipantIds = setOf(world.entityId),
        )
        val actorId = ActorId("system.player")
        val envelope = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = CommandId("command.dialogue.1"),
            runId = runId,
            actorId = actorId,
            expectedSequence = 0,
            payload = AddressNpcCommand(
                targetNpcId = npcId,
                targetEntityId = world.entityId,
                sceneId = sceneId,
                content = "  Tell me what happened.  ",
                idempotencyKey = "dialogue.turn.1",
            ),
        )

        val valid = assertIs<CommandValidationResult.Valid>(
            CommandValidator.validate(
                state,
                world.definition,
                CommandAuthorization(actorId, setOf(CommandPermission.ADDRESS_NPC)),
                envelope,
                npcAddressPolicy = NpcAddressCommandPolicy(npcId, world.entityId, sceneId),
            ),
        )
        val event = WorldEngine.handle(valid.command, EventId("event.dialogue.1")).single()
        val payload = assertIs<NpcAddressedEvent>(event.payload)
        assertEquals("Tell me what happened.", payload.content)
        assertEquals(
            1,
            assertIs<StateReductionResult.Success>(StateReducer.reduce(state, world.definition, event)).state.lastSequence,
        )

        val outside = assertIs<CommandValidationResult.Invalid>(
            CommandValidator.validate(
                state.copy(sceneParticipantIds = emptySet()),
                world.definition,
                CommandAuthorization(actorId, setOf(CommandPermission.ADDRESS_NPC)),
                envelope,
                npcAddressPolicy = NpcAddressCommandPolicy(npcId, world.entityId, sceneId),
            ),
        )
        assertEquals(CommandValidationErrorCode.NPC_ADDRESS_MISMATCH, outside.error.code)
        val tooLong = envelope.copy(
            payload = (envelope.payload as AddressNpcCommand).copy(content = "x".repeat(501)),
        )
        assertEquals(
            CommandValidationErrorCode.NPC_ADDRESS_MISMATCH,
            assertIs<CommandValidationResult.Invalid>(
                CommandValidator.validate(
                    state,
                    world.definition,
                    CommandAuthorization(actorId, setOf(CommandPermission.ADDRESS_NPC)),
                    tooLong,
                    npcAddressPolicy = NpcAddressCommandPolicy(npcId, world.entityId, sceneId),
                ),
            ).error.code,
        )
    }
}
