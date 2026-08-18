package io.worldloom.definition

private val ENTITY_ID_PATTERN = Regex("^[a-z][a-z0-9._-]*$")

enum class DefinitionProblemCode {
    UNSUPPORTED_SCHEMA_VERSION,
    BLANK_TITLE,
    DUPLICATE_DEFINITION,
    DUPLICATE_FIELD,
    INVALID_FIELD_CONSTRAINT,
    INVALID_ENTITY_ID,
    DUPLICATE_ENTITY,
    DUPLICATE_COMPONENT,
    DUPLICATE_FIELD_VALUE,
    UNKNOWN_COMPONENT,
    UNKNOWN_FIELD,
    MISSING_REQUIRED_FIELD,
    VALUE_TYPE_MISMATCH,
    INTEGER_OUT_OF_RANGE,
    INVALID_PRESENTATION_BINDING,
    INVALID_PRESENTATION_LABEL,
    INVALID_ADJUSTMENT_STEP,
    DUPLICATE_CHECK_PROFILE,
    INVALID_CHECK_PROFILE,
    DUPLICATE_CHECK_OUTCOME,
    INVALID_CHECK_OUTCOME,
    INVALID_CHECK_PRESENTATION,
}

data class DefinitionProblem(
    val code: DefinitionProblemCode,
    val path: String,
    val message: String,
)

sealed interface DefinitionValidationResult {
    data class Valid(val definition: ValidatedWorldDefinition) : DefinitionValidationResult

    data class Invalid(val problems: List<DefinitionProblem>) : DefinitionValidationResult
}

/** A world definition whose type, reference, and presentation bindings have been checked. */
class ValidatedWorldDefinition internal constructor(
    val source: WorldDefinition,
    private val componentsById: Map<DefinitionId, ComponentDefinition>,
    private val checkProfilesById: Map<DefinitionId, CheckProfileDefinition>,
) {
    fun component(id: DefinitionId): ComponentDefinition? = componentsById[id]

    fun field(
        componentId: DefinitionId,
        fieldId: DefinitionId,
    ): FieldDefinition? = componentsById[componentId]?.fields?.firstOrNull { it.id == fieldId }

    fun checkProfile(id: DefinitionId): CheckProfileDefinition? = checkProfilesById[id]
}

object WorldDefinitionValidator {
    fun validate(definition: WorldDefinition): DefinitionValidationResult {
        val problems = mutableListOf<DefinitionProblem>()

        if (definition.schemaVersion != CURRENT_WORLD_DEFINITION_SCHEMA_VERSION) {
            problems += problem(
                DefinitionProblemCode.UNSUPPORTED_SCHEMA_VERSION,
                "schemaVersion",
                "Unsupported world definition schema version: ${definition.schemaVersion}",
            )
        }
        if (definition.title.isBlank()) {
            problems += problem(DefinitionProblemCode.BLANK_TITLE, "title", "World title must not be blank")
        }

        val componentsById = indexComponents(definition, problems)
        val entitiesById = indexEntities(definition, componentsById, problems)
        val checkProfilesById = indexCheckProfiles(definition, problems)
        validatePresentation(definition, componentsById, entitiesById, checkProfilesById, problems)

        return if (problems.isEmpty()) {
            DefinitionValidationResult.Valid(ValidatedWorldDefinition(definition, componentsById, checkProfilesById))
        } else {
            DefinitionValidationResult.Invalid(problems.toList())
        }
    }

