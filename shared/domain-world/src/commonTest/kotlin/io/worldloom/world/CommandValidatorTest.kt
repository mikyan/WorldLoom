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

    @Test
    fun remotePrivateDialogueRequiresAnAuthorizedCommunicationMethod() {
        val world = testWorld(namespace = "radio")
        val runId = RunId("run.radio")
        val sceneId = io.worldloom.definition.DefinitionId("radio.scene.room")
        val npcId = io.worldloom.definition.DefinitionId("radio.npc.guide")
        val methodId = io.worldloom.definition.DefinitionId("radio.communication.handset")
        val actorId = ActorId("system.player")
        val state = InitialGameStateFactory.create(world.definition, runId).copy(
            lifecycle = RunLifecycle.ACTIVE,
            playerEntityId = world.entityId,
            currentSceneId = sceneId,
            sceneParticipantIds = emptySet(),
        )
        val command = CommandEnvelope(
            CURRENT_COMMAND_SCHEMA_VERSION,
            CommandId("command.radio.1"),
            runId,
            actorId,
            0,
            payload = AddressNpcCommand(
                targetNpcId = npcId,
                targetEntityId = world.entityId,
                sceneId = sceneId,
                content = "收到吗？",
                idempotencyKey = "radio.turn.1",
                audience = NpcDialogueAudience.PRIVATE,
                communicationMethodId = methodId,
            ),
        )
        val authorization = CommandAuthorization(actorId, setOf(CommandPermission.ADDRESS_NPC))
        val policy = NpcAddressCommandPolicy(npcId, world.entityId, sceneId, setOf(methodId))

        val valid = assertIs<CommandValidationResult.Valid>(
            CommandValidator.validate(state, world.definition, authorization, command, npcAddressPolicy = policy),
        )
        val event = WorldEngine.handle(valid.command, EventId("event.radio.1")).single()
        assertEquals(NpcDialogueAudience.PRIVATE, assertIs<NpcAddressedEvent>(event.payload).audience)
        assertIs<StateReductionResult.Success>(StateReducer.reduce(state, world.definition, event))

        val payload = assertIs<AddressNpcCommand>(command.payload)
        val group = command.copy(
            payload = payload.copy(audience = NpcDialogueAudience.NEARBY_GROUP),
        )
        assertIs<CommandValidationResult.Invalid>(
            CommandValidator.validate(state, world.definition, authorization, group, npcAddressPolicy = policy),
        )
        val unknownMethod = command.copy(
            payload = payload.copy(
                communicationMethodId = io.worldloom.definition.DefinitionId("radio.communication.unknown"),
            ),
        )
        assertIs<CommandValidationResult.Invalid>(
            CommandValidator.validate(state, world.definition, authorization, unknownMethod, npcAddressPolicy = policy),
        )
    }

    @Test
    fun npcPresenceChangesAreReplayableAndRejectNoOps() {
        val world = testWorld(namespace = "presence")
        val runId = RunId("run.presence")
        val sceneId = io.worldloom.definition.DefinitionId("presence.scene.room")
        val npcId = io.worldloom.definition.DefinitionId("presence.npc.guide")
        val actorId = ActorId("worldloom.actor.gm")
        val state = InitialGameStateFactory.create(world.definition, runId).copy(
            lifecycle = RunLifecycle.ACTIVE,
            playerEntityId = world.entityId,
            currentSceneId = sceneId,
        )
        val policy = NpcPresenceCommandPolicy(npcId, world.entityId, sceneId)
        val authorization = CommandAuthorization(actorId, setOf(CommandPermission.MANAGE_NPC_PRESENCE))
        val command = CommandEnvelope(
            CURRENT_COMMAND_SCHEMA_VERSION,
            CommandId("command.presence.1"),
            runId,
            actorId,
            0,
            payload = SetNpcPresenceCommand(npcId = npcId, entityId = world.entityId, sceneId = sceneId, present = true),
        )

        val valid = assertIs<CommandValidationResult.Valid>(
            CommandValidator.validate(state, world.definition, authorization, command, npcPresencePolicy = policy),
        )
        val event = WorldEngine.handle(valid.command, EventId("event.presence.1")).single()
        val arrived = assertIs<StateReductionResult.Success>(StateReducer.reduce(state, world.definition, event)).state
        assertEquals(setOf(world.entityId), arrived.sceneParticipantIds)
        assertIs<CommandValidationResult.Invalid>(
            CommandValidator.validate(
                arrived,
                world.definition,
                authorization,
                command.copy(expectedSequence = arrived.lastSequence),
                npcPresencePolicy = policy,
            ),
        )
    }
}
