package io.worldloom.rules

import io.worldloom.definition.CheckResolutionMode
import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.WorldDefinitionCodec
import io.worldloom.definition.WorldDefinitionDecodeResult
import io.worldloom.definition.WorldDefinitionValidator
import io.worldloom.rules.module.api.WorldManifestCodec
import io.worldloom.rules.module.api.WorldManifestDecodeResult
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
import io.worldloom.world.InitialGameStateFactory
import io.worldloom.world.ReplayResult
import io.worldloom.world.RunId
import io.worldloom.world.StateReducer
import io.worldloom.world.StateReductionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ContractCheckTest {
    @Test
    fun bothContractWorldsResolveAndReplayThroughTheSameRulePipeline() {
        val cases = listOf(
            ContractCase("war-survival", CheckResolutionMode.RANDOM),
            ContractCase("station-ai", CheckResolutionMode.DETERMINISTIC),
        )
        val reducer = EventReducerChain(listOf(StateReducer, CheckEventReducer))

        cases.forEachIndexed { index, case ->
            val definitionSource = resource("${case.directory}/world.json")
            val manifestSource = resource("${case.directory}/manifest.json")
            val definition = assertIs<DefinitionValidationResult.Valid>(
                WorldDefinitionValidator.validate(
                    assertIs<WorldDefinitionDecodeResult.Success>(
                        WorldDefinitionCodec.decode(definitionSource),
                    ).definition,
                ),
            ).definition
            val manifest = assertIs<WorldManifestDecodeResult.Success>(
                WorldManifestCodec.decode(manifestSource),
            ).manifest
            val modules = assertIs<ModuleRegistrationResult.Success>(
                StandardRuleModules.registry().register(manifest),
            ).modules
            val profile = definition.source.checkProfiles.single()
            val state = InitialGameStateFactory.create(definition, RunId("run.contract-check.$index"))
            val actor = ActorId("actor.contract")
            val command = CommandEnvelope(
                schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
                commandId = CommandId("command.contract-check.$index"),
                runId = state.runId,
                actorId = actor,
                expectedSequence = 0,
                payload = ResolveCheckCommand(profile.id),
            )
            val validated = assertIs<CheckCommandValidationResult.Valid>(
                CheckCommandValidator.validate(
                    state,
                    definition,
                    modules,
                    CommandAuthorization(actor, setOf(CommandPermission.RESOLVE_CHECK)),
                    command,
                ),
            ).command
            val event = assertIs<CheckResolutionResult.Success>(
                RuleEngine.resolve(
                    validated,
                    EventId("event.contract-check.$index"),
                    CheckId("check.contract.$index"),
                    RandomRecordId("random.contract.$index"),
                    SeededRandomService(100L + index),
                ),
            ).event
            val record = assertIs<CheckResolvedEvent>(event.payload).record
            val live = assertIs<StateReductionResult.Success>(
                reducer.reduce(state, definition, event),
            ).state
            val replay = assertIs<ReplayResult.Success>(
                EventReplayer.replay(state, definition, listOf(event), reducer),
            ).state

            assertEquals(case.mode, profile.mode)
            assertEquals(live, replay)
            if (case.mode == CheckResolutionMode.RANDOM) {
                val randomRecord = assertNotNull(record.randomRecord)
                assertEquals(DiceRandomRequest(2, 6), randomRecord.request)
                assertEquals(2, randomRecord.results.size)
            } else {
                assertNull(record.randomRecord)
                assertEquals(8, record.total)
            }
        }
    }

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()

    private data class ContractCase(
        val directory: String,
        val mode: CheckResolutionMode,
    )
}