    private fun indexComponents(
        definition: WorldDefinition,
        problems: MutableList<DefinitionProblem>,
    ): Map<DefinitionId, ComponentDefinition> {
        val componentsById = linkedMapOf<DefinitionId, ComponentDefinition>()
        definition.components.forEachIndexed { componentIndex, component ->
            val componentPath = "components[$componentIndex]"
            if (componentsById.put(component.id, component) != null) {
                problems += problem(
                    DefinitionProblemCode.DUPLICATE_DEFINITION,
                    "$componentPath.id",
                    "Duplicate component definition: ${component.id}",
                )
            }

            val fieldIds = mutableSetOf<DefinitionId>()
            component.fields.forEachIndexed { fieldIndex, field ->
                val fieldPath = "$componentPath.fields[$fieldIndex]"
                if (!fieldIds.add(field.id)) {
                    problems += problem(
                        DefinitionProblemCode.DUPLICATE_FIELD,
                        "$fieldPath.id",
                        "Duplicate field definition: ${field.id}",
                    )
                }
                validateFieldConstraint(field, fieldPath, problems)
            }
        }
        return componentsById
    }

    private fun validateFieldConstraint(
        field: FieldDefinition,
        path: String,
        problems: MutableList<DefinitionProblem>,
    ) {
        val hasIntegerConstraint = field.minInteger != null || field.maxInteger != null
        if (hasIntegerConstraint && field.valueType != ValueType.INTEGER) {
            problems += problem(
                DefinitionProblemCode.INVALID_FIELD_CONSTRAINT,
                path,
                "Integer bounds can only be applied to INTEGER fields",
            )
        }
        if (field.minInteger != null && field.maxInteger != null && field.minInteger > field.maxInteger) {
            problems += problem(
                DefinitionProblemCode.INVALID_FIELD_CONSTRAINT,
                path,
                "minInteger must not be greater than maxInteger",
            )
        }
    }

    private fun indexEntities(
        definition: WorldDefinition,
        componentsById: Map<DefinitionId, ComponentDefinition>,
        problems: MutableList<DefinitionProblem>,
    ): Map<String, EntitySeed> {
        val entitiesById = linkedMapOf<String, EntitySeed>()
        definition.initialEntities.forEachIndexed { entityIndex, entity ->
            val entityPath = "initialEntities[$entityIndex]"
            if (!ENTITY_ID_PATTERN.matches(entity.entityId)) {
                problems += problem(
                    DefinitionProblemCode.INVALID_ENTITY_ID,
                    "$entityPath.entityId",
                    "Entity ID must be a stable lowercase identifier",
                )
            }
            if (entitiesById.put(entity.entityId, entity) != null) {
                problems += problem(
                    DefinitionProblemCode.DUPLICATE_ENTITY,
                    "$entityPath.entityId",
                    "Duplicate entity: ${entity.entityId}",
                )
            }
            validateEntity(entity, entityPath, componentsById, problems)
        }
        return entitiesById
    }

    private fun validateEntity(
        entity: EntitySeed,
        entityPath: String,
        componentsById: Map<DefinitionId, ComponentDefinition>,
        problems: MutableList<DefinitionProblem>,
    ) {
        val componentIds = mutableSetOf<DefinitionId>()
        entity.components.forEachIndexed { componentIndex, componentSeed ->
            val componentPath = "$entityPath.components[$componentIndex]"
            if (!componentIds.add(componentSeed.definitionId)) {
                problems += problem(
                    DefinitionProblemCode.DUPLICATE_COMPONENT,
                    "$componentPath.definitionId",
                    "Duplicate component instance: ${componentSeed.definitionId}",
                )
            }
            val componentDefinition = componentsById[componentSeed.definitionId]
            if (componentDefinition == null) {
                problems += problem(
                    DefinitionProblemCode.UNKNOWN_COMPONENT,
                    "$componentPath.definitionId",
                    "Unknown component definition: ${componentSeed.definitionId}",
                )
            } else {
                validateComponentSeed(componentSeed, componentDefinition, componentPath, problems)
            }
        }
    }

