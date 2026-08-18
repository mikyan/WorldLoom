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

    @Test
    fun knowledgeRevealUsesPinnedSummaryAndCannotBeRewordedByNpc() {
        val world = testWorld("npc-knowledge")
        val runId = RunId("run.npc-knowledge")
        val sceneId = DefinitionId("npc-knowledge.scene")
        val npcId = DefinitionId("npc-knowledge.guide")
        val knowledgeId = DefinitionId("npc-knowledge.guide.route")
        val actorId = ActorId("actor.npc")
        val state = InitialGameStateFactory.create(world.definition, runId).copy(
            lifecycle = RunLifecycle.ACTIVE,
            playerEntityId = world.entityId,
            currentSceneId = sceneId,
            sceneParticipantIds = setOf(world.entityId),
        )
        val summary = "The guide publicly confirms the eastern route is blocked."
        val envelope = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = CommandId("command.npc-knowledge"),
            runId = runId,
            actorId = actorId,
            expectedSequence = 0,
            payload = RevealNpcKnowledgeCommand(
                npcId = npcId,
                entityId = world.entityId,
                sceneId = sceneId,
                knowledgeId = knowledgeId,
                publicSummary = summary,
            ),
        )
        val authorization = CommandAuthorization(actorId, setOf(CommandPermission.REVEAL_NPC_KNOWLEDGE))
        val policy = NpcKnowledgeRevealCommandPolicy(npcId, world.entityId, sceneId, knowledgeId, summary)

        val validated = assertIs<CommandValidationResult.Valid>(
            CommandValidator.validate(
                state,
                world.definition,
                authorization,
                envelope,
                npcKnowledgeRevealPolicy = policy,
            ),
        ).command
        val event = WorldEngine.handle(validated, EventId("event.npc-knowledge")).single()
        assertEquals(summary, assertIs<NpcKnowledgeRevealedEvent>(event.payload).publicSummary)
        assertEquals(1, assertIs<StateReductionResult.Success>(StateReducer.reduce(state, world.definition, event)).state.lastSequence)

        val reworded = envelope.copy(
            payload = (envelope.payload as RevealNpcKnowledgeCommand).copy(publicSummary = "Model-authored secret"),
        )
        assertEquals(
            CommandValidationErrorCode.NPC_KNOWLEDGE_MISMATCH,
            assertIs<CommandValidationResult.Invalid>(
                CommandValidator.validate(
                    state,
                    world.definition,
                    authorization,
                    reworded,
                    npcKnowledgeRevealPolicy = policy,
                ),
            ).error.code,
        )
    }
}
