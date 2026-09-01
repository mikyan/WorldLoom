package io.worldloom.rules

import io.worldloom.definition.CURRENT_WORLD_DEFINITION_SCHEMA_VERSION
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.WorldDefinitionValidator
import io.worldloom.world.ActorId
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandEnvelope
import io.worldloom.world.CommandId
import io.worldloom.world.CommandPermission
import io.worldloom.world.CURRENT_COMMAND_SCHEMA_VERSION
import io.worldloom.world.EventId
import io.worldloom.world.EventReplayer
import io.worldloom.world.InitialGameStateFactory
import io.worldloom.world.ReplayResult
import io.worldloom.world.RunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SceneExplorationTest {
    @Test
    fun authorizedRevealReplaysDeterministicallyAndRejectsRediscovery() {
        val definition = assertIs<DefinitionValidationResult.Valid>(
            WorldDefinitionValidator.validate(
                WorldDefinition(
                    schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
                    id = id("test.world.exploration"),
                    title = "Exploration Test",
                    components = emptyList(),
                    initialEntities = emptyList(),
                    presentation = emptyList(),
                    checkProfiles = emptyList(),
                    presentationChecks = emptyList(),
                ),
            ),
        ).definition
        val state = ExplorationState.initialize(InitialGameStateFactory.create(definition, RunId("run.exploration")))
        val actor = ActorId("actor.application")
        val change = ExplorationKnowledgeChange(
            ExplorationKnowledgeKind.NODE,
            id("test.place.bridge"),
            ExplorationKnowledgeLevel.DISCOVERED,
        )
        val envelope = CommandEnvelope(
            CURRENT_COMMAND_SCHEMA_VERSION,
            CommandId("command.reveal.1"),
            state.runId,
            actor,
            state.lastSequence,
            payload = RevealExplorationKnowledgeCommand(causeId = id("test.cause.observe"), changes = listOf(change)),
        )
        val authorization = CommandAuthorization(actor, setOf(CommandPermission.REVEAL_EXPLORATION_KNOWLEDGE))
        val policy = ExplorationRevealPolicy(id("test.cause.observe"), setOf(change))
        val validated = assertIs<ExplorationCommandValidationResult.Valid>(
            ExplorationCommandValidator.validate(state, authorization, envelope, policy),
        ).command
        val event = ExplorationRuleEngine.handle(validated, EventId("event.reveal.1"))
        val replayed = assertIs<ReplayResult.Success>(
            EventReplayer.replay(state, definition, listOf(event), ExplorationEventReducer),
        ).state

        assertEquals(ExplorationKnowledgeLevel.DISCOVERED, ExplorationState.level(replayed, change.id))
        val duplicate = envelope.copy(commandId = CommandId("command.reveal.2"), expectedSequence = replayed.lastSequence)
        assertIs<ExplorationCommandValidationResult.Invalid>(
            ExplorationCommandValidator.validate(replayed, authorization, duplicate, policy),
        )
    }

    @Test
    fun revealRequiresExplicitPermissionAndExactWorldPolicy() {
        val definition = assertIs<DefinitionValidationResult.Valid>(
            WorldDefinitionValidator.validate(
                WorldDefinition(
                    schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
                    id = id("test.world.permission"),
                    title = "Permission Test",
                    components = emptyList(), initialEntities = emptyList(),
                    presentation = emptyList(), checkProfiles = emptyList(), presentationChecks = emptyList(),
                ),
            ),
        ).definition
        val state = ExplorationState.initialize(InitialGameStateFactory.create(definition, RunId("run.permission")))
        val actor = ActorId("actor.untrusted")
        val change = ExplorationKnowledgeChange(ExplorationKnowledgeKind.AFFORDANCE, id("test.clue.console"), ExplorationKnowledgeLevel.DISCOVERED)
        val envelope = CommandEnvelope(
            CURRENT_COMMAND_SCHEMA_VERSION,
            CommandId("command.permission"),
            state.runId,
            actor,
            0,
            payload = RevealExplorationKnowledgeCommand(causeId = id("test.cause.fake"), changes = listOf(change)),
        )
        assertIs<ExplorationCommandValidationResult.Invalid>(
            ExplorationCommandValidator.validate(
                state,
                CommandAuthorization(actor, emptySet()),
                envelope,
                ExplorationRevealPolicy(id("test.cause.fake"), setOf(change)),
            ),
        )
        assertIs<ExplorationCommandValidationResult.Invalid>(
            ExplorationCommandValidator.validate(
                state,
                CommandAuthorization(actor, setOf(CommandPermission.REVEAL_EXPLORATION_KNOWLEDGE)),
                envelope,
                ExplorationRevealPolicy(id("test.cause.real"), emptySet()),
            ),
        )

        val unauthorizedKnowledge = change.copy(id = id("test.clue.not-authored"))
        val unauthorizedEnvelope = envelope.copy(
            commandId = CommandId("command.unknown-knowledge"),
            payload = RevealExplorationKnowledgeCommand(
                causeId = id("test.cause.fake"),
                changes = listOf(unauthorizedKnowledge),
            ),
        )
        assertIs<ExplorationCommandValidationResult.Invalid>(
            ExplorationCommandValidator.validate(
                state,
                CommandAuthorization(actor, setOf(CommandPermission.REVEAL_EXPLORATION_KNOWLEDGE)),
                unauthorizedEnvelope,
                ExplorationRevealPolicy(id("test.cause.fake"), setOf(change)),
            ),
        )

        val duplicateEnvelope = envelope.copy(
            commandId = CommandId("command.duplicate-knowledge"),
            payload = RevealExplorationKnowledgeCommand(
                causeId = id("test.cause.fake"),
                changes = listOf(change, change.copy(level = ExplorationKnowledgeLevel.BLOCKED)),
            ),
        )
        assertIs<ExplorationCommandValidationResult.Invalid>(
            ExplorationCommandValidator.validate(
                state,
                CommandAuthorization(actor, setOf(CommandPermission.REVEAL_EXPLORATION_KNOWLEDGE)),
                duplicateEnvelope,
                ExplorationRevealPolicy(
                    id("test.cause.fake"),
                    setOf(change, change.copy(level = ExplorationKnowledgeLevel.BLOCKED)),
                ),
            ),
        )
    }

    private fun id(value: String) = DefinitionId(value)
}
