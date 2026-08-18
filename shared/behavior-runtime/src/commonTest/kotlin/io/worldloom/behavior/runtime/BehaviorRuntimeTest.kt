package io.worldloom.behavior.runtime

import io.worldloom.definition.ComponentDefinition
import io.worldloom.definition.ComponentSeed
import io.worldloom.definition.CURRENT_WORLD_DEFINITION_SCHEMA_VERSION
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionReferenceValue
import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.EntitySeed
import io.worldloom.definition.FieldDefinition
import io.worldloom.definition.FieldSeed
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.TextValue
import io.worldloom.definition.ValueType
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.WorldDefinitionValidator
import io.worldloom.world.ActorId
import io.worldloom.world.AdjustNumericComponentCommand
import io.worldloom.world.CommandId
import io.worldloom.world.CommandPermission
import io.worldloom.world.InitialGameStateFactory
import io.worldloom.world.RunId
import io.worldloom.world.ComponentInstance
import io.worldloom.rules.module.api.RegisteredWorldModules
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BehaviorRuntimeTest {
    @Test
    fun eachEffectReadsTheLatestStateWhileTheTriggerContextStaysFrozen() = runTest {
        val fixture = fixture()
        val componentValue = ComponentFieldExpression(
            entity = PathExpression("event.subject"),
            componentId = DefinitionId("test.status"),
            fieldId = DefinitionId("test.energy"),
        )
        val source = behavior().copy(
            effects = behavior().effects + behavior().effects.single().copy(
                arguments = behavior().effects.single().arguments + ("delta" to componentValue),
            ),
        )
        val validated = assertIs<BehaviorValidationResult.Valid>(
            BehaviorValidator.validate(source, fixture.definition, mapOf("event.subject" to ValueType.TEXT)),
        ).behavior
        var latest = fixture.state
        val deltas = mutableListOf<Long>()
        val runtime = BehaviorRuntime { submission ->
            val payload = assertIs<AdjustNumericComponentCommand>(submission.envelope.payload)
            deltas += payload.delta
            val entity = latest.entities.getValue(payload.entityId)
            val component = entity.components.getValue(payload.componentId)
            val current = (component.fields.getValue(payload.fieldId) as IntegerValue).value
            latest = latest.copy(
                lastSequence = latest.lastSequence + 1,
                entities = latest.entities + (
                    payload.entityId to entity.copy(
                        components = entity.components + (
                            payload.componentId to ComponentInstance(
                                payload.componentId,
                                component.fields + (payload.fieldId to IntegerValue(current + payload.delta)),
                            )
                        ),
                    )
                ),
            )
            BehaviorCommandSubmitResult.Accepted(latest.lastSequence)
        }

        assertIs<BehaviorExecutionResult.Applied>(
            runtime.execute(
                validated,
                BehaviorEventContext(
                    DefinitionId("worldloom.event.tick"),
                    "event.tick.latest",
                    mapOf("event.subject" to TextValue("player")),
                ),
                fixture.state,
                ActorId("system.behavior"),
                BehaviorCommandIdSource { _, index -> CommandId("command.latest.$index") },
                stateProvider = { latest },
            ),
        )
        assertEquals(listOf(-1L, 1L), deltas)
    }

    @Test
    fun worldRegistryRejectsCommandsAndTriggersThatWereNotEnabled() {
        val fixture = fixture()
        val result = assertIs<BehaviorValidationResult.Invalid>(
            BehaviorValidator.validate(
                behavior(),
                fixture.definition,
                mapOf("event.subject" to ValueType.TEXT),
                BehaviorCommandRegistry.forWorld(RegisteredWorldModules(emptyList())),
                allowedEventTypes = setOf(DefinitionId("worldloom.event.other")),
            ),
        )
        assertTrue(result.problems.any { it.code == BehaviorProblemCode.COMMAND_NOT_ALLOWED })
        assertTrue(result.problems.any { it.code == BehaviorProblemCode.TRIGGER_NOT_ALLOWED })
    }

    @Test
    fun validatedBehaviorSubmitsWhitelistedCommandThroughAuthoritativeSink() = runTest {
        val fixture = fixture()
        val behavior = behavior()
        val validated = assertIs<BehaviorValidationResult.Valid>(
            BehaviorValidator.validate(
                behavior,
                fixture.definition,
                pathTypes = mapOf("event.subject" to ValueType.TEXT),
            ),
        ).behavior
        val submissions = mutableListOf<BehaviorCommandSubmission>()
        val runtime = BehaviorRuntime { submission ->
            submissions += submission
            BehaviorCommandSubmitResult.Accepted(submission.envelope.expectedSequence + 1)
        }

        val result = runtime.execute(
            behavior = validated,
            event = BehaviorEventContext(
                eventType = DefinitionId("worldloom.event.tick"),
                sourceEventId = "event.tick.1",
                values = mapOf("event.subject" to TextValue("player")),
            ),
            state = fixture.state,
            actorId = ActorId("system.behavior"),
            commandIds = BehaviorCommandIdSource { id, index -> CommandId("command.${id.value}.$index") },
        )

        assertEquals(BehaviorExecutionResult.Applied(1, 1), result)
        val submission = submissions.single()
        assertEquals(CommandPermission.ADJUST_NUMERIC_COMPONENT, submission.requiredPermission)
        val payload = assertIs<AdjustNumericComponentCommand>(submission.envelope.payload)
        assertEquals(-1, payload.delta)
        assertEquals("event.tick.1", submission.envelope.correlationId)
    }

    @Test
    fun validatorRejectsUnknownCommandAndArgumentTypeMismatch() {
        val fixture = fixture()
        val unknown = behavior().copy(
            effects = listOf(BehaviorCommandEffect(DefinitionId("world.command.arbitrary-code"), emptyMap())),
        )
        val wrongType = behavior().copy(
            effects = behavior().effects.map { effect ->
                effect.copy(arguments = effect.arguments + ("delta" to ValueExpression(TextValue("minus one"))))
            },
        )

        val unknownProblems = assertIs<BehaviorValidationResult.Invalid>(
            BehaviorValidator.validate(unknown, fixture.definition, mapOf("event.subject" to ValueType.TEXT)),
        ).problems
        val typeProblems = assertIs<BehaviorValidationResult.Invalid>(
            BehaviorValidator.validate(wrongType, fixture.definition, mapOf("event.subject" to ValueType.TEXT)),
        ).problems

        assertTrue(unknownProblems.any { it.code == BehaviorProblemCode.COMMAND_NOT_ALLOWED })
        assertTrue(typeProblems.any { it.code == BehaviorProblemCode.TYPE_MISMATCH })
    }

    @Test
    fun canonicalJsonRoundTripPreservesTypedAst() {
        val encoded = BehaviorCodec.encode(behavior())
        val decoded = assertIs<BehaviorDecodeResult.Success>(BehaviorCodec.decode(encoded)).behavior
        assertEquals(behavior(), decoded)
        assertTrue(encoded.contains(BEHAVIOR_SCHEMA_V1))
    }

    private fun behavior() = BehaviorDefinition(
        schema = BEHAVIOR_SCHEMA_V1,
        id = DefinitionId("test.behavior.energy-drain"),
        trigger = BehaviorTrigger(DefinitionId("worldloom.event.tick")),
        condition = ComparisonExpression(
            ComparisonOperator.GT,
            ComponentFieldExpression(
                entity = PathExpression("event.subject"),
                componentId = DefinitionId("test.status"),
                fieldId = DefinitionId("test.energy"),
            ),
            ValueExpression(IntegerValue(0)),
        ),
        effects = listOf(
            BehaviorCommandEffect(
                commandId = DefinitionId("worldloom.command.adjust-numeric-component"),
                arguments = mapOf(
                    "entityId" to PathExpression("event.subject"),
                    "componentId" to ValueExpression(DefinitionReferenceValue(DefinitionId("test.status"))),
                    "fieldId" to ValueExpression(DefinitionReferenceValue(DefinitionId("test.energy"))),
                    "delta" to ValueExpression(IntegerValue(-1)),
                ),
            ),
        ),
    )

    private fun fixture(): Fixture {
        val componentId = DefinitionId("test.status")
        val fieldId = DefinitionId("test.energy")
        val definition = WorldDefinition(
            schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
            id = DefinitionId("test.behavior-world"),
            title = "Behavior World",
            components = listOf(
                ComponentDefinition(componentId, listOf(FieldDefinition(fieldId, ValueType.INTEGER, minInteger = 0))),
            ),
            initialEntities = listOf(
                EntitySeed("player", listOf(ComponentSeed(componentId, listOf(FieldSeed(fieldId, IntegerValue(2)))))),
            ),
            presentation = emptyList(),
        )
        val validated = assertIs<DefinitionValidationResult.Valid>(WorldDefinitionValidator.validate(definition)).definition
        return Fixture(validated, InitialGameStateFactory.create(validated, RunId("run.behavior")))
    }

    private data class Fixture(
        val definition: io.worldloom.definition.ValidatedWorldDefinition,
        val state: io.worldloom.world.GameState,
    )
}
