package io.worldloom.world

import io.worldloom.definition.ComponentDefinition
import io.worldloom.definition.ComponentSeed
import io.worldloom.definition.CURRENT_WORLD_DEFINITION_SCHEMA_VERSION
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.EntitySeed
import io.worldloom.definition.FieldDefinition
import io.worldloom.definition.FieldSeed
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.PresentationFieldDefinition
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.definition.ValueType
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.WorldDefinitionValidator
import kotlin.test.assertIs

data class TestWorld(
    val definition: ValidatedWorldDefinition,
    val entityId: EntityId,
    val componentId: DefinitionId,
    val fieldId: DefinitionId,
)

fun testWorld(
    namespace: String = "test",
    initialValue: Long = 5,
    minimum: Long = 0,
    maximum: Long = 10,
): TestWorld {
    val componentId = DefinitionId("$namespace.status")
    val fieldId = DefinitionId("$namespace.energy")
    val entityId = EntityId("player")
    val source = WorldDefinition(
        schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
        id = DefinitionId("contract.$namespace"),
        title = "$namespace world",
        components = listOf(
            ComponentDefinition(
                id = componentId,
                fields = listOf(
                    FieldDefinition(
                        id = fieldId,
                        valueType = ValueType.INTEGER,
                        minInteger = minimum,
                        maxInteger = maximum,
                    ),
                ),
            ),
        ),
        initialEntities = listOf(
            EntitySeed(
                entityId = entityId.value,
                components = listOf(
                    ComponentSeed(
                        definitionId = componentId,
                        fields = listOf(FieldSeed(fieldId, IntegerValue(initialValue))),
                    ),
                ),
            ),
        ),
        presentation = listOf(
            PresentationFieldDefinition(
                id = DefinitionId("$namespace.presentation.primary"),
                entityId = entityId.value,
                componentId = componentId,
                fieldId = fieldId,
                label = "Value",
                adjustmentStep = -1,
            ),
        ),
    )
    val validated = assertIs<DefinitionValidationResult.Valid>(WorldDefinitionValidator.validate(source)).definition
    return TestWorld(validated, entityId, componentId, fieldId)
}

fun adjustmentCommand(
    world: TestWorld,
    runId: RunId,
    delta: Long,
    expectedSequence: Long = 0,
    actorId: ActorId = ActorId("system.development"),
): CommandEnvelope =
    CommandEnvelope(
        schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
        commandId = CommandId("command.$expectedSequence"),
        runId = runId,
        actorId = actorId,
        expectedSequence = expectedSequence,
        payload = AdjustNumericComponentCommand(
            entityId = world.entityId,
            componentId = world.componentId,
            fieldId = world.fieldId,
            delta = delta,
        ),
    )

fun developmentAuthorization(actorId: ActorId = ActorId("system.development")): CommandAuthorization =
    CommandAuthorization(actorId, setOf(CommandPermission.ADJUST_NUMERIC_COMPONENT))
