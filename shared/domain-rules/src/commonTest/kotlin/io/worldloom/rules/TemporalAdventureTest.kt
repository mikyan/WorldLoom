package io.worldloom.rules

import io.worldloom.definition.BooleanValue
import io.worldloom.definition.ComponentDefinition
import io.worldloom.definition.ComponentSeed
import io.worldloom.definition.CURRENT_WORLD_DEFINITION_SCHEMA_VERSION
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.EntitySeed
import io.worldloom.definition.FieldDefinition
import io.worldloom.definition.FieldSeed
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.ValueType
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.WorldDefinitionValidator
import io.worldloom.rules.module.api.CURRENT_RULE_MODULE_API_VERSION
import io.worldloom.rules.module.api.CURRENT_WORLD_MANIFEST_SCHEMA_VERSION
import io.worldloom.rules.module.api.ModuleVersion
import io.worldloom.rules.module.api.WorldManifest
import io.worldloom.rules.module.api.WorldModuleSelection
import io.worldloom.rules.module.registry.ModuleRegistrationResult
import io.worldloom.rules.module.registry.StandardRuleModules
import io.worldloom.world.ActorId
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandEnvelope
import io.worldloom.world.CommandId
import io.worldloom.world.CommandPermission
import io.worldloom.world.CURRENT_COMMAND_SCHEMA_VERSION
import io.worldloom.world.EntityId
import io.worldloom.world.EventId
import io.worldloom.world.EventReducerChain
import io.worldloom.world.EventReplayer
import io.worldloom.world.GameState
import io.worldloom.world.InitialGameStateFactory
import io.worldloom.world.ReplayResult
import io.worldloom.world.RunId
import io.worldloom.world.RunLifecycle
import io.worldloom.world.StateReducer
import io.worldloom.world.StateReductionErrorCode
import io.worldloom.world.StateReductionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TemporalAdventureTest {
    @Test
    fun blockedTravelStillAdvancesConfiguredTimeWithoutChangingScene() {
        val fixture = fixture()
        val envelope = envelope(
            fixture.state,
            TravelRouteCommand(
                routeId = id("test.route.blocked"),
                outcomeId = id("test.outcome.blocked"),
            ),
        )
        val validated = assertIs<TemporalCommandValidationResult.Valid>(
            TemporalCommandValidator.validate(
                fixture.state,
                fixture.definition,
                fixture.modules,
                authorization(CommandPermission.TRAVEL),
                envelope,
                fixture.temporal,
            ),
        ).command
        val events = TemporalRuleEngine.handle(
            validated,
            List(TemporalRuleEngine.requiredEventCount(validated)) { EventId("event.blocked.${it + 1}") },
        )
        val replayed = assertIs<ReplayResult.Success>(
            EventReplayer.replay(
                fixture.state,
                fixture.definition,
                events,
                EventReducerChain(listOf(StateReducer, TemporalEventReducer)),
            ),
        ).state

        assertEquals(80, TemporalState.minute(replayed, fixture.temporal))
        assertEquals(id("test.scene.start"), replayed.currentSceneId)
        assertEquals(false, events.mapNotNull { it.payload as? TravelCompletedEvent }.single().arrived)
    }

    @Test
    fun crossingScheduleProducesAuditableEventsAndNeverRefiresOnReplay() {
        val fixture = fixture()
        val envelope = envelope(
            fixture.state,
            AdvanceWorldTimeCommand(deltaMinutes = 20, reasonId = id("test.reason.wait")),
        )
        val validated = assertIs<TemporalCommandValidationResult.Valid>(
            TemporalCommandValidator.validate(
                fixture.state,
                fixture.definition,
                fixture.modules,
                authorization(CommandPermission.ADVANCE_WORLD_TIME),
                envelope,
                fixture.temporal,
            ),
        ).command
        val events = TemporalRuleEngine.handle(
            validated,
            List(TemporalRuleEngine.requiredEventCount(validated)) { EventId("event.test.${it + 1}") },
        )

        assertEquals(
            listOf("WorldTimeAdvancedEvent", "ScheduledTriggerFiredEvent", "NumericComponentAdjustedEvent"),
            events.map { it.payload::class.simpleName },
        )
        val reducer = EventReducerChain(listOf(StateReducer, TemporalEventReducer))
        val replayed = assertIs<ReplayResult.Success>(
            EventReplayer.replay(fixture.state, fixture.definition, events, reducer),
        ).state
        assertEquals(70, TemporalState.minute(replayed, fixture.temporal))
        assertEquals(
            3,
            (replayed.entities.getValue(EntityId("player"))
                .components.getValue(id("test.component.status"))
                .fields.getValue(id("test.field.value")) as IntegerValue).value,
        )

        val nextEnvelope = envelope(
            replayed,
            AdvanceWorldTimeCommand(deltaMinutes = 10, reasonId = id("test.reason.wait")),
            commandId = "command.test.2",
        )
        val next = assertIs<TemporalCommandValidationResult.Valid>(
            TemporalCommandValidator.validate(
                replayed,
                fixture.definition,
                fixture.modules,
                authorization(CommandPermission.ADVANCE_WORLD_TIME),
                nextEnvelope,
                fixture.temporal,
            ),
        ).command
        assertEquals(1, TemporalRuleEngine.requiredEventCount(next))
    }

    @Test
    fun reducerRejectsTamperedPreviousTimeAndValidatorRejectsMissingPermission() {
        val fixture = fixture()
        val event = io.worldloom.world.EventEnvelope(
            schemaVersion = io.worldloom.world.CURRENT_EVENT_SCHEMA_VERSION,
            eventId = EventId("event.tampered"),
            runId = fixture.state.runId,
            sequence = 1,
            causationId = CommandId("command.tampered"),
            correlationId = "command.tampered",
            payload = WorldTimeAdvancedEvent(
                previousMinute = 1,
                deltaMinutes = 10,
                minute = 11,
                reasonId = id("test.reason.wait"),
            ),
        )

        val reduction = assertIs<StateReductionResult.Failure>(
            TemporalEventReducer.reduce(fixture.state, fixture.definition, event),
        )
        assertEquals(StateReductionErrorCode.PREVIOUS_VALUE_MISMATCH, reduction.error.code)

        val validation = assertIs<TemporalCommandValidationResult.Invalid>(
            TemporalCommandValidator.validate(
                fixture.state,
                fixture.definition,
                fixture.modules,
                authorization(),
                envelope(
                    fixture.state,
                    AdvanceWorldTimeCommand(deltaMinutes = 10, reasonId = id("test.reason.wait")),
                ),
                fixture.temporal,
            ),
        )
        assertEquals(io.worldloom.world.CommandValidationErrorCode.PERMISSION_DENIED, validation.error.code)
    }

    private fun fixture(): Fixture {
        val componentId = id("test.component.status")
        val fieldId = id("test.field.value")
        val source = WorldDefinition(
            schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
            id = id("test.world.temporal"),
            title = "Temporal Test",
            components = listOf(
                ComponentDefinition(componentId, listOf(FieldDefinition(fieldId, ValueType.INTEGER, minInteger = 0, maxInteger = 10))),
            ),
            initialEntities = listOf(
                EntitySeed(
                    "player",
                    listOf(ComponentSeed(componentId, listOf(FieldSeed(fieldId, IntegerValue(5))))),
                ),
            ),
            presentation = emptyList(),
        )
        val definition = assertIs<DefinitionValidationResult.Valid>(WorldDefinitionValidator.validate(source)).definition
        val temporal = TemporalAdventureDefinition(
            initialMinute = 50,
            routes = listOf(
                TravelRouteDefinition(
                    id = id("test.route.blocked"),
                    label = "Blocked route",
                    fromSceneId = id("test.scene.start"),
                    toSceneId = id("test.scene.destination"),
                    durationMinutes = 30,
                    resolutions = listOf(
                        TravelResolutionDefinition(id("test.outcome.blocked"), arrives = false),
                    ),
                ),
            ),
            scheduledTriggers = listOf(
                ScheduledTriggerDefinition(
                    id("test.trigger.sixty"),
                    "Sixty",
                    60,
                    listOf(NumericEffectDefinition(EntityId("player"), componentId, fieldId, -2)),
                ),
            ),
        )
        val manifest = WorldManifest(
            schemaVersion = CURRENT_WORLD_MANIFEST_SCHEMA_VERSION,
            runtimeApiVersion = CURRENT_RULE_MODULE_API_VERSION,
            worldId = source.id,
            worldDefinitionPath = "world.json",
            modules = listOf(
                WorldModuleSelection(
                    id("worldloom.core.numeric-state"),
                    ModuleVersion(1, 0, 0),
                    mapOf(id("worldloom.parameter.direct-adjustment") to BooleanValue(true)),
                ),
                WorldModuleSelection(WORLD_TIME_MODULE_ID, ModuleVersion(1, 0, 0)),
                WorldModuleSelection(ACTIVITY_MODULE_ID, ModuleVersion(1, 0, 0)),
                WorldModuleSelection(TRAVEL_MODULE_ID, ModuleVersion(1, 0, 0)),
            ),
        )
        val modules = assertIs<ModuleRegistrationResult.Success>(StandardRuleModules.registry().register(manifest)).modules
        val state = TemporalState.initialize(
            InitialGameStateFactory.create(definition, RunId("run.temporal")).copy(
                lifecycle = RunLifecycle.ACTIVE,
                playerEntityId = EntityId("player"),
                currentSceneId = id("test.scene.start"),
            ),
            temporal,
        )
        return Fixture(definition, modules, temporal, state)
    }

    private fun envelope(
        state: GameState,
        payload: io.worldloom.world.GameCommandPayload,
        commandId: String = "command.test.1",
    ) = CommandEnvelope(
        schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
        commandId = CommandId(commandId),
        runId = state.runId,
        actorId = ActorId("actor.test"),
        expectedSequence = state.lastSequence,
        payload = payload,
    )

    private fun authorization(vararg permissions: CommandPermission) =
        CommandAuthorization(ActorId("actor.test"), permissions.toSet())

    private data class Fixture(
        val definition: io.worldloom.definition.ValidatedWorldDefinition,
        val modules: io.worldloom.rules.module.api.RegisteredWorldModules,
        val temporal: TemporalAdventureDefinition,
        val state: GameState,
    )

    private companion object {
        fun id(value: String) = DefinitionId(value)
    }
}
