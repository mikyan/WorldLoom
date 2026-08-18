package io.worldloom.definition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorldDefinitionValidatorTest {
    @Test
    fun rejectsTypeRangeAndPresentationReferenceProblemsTogether() {
        val componentId = DefinitionId("test.status")
        val fieldId = DefinitionId("test.energy")
        val definition = WorldDefinition(
            schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
            id = DefinitionId("test.world"),
            title = "Test World",
            components = listOf(
                ComponentDefinition(
                    id = componentId,
                    fields = listOf(
                        FieldDefinition(
                            id = fieldId,
                            valueType = ValueType.INTEGER,
                            minInteger = 0,
                            maxInteger = 10,
                        ),
                    ),
                ),
            ),
            initialEntities = listOf(
                EntitySeed(
                    entityId = "player",
                    components = listOf(
                        ComponentSeed(
                            definitionId = componentId,
                            fields = listOf(FieldSeed(fieldId, TextValue("not an integer"))),
                        ),
                    ),
                ),
            ),
            presentation = listOf(
                PresentationFieldDefinition(
                    id = DefinitionId("test.presentation.missing"),
                    entityId = "missing",
                    componentId = componentId,
                    fieldId = fieldId,
                    label = "Missing",
                    adjustmentStep = 0,
                ),
            ),
        )

        val invalid = assertIs<DefinitionValidationResult.Invalid>(WorldDefinitionValidator.validate(definition))
        val codes = invalid.problems.map { it.code }.toSet()

        assertTrue(DefinitionProblemCode.VALUE_TYPE_MISMATCH in codes)
        assertTrue(DefinitionProblemCode.INVALID_PRESENTATION_BINDING in codes)
        assertTrue(DefinitionProblemCode.INVALID_ADJUSTMENT_STEP in codes)
    }

    @Test
    fun exposesIndexedFieldsAfterValidation() {
        val componentId = DefinitionId("test.status")
        val fieldId = DefinitionId("test.energy")
        val definition = WorldDefinition(
            schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
            id = DefinitionId("test.world"),
            title = "Test World",
            components = listOf(
                ComponentDefinition(
                    componentId,
                    listOf(FieldDefinition(fieldId, ValueType.INTEGER, minInteger = 0, maxInteger = 10)),
                ),
            ),
            initialEntities = listOf(
                EntitySeed(
                    "player",
                    listOf(ComponentSeed(componentId, listOf(FieldSeed(fieldId, IntegerValue(5))))),
                ),
            ),
            presentation = emptyList(),
        )

        val valid = assertIs<DefinitionValidationResult.Valid>(WorldDefinitionValidator.validate(definition))

        assertEquals(ValueType.INTEGER, valid.definition.field(componentId, fieldId)?.valueType)
    }

    @Test
    fun rejectsRandomCheckWithoutValidDiceAndUnknownPresentationBinding() {
        val profileId = DefinitionId("test.check.invalid")
        val definition = WorldDefinition(
            schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
            id = DefinitionId("test.world"),
            title = "Test World",
            components = emptyList(),
            initialEntities = emptyList(),
            checkProfiles = listOf(
                CheckProfileDefinition(
                    id = profileId,
                    label = "Invalid",
                    mode = CheckResolutionMode.RANDOM,
                    dice = DiceExpression(0, 1),
                    outcomes = emptyList(),
                ),
            ),
            presentation = emptyList(),
            presentationChecks = listOf(
                PresentationCheckDefinition(
                    DefinitionId("test.presentation.check"),
                    DefinitionId("test.check.missing"),
                    "Check",
                ),
            ),
        )

        val invalid = assertIs<DefinitionValidationResult.Invalid>(WorldDefinitionValidator.validate(definition))

        assertTrue(invalid.problems.any { it.code == DefinitionProblemCode.INVALID_CHECK_PROFILE })
        assertTrue(invalid.problems.any { it.code == DefinitionProblemCode.INVALID_CHECK_PRESENTATION })
    }
}