    private fun validateComponentSeed(
        seed: ComponentSeed,
        definition: ComponentDefinition,
        path: String,
        problems: MutableList<DefinitionProblem>,
    ) {
        val fieldsById = definition.fields.associateBy { it.id }
        val suppliedFieldIds = mutableSetOf<DefinitionId>()

        seed.fields.forEachIndexed { fieldIndex, fieldSeed ->
            val fieldPath = "$path.fields[$fieldIndex]"
            if (!suppliedFieldIds.add(fieldSeed.id)) {
                problems += problem(
                    DefinitionProblemCode.DUPLICATE_FIELD_VALUE,
                    "$fieldPath.id",
                    "Duplicate field value: ${fieldSeed.id}",
                )
            }
            val fieldDefinition = fieldsById[fieldSeed.id]
            if (fieldDefinition == null) {
                problems += problem(
                    DefinitionProblemCode.UNKNOWN_FIELD,
                    "$fieldPath.id",
                    "Unknown field definition: ${fieldSeed.id}",
                )
            } else {
                validateFieldValue(fieldSeed.value, fieldDefinition, "$fieldPath.value", problems)
            }
        }

        definition.fields.filter { it.required && it.id !in suppliedFieldIds }.forEach { missing ->
            problems += problem(
                DefinitionProblemCode.MISSING_REQUIRED_FIELD,
                "$path.fields",
                "Missing required field: ${missing.id}",
            )
        }
    }

    private fun validateFieldValue(
        value: TypedValue,
        definition: FieldDefinition,
        path: String,
        problems: MutableList<DefinitionProblem>,
    ) {
        if (value.valueType() != definition.valueType) {
            problems += problem(
                DefinitionProblemCode.VALUE_TYPE_MISMATCH,
                path,
                "Expected ${definition.valueType}, found ${value.valueType()}",
            )
            return
        }

        if (value is IntegerValue) {
            val belowMinimum = definition.minInteger?.let { value.value < it } == true
            val aboveMaximum = definition.maxInteger?.let { value.value > it } == true
            if (belowMinimum || aboveMaximum) {
                problems += problem(
                    DefinitionProblemCode.INTEGER_OUT_OF_RANGE,
                    path,
                    "Integer value is outside the declared bounds",
                )
            }
        }
    }

    private fun validatePresentation(
        definition: WorldDefinition,
        componentsById: Map<DefinitionId, ComponentDefinition>,
        entitiesById: Map<String, EntitySeed>,
        checkProfilesById: Map<DefinitionId, CheckProfileDefinition>,
        problems: MutableList<DefinitionProblem>,
    ) {
        val presentationIds = mutableSetOf<DefinitionId>()
        definition.presentation.forEachIndexed { index, presentation ->
            val path = "presentation[$index]"
            if (!presentationIds.add(presentation.id)) {
                problems += problem(
                    DefinitionProblemCode.DUPLICATE_DEFINITION,
                    "$path.id",
                    "Duplicate presentation definition: ${presentation.id}",
                )
            }
            if (presentation.label.isBlank()) {
                problems += problem(
                    DefinitionProblemCode.INVALID_PRESENTATION_LABEL,
                    "$path.label",
                    "Presentation label must not be blank",
                )
            }
            if (presentation.adjustmentStep == 0L) {
                problems += problem(
                    DefinitionProblemCode.INVALID_ADJUSTMENT_STEP,
                    "$path.adjustmentStep",
                    "Adjustment step must not be zero",
                )
            }

            val entity = entitiesById[presentation.entityId]
            val component = componentsById[presentation.componentId]
            val field = component?.fields?.firstOrNull { it.id == presentation.fieldId }
            val hasComponent = entity?.components?.any { it.definitionId == presentation.componentId } == true
            val hasFieldValue = entity?.components
                ?.firstOrNull { it.definitionId == presentation.componentId }
                ?.fields
                ?.any { it.id == presentation.fieldId } == true

            if (entity == null || component == null || field == null || !hasComponent || !hasFieldValue) {
                problems += problem(
                    DefinitionProblemCode.INVALID_PRESENTATION_BINDING,
                    path,
                    "Presentation binding must reference an initialized entity field",
                )
            } else if (field.valueType != ValueType.INTEGER) {
                problems += problem(
                    DefinitionProblemCode.INVALID_PRESENTATION_BINDING,
                    "$path.fieldId",
                    "The initialization adjustment binding must reference an INTEGER field",
                )
            }
        }

        definition.presentationChecks.forEachIndexed { index, presentation ->
            val path = "presentationChecks[$index]"
            if (!presentationIds.add(presentation.id)) {
                problems += problem(
                    DefinitionProblemCode.DUPLICATE_DEFINITION,
                    "$path.id",
                    "Duplicate presentation definition: ${presentation.id}",
                )
            }
            if (presentation.label.isBlank()) {
                problems += problem(
                    DefinitionProblemCode.INVALID_PRESENTATION_LABEL,
                    "$path.label",
                    "Presentation label must not be blank",
                )
            }
            if (presentation.checkProfileId !in checkProfilesById) {
                problems += problem(
                    DefinitionProblemCode.INVALID_CHECK_PRESENTATION,
                    "$path.checkProfileId",
                    "Presentation check must reference a declared CheckProfile",
                )
            }
        }
    }

