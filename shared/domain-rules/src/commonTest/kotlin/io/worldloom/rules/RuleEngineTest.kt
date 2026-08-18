package io.worldloom.rules

import io.worldloom.definition.CheckOutcomeDefinition
import io.worldloom.definition.CheckProfileDefinition
import io.worldloom.definition.CheckResolutionMode
import io.worldloom.definition.CURRENT_WORLD_DEFINITION_SCHEMA_VERSION
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.DiceExpression
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
import io.worldloom.world.EventId
import io.worldloom.world.EventReducerChain
import io.worldloom.world.EventReplayer
import io.worldloom.world.GameState
import io.worldloom.world.InitialGameStateFactory
import io.worldloom.world.ReplayResult
import io.worldloom.world.RunId
import io.worldloom.world.StateReducer
import io.worldloom.world.StateReductionErrorCode
import io.worldloom.world.StateReductionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RuleEngineTest {
    @Test
    fun randomCheckRecordsDiceAndReplayNeverCallsRandomServiceAgain() {
        val fixture = fixture(CheckResolutionMode.RANDOM)
        val random = CountingRandomService(listOf(3, 4))
        val event = resolve(fixture, random)

        val payload = assertIs<CheckResolvedEvent>(event.payload)
        assertEquals(listOf(3, 4), payload.record.randomRecord?.results)
        assertEquals(7, payload.record.total)
        assertEquals(DefinitionId("test.outcome.cost"), payload.record.outcomeId)
        assertEquals(1, random.calls)

        val reducer = EventReducerChain(listOf(StateReducer, CheckEventReducer))
        val live = assertIs<StateReductionResult.Success>(
            reducer.reduce(fixture.state, fixture.definition, event),
        ).state
        val replayed = assertIs<ReplayResult.Success>(
            EventReplayer.replay(fixture.state, fixture.definition, listOf(event), reducer),
        ).state

        assertEquals(live, replayed)
        assertEquals(1, random.calls)
    }

    @Test
    fun deterministicCheckDoesNotInvokeRandomService() {
        val fixture = fixture(CheckResolutionMode.DETERMINISTIC)
        val random = CountingRandomService(emptyList())

        val event = resolve(fixture, random)
        val record = assertIs<CheckResolvedEvent>(event.payload).record

        assertEquals(8, record.total)
        assertEquals(null, record.randomRecord)
        assertEquals(0, random.calls)
    }

    @Test
    fun reducerRejectsTamperedRandomFacts() {
        val fixture = fixture(CheckResolutionMode.RANDOM)
        val event = resolve(fixture, CountingRandomService(listOf(3, 4)))
        val payload = assertIs<CheckResolvedEvent>(event.payload)
        val tampered = event.copy(payload = payload.copy(record = payload.record.copy(total = 12)))

        val result = assertIs<StateReductionResult.Failure>(
            CheckEventReducer.reduce(fixture.state, fixture.definition, tampered),
        )

        assertEquals(StateReductionErrorCode.INVALID_EVENT_ARITHMETIC, result.error.code)
    }

    private fun resolve(
        fixture: Fixture,
        randomService: RandomService,
    ): io.worldloom.world.EventEnvelope {
        val actorId = ActorId("actor.test")
        val envelope = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = CommandId("command.test"),
            runId = fixture.state.runId,
            actorId = actorId,
            expectedSequence = fixture.state.lastSequence,
            payload = ResolveCheckCommand(fixture.profileId),
        )
        val validated = assertIs<CheckCommandValidationResult.Valid>(
            CheckCommandValidator.validate(
                fixture.state,
                fixture.definition,
                fixture.modules,
                CommandAuthorization(actorId, setOf(CommandPermission.RESOLVE_CHECK)),
                envelope,
            ),
        ).command
        return assertIs<CheckResolutionResult.Success>(
            RuleEngine.resolve(
                validated,
                EventId("event.test"),
                CheckId("check.test"),
                RandomRecordId("random.test"),
                randomService,
            ),
        ).event
    }

    private fun fixture(mode: CheckResolutionMode): Fixture {
        val worldId = DefinitionId("contract.test")
        val profileId = DefinitionId("test.check.primary")
        val definitionSource = WorldDefinition(
            schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
            id = worldId,
            title = "test",
            components = emptyList(),
            initialEntities = emptyList(),
            checkProfiles = listOf(
                CheckProfileDefinition(
                    id = profileId,
                    label = "test check",
                    mode = mode,
                    dice = if (mode == CheckResolutionMode.RANDOM) DiceExpression(2, 6) else null,
                    baseValue = if (mode == CheckResolutionMode.DETERMINISTIC) 8 else 0,
                    outcomes = listOf(
                        CheckOutcomeDefinition(DefinitionId("test.outcome.success"), "success", 10),
                        CheckOutcomeDefinition(DefinitionId("test.outcome.cost"), "cost", 7),
                        CheckOutcomeDefinition(DefinitionId("test.outcome.failure"), "failure", -1000),
                    ),
                ),
            ),
            presentation = emptyList(),
        )
        val definition = assertIs<DefinitionValidationResult.Valid>(
            WorldDefinitionValidator.validate(definitionSource),
        ).definition
        val checkModuleId = when (mode) {
            CheckResolutionMode.RANDOM -> DefinitionId("worldloom.rules.random-check")
            CheckResolutionMode.DETERMINISTIC -> DefinitionId("worldloom.rules.deterministic-check")
        }
        val manifest = WorldManifest(
            schemaVersion = CURRENT_WORLD_MANIFEST_SCHEMA_VERSION,
            runtimeApiVersion = CURRENT_RULE_MODULE_API_VERSION,
            worldId = worldId,
            worldDefinitionPath = "world.json",
            modules = listOf(
                WorldModuleSelection(
                    DefinitionId("worldloom.core.numeric-state"),
                    ModuleVersion(1, 0, 0),
                    mapOf(
                        DefinitionId("worldloom.parameter.direct-adjustment") to
                            io.worldloom.definition.BooleanValue(true),
                    ),
                ),
                WorldModuleSelection(checkModuleId, ModuleVersion(1, 0, 0)),
            ),
        )
        val modules = assertIs<ModuleRegistrationResult.Success>(
            StandardRuleModules.registry().register(manifest),
        ).modules
        val state = InitialGameStateFactory.create(definition, RunId("run.test"))
        return Fixture(profileId, definition, modules, state)
    }

    private data class Fixture(
        val profileId: DefinitionId,
        val definition: io.worldloom.definition.ValidatedWorldDefinition,
        val modules: io.worldloom.rules.module.api.RegisteredWorldModules,
        val state: GameState,
    )

    private class CountingRandomService(private val results: List<Int>) : RandomService {
        var calls: Int = 0
            private set

        override fun resolve(request: RandomRequest, recordId: RandomRecordId): RandomServiceResult {
            calls++
            return RandomServiceResult.Success(
                RandomRecord(recordId, RANDOM_ALGORITHM_VERSION, request, results),
            )
        }
    }
}
