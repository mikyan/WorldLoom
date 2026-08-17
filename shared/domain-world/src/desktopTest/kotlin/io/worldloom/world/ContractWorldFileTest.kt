package io.worldloom.world

import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.WorldDefinitionCodec
import io.worldloom.definition.WorldDefinitionDecodeResult
import io.worldloom.definition.WorldDefinitionValidator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ContractWorldFileTest {
    @Test
    fun bothTopicsUseTheSameAuthoritativePipeline() = runTest {
        val cases = listOf(
            ContractCase("war-survival/world.json", expectedLabel = "身体状况", expectedInitial = 7, expectedFinal = 6),
            ContractCase("station-ai/world.json", expectedLabel = "能源储备", expectedInitial = 80, expectedFinal = 70),
        )

        cases.forEachIndexed { index, case ->
            val source = assertNotNull(javaClass.classLoader.getResource(case.resourcePath)).readText()
            val decoded = assertIs<WorldDefinitionDecodeResult.Success>(WorldDefinitionCodec.decode(source)).definition
            val definition = assertIs<DefinitionValidationResult.Valid>(
                WorldDefinitionValidator.validate(decoded),
            ).definition
            val binding = definition.source.presentation.single()
            val runId = RunId("run.contract.$index")
            val initialState = InitialGameStateFactory.create(definition, runId)
            val entityId = EntityId(binding.entityId)
            val current = initialState.entities.getValue(entityId)
                .components.getValue(binding.componentId)
                .fields.getValue(binding.fieldId)
            assertEquals(case.expectedInitial, assertIs<IntegerValue>(current).value)
            assertEquals(case.expectedLabel, binding.label)

            val envelope = CommandEnvelope(
                schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
                commandId = CommandId("command.contract.$index"),
                runId = runId,
                actorId = ActorId("system.development"),
                expectedSequence = 0,
                payload = AdjustNumericComponentCommand(
                    entityId = entityId,
                    componentId = binding.componentId,
                    fieldId = binding.fieldId,
                    delta = binding.adjustmentStep,
                ),
            )
            val validated = assertIs<CommandValidationResult.Valid>(
                CommandValidator.validate(initialState, definition, developmentAuthorization(), envelope),
            )
            val event = WorldEngine.handle(validated.command, EventId("event.contract.$index")).single()
            val store = InMemoryEventStore()
            assertIs<EventAppendResult.Success>(store.append(runId, 0, listOf(event)))
            val liveState = assertIs<StateReductionResult.Success>(
                StateReducer.reduce(initialState, definition, event),
            ).state
            val replayState = assertIs<ReplayResult.Success>(
                EventReplayer.replay(initialState, definition, store.read(runId)),
            ).state
            val finalValue = liveState.entities.getValue(entityId)
                .components.getValue(binding.componentId)
                .fields.getValue(binding.fieldId)

            assertEquals(case.expectedFinal, assertIs<IntegerValue>(finalValue).value)
            assertEquals(liveState, replayState)
        }
    }

    private data class ContractCase(
        val resourcePath: String,
        val expectedLabel: String,
        val expectedInitial: Long,
        val expectedFinal: Long,
    )
}
