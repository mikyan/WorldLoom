package io.worldloom.world

import io.worldloom.definition.DefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CharacterCreationTest {
    @Test
    fun lifecycleAndCharacterBatchReplayToAnActiveRun() {
        val world = testWorld(namespace = "creation")
        val runId = RunId("run.character")
        val profileId = DefinitionId("creation.profile.player")
        val sceneId = DefinitionId("creation.scene.opening")
        val authorization = characterAuthorization()
        val created = InitialGameStateFactory.createForCharacterCreation(world.definition, runId, world.entityId)

        assertEquals(RunLifecycle.CREATED, created.lifecycle)
        assertNull(created.playerEntityId)
        assertEquals(false, world.entityId in created.entities)

        val lifecycleValidation = assertIs<CommandValidationResult.Valid>(
            CommandValidator.validate(
                created,
                world.definition,
                authorization,
                lifecycleCommand(runId),
            ),
        )
        val lifecycleEvent = WorldEngine.handle(
            lifecycleValidation.command,
            EventId("event.character.lifecycle"),
        ).single()
        val creating = assertIs<StateReductionResult.Success>(
            StateReducer.reduce(created, world.definition, lifecycleEvent),
        ).state

        val command = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = CommandId("command.character.confirm"),
            runId = runId,
            actorId = authorization.actorId,
            expectedSequence = creating.lastSequence,
            payload = CreatePlayerCharacterCommand(
                profileId = profileId,
                entity = world.definition.source.initialEntities.single(),
                initialSceneId = sceneId,
            ),
        )
        val validation = assertIs<CommandValidationResult.Valid>(
            CommandValidator.validate(
                creating,
                world.definition,
                authorization,
                command,
                CharacterCreationCommandPolicy(profileId, world.entityId, sceneId),
            ),
        )
        val eventIds = List(WorldEngine.requiredEventCount(validation.command)) { index ->
            EventId("event.character.${index + 1}")
        }
        val events = WorldEngine.handle(validation.command, eventIds)
        val replayed = assertIs<ReplayResult.Success>(
            EventReplayer.replay(created, world.definition, listOf(lifecycleEvent) + events),
        ).state

        assertEquals(4, events.size)
        assertEquals(RunLifecycle.ACTIVE, replayed.lifecycle)
        assertEquals(world.entityId, replayed.playerEntityId)
        assertEquals(sceneId, replayed.currentSceneId)
        assertEquals(5, replayed.lastSequence)
        assertEquals(
            world.definition.source.initialEntities.single().components.single().fields.single().value,
            replayed.entities.getValue(world.entityId).components.getValue(world.componentId).fields.getValue(world.fieldId),
        )
    }

    @Test
    fun rejectsDuplicateConfirmationAndIllegalLifecycleTransitions() {
        val world = testWorld(namespace = "guard")
        val runId = RunId("run.guard")
        val active = InitialGameStateFactory.create(world.definition, runId)
        val authorization = characterAuthorization()

        val illegal = assertIs<CommandValidationResult.Invalid>(
            CommandValidator.validate(
                active,
                world.definition,
                authorization,
                lifecycleCommand(runId, RunLifecycle.CHARACTER_CREATION),
            ),
        )
        val duplicate = assertIs<CommandValidationResult.Invalid>(
            CommandValidator.validate(
                active.copy(lifecycle = RunLifecycle.CHARACTER_CREATION, playerEntityId = world.entityId),
                world.definition,
                authorization,
                CommandEnvelope(
                    schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
                    commandId = CommandId("command.guard.confirm"),
                    runId = runId,
                    actorId = authorization.actorId,
                    expectedSequence = 0,
                    payload = CreatePlayerCharacterCommand(
                        profileId = DefinitionId("guard.profile.player"),
                        entity = world.definition.source.initialEntities.single(),
                        initialSceneId = DefinitionId("guard.scene.opening"),
                    ),
                ),
                CharacterCreationCommandPolicy(
                    DefinitionId("guard.profile.player"),
                    world.entityId,
                    DefinitionId("guard.scene.opening"),
                ),
            ),
        )

        assertEquals(CommandValidationErrorCode.RUN_LIFECYCLE_INVALID, illegal.error.code)
        assertEquals(CommandValidationErrorCode.ENTITY_ALREADY_EXISTS, duplicate.error.code)
    }

    private fun lifecycleCommand(
        runId: RunId,
        lifecycle: RunLifecycle = RunLifecycle.CHARACTER_CREATION,
    ): CommandEnvelope = CommandEnvelope(
        schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
        commandId = CommandId("command.lifecycle.$lifecycle"),
        runId = runId,
        actorId = ActorId("system.application"),
        expectedSequence = 0,
        payload = ChangeRunLifecycleCommand(lifecycle = lifecycle),
    )

    private fun characterAuthorization(): CommandAuthorization = CommandAuthorization(
        actorId = ActorId("system.application"),
        permissions = setOf(
            CommandPermission.MANAGE_RUN_LIFECYCLE,
            CommandPermission.CREATE_PLAYER_CHARACTER,
        ),
    )
}