    private fun indexCheckProfiles(
        definition: WorldDefinition,
        problems: MutableList<DefinitionProblem>,
    ): Map<DefinitionId, CheckProfileDefinition> {
        val profiles = linkedMapOf<DefinitionId, CheckProfileDefinition>()
        definition.checkProfiles.forEachIndexed { profileIndex, profile ->
            val path = "checkProfiles[$profileIndex]"
            if (profiles.put(profile.id, profile) != null) {
                problems += problem(
                    DefinitionProblemCode.DUPLICATE_CHECK_PROFILE,
                    "$path.id",
                    "Duplicate check profile: ${profile.id}",
                )
            }
            if (profile.label.isBlank()) {
                problems += problem(
                    DefinitionProblemCode.INVALID_CHECK_PROFILE,
                    "$path.label",
                    "Check profile label must not be blank",
                )
            }
            when (profile.mode) {
                CheckResolutionMode.RANDOM -> {
                    val dice = profile.dice
                    if (dice == null || dice.count !in 1..100 || dice.sides !in 2..1000) {
                        problems += problem(
                            DefinitionProblemCode.INVALID_CHECK_PROFILE,
                            "$path.dice",
                            "Random checks require 1..100 dice with 2..1000 sides",
                        )
                    }
                }

                CheckResolutionMode.DETERMINISTIC -> if (profile.dice != null) {
                    problems += problem(
                        DefinitionProblemCode.INVALID_CHECK_PROFILE,
                        "$path.dice",
                        "Deterministic checks must not declare dice",
                    )
                }
            }
            if (profile.outcomes.isEmpty()) {
                problems += problem(
                    DefinitionProblemCode.INVALID_CHECK_PROFILE,
                    "$path.outcomes",
                    "Check profile must declare at least one outcome",
                )
            }
            val outcomeIds = mutableSetOf<DefinitionId>()
            val thresholds = mutableSetOf<Long>()
            profile.outcomes.forEachIndexed { outcomeIndex, outcome ->
                val outcomePath = "$path.outcomes[$outcomeIndex]"
                if (!outcomeIds.add(outcome.id)) {
                    problems += problem(
                        DefinitionProblemCode.DUPLICATE_CHECK_OUTCOME,
                        "$outcomePath.id",
                        "Duplicate check outcome: ${outcome.id}",
                    )
                }
                if (!thresholds.add(outcome.minimumTotal)) {
                    problems += problem(
                        DefinitionProblemCode.DUPLICATE_CHECK_OUTCOME,
                        "$outcomePath.minimumTotal",
                        "Check outcome thresholds must be unique",
                    )
                }
                if (outcome.label.isBlank()) {
                    problems += problem(
                        DefinitionProblemCode.INVALID_CHECK_OUTCOME,
                        "$outcomePath.label",
                        "Check outcome label must not be blank",
                    )
                }
            }
        }
        return profiles
    }

    private fun problem(
        code: DefinitionProblemCode,
        path: String,
        message: String,
    ): DefinitionProblem = DefinitionProblem(code, path, message)
}
