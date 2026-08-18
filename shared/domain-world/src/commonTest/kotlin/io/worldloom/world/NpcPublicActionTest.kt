package io.worldloom.world

import io.worldloom.definition.DefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NpcPublicActionTest {
    @Test
    fun publicNpcActionRequiresPinnedIdentitySceneAndWhitelistThenReplaysAsFact() {
        val world = testWorld("npc-action")
        val runId = RunId("run.npc-action")
        val sceneId = DefinitionId("npc-action.scene")
        val actorId = ActorId("actor.npc")
        val state = InitialGameStateFactory.create(world.definition, runId).copy(
            lifecycle = RunLifecycle.ACTIVE,
            playerEntityId = world.entityId,
            currentSceneId = sceneId,
            sceneParticipantIds = setOf(world.entityId),
        )
        val authorization = CommandAuthorization(actorId, setOf(CommandPermission.PUBLISH_NPC_ACTION))
        val policy = NpcPublicActionCommandPolicy(
            entityId = world.entityId,
            sceneId = sceneId,
            allowedActionIds = setOf(DefinitionId("npc-action.observe")),
            canSpeak = true,
        )
        val envelope = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = CommandId("command.npc-action"),
            runId = runId,
            actorId = actorId,
            expectedSequence = 0,
            payload = PublishNpcActionCommand(
                entityId = world.entityId,
                sceneId = sceneId,
                kind = NpcPublicActionKind.ACTION,
                actionId = DefinitionId("npc-action.observe"),
                content = "  NPC examines the doorway.  ",
            ),
        )

        val validated = assertIs<CommandValidationResult.Valid>(
            CommandValidator.validate(
                state,
                world.definition,
                authorization,
                envelope,
                npcPublicActionPolicy = policy,
            ),
        ).command
        val event = WorldEngine.handle(validated, EventId("event.npc-action")).single()
        val payload = assertIs<NpcPublicActionPublishedEvent>(event.payload)
        assertEquals("NPC examines the doorway.", payload.content)
        assertEquals(1, assertIs<StateReductionResult.Success>(StateReducer.reduce(state, world.definition, event)).state.lastSequence)

        val rejected = assertIs<CommandValidationResult.Invalid>(
            CommandValidator.validate(
                state,
                world.definition,
                authorization,
                envelope.copy(
                    payload = (envelope.payload as PublishNpcActionCommand).copy(
                        actionId = DefinitionId("npc-action.forbidden"),
                    ),
                ),
                npcPublicActionPolicy = policy,
            ),
        )
        assertEquals(CommandValidationErrorCode.NPC_ACTION_MISMATCH, rejected.error.code)
    }
}
