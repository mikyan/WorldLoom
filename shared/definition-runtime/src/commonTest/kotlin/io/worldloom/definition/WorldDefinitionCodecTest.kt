package io.worldloom.definition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorldDefinitionCodecTest {
    @Test
    fun typedValuesRoundTripWithoutLosingTheirType() {
        val original = validDefinition(
            field = FieldDefinition(
                id = DefinitionId("test.precision"),
                valueType = ValueType.DECIMAL,
            ),
            value = DecimalValue(unscaledValue = 1234, scale = 2),
        )

        val decoded = assertIs<WorldDefinitionDecodeResult.Success>(
            WorldDefinitionCodec.decode(WorldDefinitionCodec.encode(original)),
        ).definition

        assertEquals(original, decoded)
    }

    @Test
    fun invalidJsonReturnsStructuredFailure() {
        val result = assertIs<WorldDefinitionDecodeResult.Failure>(WorldDefinitionCodec.decode("{invalid"))

        assertEquals("definition.decode.invalid_json", result.code)
    }

    private fun validDefinition(
        field: FieldDefinition,
        value: TypedValue,
    ): WorldDefinition =
        WorldDefinition(
            schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
            id = DefinitionId("test.world"),
            title = "Test World",
            components = listOf(ComponentDefinition(DefinitionId("test.component"), listOf(field))),
            initialEntities = listOf(
                EntitySeed(
                    entityId = "player",
                    components = listOf(
                        ComponentSeed(
                            definitionId = DefinitionId("test.component"),
                            fields = listOf(FieldSeed(field.id, value)),
                        ),
                    ),
                ),
            ),
            presentation = emptyList(),
        )
}
