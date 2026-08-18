package io.worldloom.content.schema

import io.worldloom.definition.ComponentSeed
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.EntitySeed
import io.worldloom.definition.FieldSeed
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.TypedValue
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.definition.ValueType
import io.worldloom.definition.valueType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val CURRENT_CHARACTER_CREATION_SCHEMA_VERSION: Int = 1

@Serializable
enum class CharacterCreationMode { FIXED, TEMPLATE, POINT_BUY, NARRATIVE }

@Serializable
data class CharacterFieldRule(
    val componentId: DefinitionId,
    val fieldId: DefinitionId,
    val defaultValue: TypedValue,
    val minimumInteger: Long? = null,
    val maximumInteger: Long? = null,
    val pointCostPerStep: Int = 1,
) {
    init {
        require(pointCostPerStep > 0) { "Character field point cost must be positive" }
        require(minimumInteger == null || maximumInteger == null || minimumInteger <= maximumInteger) {
            "Character field bounds are invalid"
        }
    }
}

@Serializable
data class CharacterCreationOption(
    val id: DefinitionId,
    val label: String,
    val values: List<CharacterValueAssignment>,
) {
    init { require(label.isNotBlank()) { "Character creation option label must not be blank" } }
}

@Serializable
data class CharacterValueAssignment(
    val componentId: DefinitionId,
    val fieldId: DefinitionId,
    val value: TypedValue,
)

@Serializable
data class CharacterCreationProfile(
    val schemaVersion: Int,
    val id: DefinitionId,
    val modes: Set<CharacterCreationMode>,
    val fields: List<CharacterFieldRule>,
    val fixedOptions: List<CharacterCreationOption> = emptyList(),
    val templates: List<CharacterCreationOption> = emptyList(),
    val pointBuyBudget: Int? = null,
    val narrativeMaximumCharacters: Int = 5_000,
) {
    init {
        require(narrativeMaximumCharacters in 1..5_000) { "Narrative background limit must be within 1..5000" }
    }
}

sealed interface CharacterCreationProfileDecodeResult {
    data class Success(val profile: CharacterCreationProfile) : CharacterCreationProfileDecodeResult

    data class Failure(val message: String) : CharacterCreationProfileDecodeResult
}

@OptIn(ExperimentalSerializationApi::class)
object CharacterCreationProfileCodec {
    private val json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    fun decode(source: String): CharacterCreationProfileDecodeResult =
        try {
            CharacterCreationProfileDecodeResult.Success(json.decodeFromString<CharacterCreationProfile>(source))
        } catch (error: SerializationException) {
            CharacterCreationProfileDecodeResult.Failure(error.message ?: "Character profile JSON is invalid")
        } catch (error: IllegalArgumentException) {
            CharacterCreationProfileDecodeResult.Failure(error.message ?: "Character profile contains an invalid value")
        }

    fun encode(profile: CharacterCreationProfile): String = json.encodeToString(profile)
}

enum class CharacterProfileProblemCode {
    UNSUPPORTED_SCHEMA,
    NO_CREATION_MODE,
    DUPLICATE_FIELD,
    UNKNOWN_FIELD,
    VALUE_TYPE_MISMATCH,
    INVALID_INTEGER_BOUNDS,
    MODE_CONFIGURATION_MISSING,
    DUPLICATE_OPTION,
    DUPLICATE_ASSIGNMENT,
    ASSIGNMENT_OUT_OF_RANGE,
}

data class CharacterProfileProblem(
    val code: CharacterProfileProblemCode,
    val path: String,
    val message: String,
)

@ConsistentCopyVisibility
data class ValidatedCharacterCreationProfile internal constructor(
    val source: CharacterCreationProfile,
    internal val rules: Map<Pair<DefinitionId, DefinitionId>, CharacterFieldRule>,
)

sealed interface CharacterProfileValidationResult {
    data class Valid(val profile: ValidatedCharacterCreationProfile) : CharacterProfileValidationResult
    data class Invalid(val problems: List<CharacterProfileProblem>) : CharacterProfileValidationResult
}

object CharacterCreationProfileValidator {
    fun validate(
        profile: CharacterCreationProfile,
        definition: ValidatedWorldDefinition,
    ): CharacterProfileValidationResult {
        val problems = mutableListOf<CharacterProfileProblem>()
        if (profile.schemaVersion != CURRENT_CHARACTER_CREATION_SCHEMA_VERSION) {
            problems += problem(CharacterProfileProblemCode.UNSUPPORTED_SCHEMA, "schemaVersion", "Unsupported profile schema")
        }
        if (profile.modes.isEmpty()) {
            problems += problem(CharacterProfileProblemCode.NO_CREATION_MODE, "modes", "At least one mode is required")
        }
        val rules = linkedMapOf<Pair<DefinitionId, DefinitionId>, CharacterFieldRule>()
        profile.fields.forEachIndexed { index, rule ->
            val path = "fields[$index]"
            val key = rule.componentId to rule.fieldId
            if (rules.containsKey(key)) {
                problems += problem(CharacterProfileProblemCode.DUPLICATE_FIELD, path, "Character field is duplicated")
            } else {
                rules[key] = rule
            }
            val field = definition.field(rule.componentId, rule.fieldId)
            if (field == null) {
                problems += problem(CharacterProfileProblemCode.UNKNOWN_FIELD, path, "Character field is not defined")
            } else if (field.valueType != rule.defaultValue.valueType()) {
                problems += problem(CharacterProfileProblemCode.VALUE_TYPE_MISMATCH, "$path.defaultValue", "Default type is invalid")
            }
            validateInteger(rule, field?.valueType, path, problems)
        }
        if (CharacterCreationMode.FIXED in profile.modes && profile.fixedOptions.isEmpty()) {
            problems += problem(CharacterProfileProblemCode.MODE_CONFIGURATION_MISSING, "fixedOptions", "Fixed mode needs options")
        }
        if (CharacterCreationMode.TEMPLATE in profile.modes && profile.templates.isEmpty()) {
            problems += problem(CharacterProfileProblemCode.MODE_CONFIGURATION_MISSING, "templates", "Template mode needs options")
        }
        if (CharacterCreationMode.POINT_BUY in profile.modes && (profile.pointBuyBudget == null || profile.pointBuyBudget < 0)) {
            problems += problem(CharacterProfileProblemCode.MODE_CONFIGURATION_MISSING, "pointBuyBudget", "Point-buy mode needs a budget")
        }
        validateOptions("fixedOptions", profile.fixedOptions, rules, problems)
        validateOptions("templates", profile.templates, rules, problems)
        return if (problems.isEmpty()) {
            CharacterProfileValidationResult.Valid(ValidatedCharacterCreationProfile(profile, rules))
        } else {
            CharacterProfileValidationResult.Invalid(problems)
        }
    }

    private fun validateInteger(
        rule: CharacterFieldRule,
        type: ValueType?,
        path: String,
        problems: MutableList<CharacterProfileProblem>,
    ) {
        if (rule.minimumInteger != null || rule.maximumInteger != null) {
            if (type != ValueType.INTEGER || rule.defaultValue !is IntegerValue) {
                problems += problem(CharacterProfileProblemCode.INVALID_INTEGER_BOUNDS, path, "Integer bounds require an INTEGER field")
            } else if (!inBounds(rule.defaultValue.value, rule)) {
                problems += problem(CharacterProfileProblemCode.ASSIGNMENT_OUT_OF_RANGE, "$path.defaultValue", "Default is out of range")
            }
        }
    }

    private fun validateOptions(
        name: String,
        options: List<CharacterCreationOption>,
        rules: Map<Pair<DefinitionId, DefinitionId>, CharacterFieldRule>,
        problems: MutableList<CharacterProfileProblem>,
    ) {
        if (options.map(CharacterCreationOption::id).distinct().size != options.size) {
            problems += problem(CharacterProfileProblemCode.DUPLICATE_OPTION, name, "Option ids must be unique")
        }
        options.forEachIndexed { index, option ->
            val seen = mutableSetOf<Pair<DefinitionId, DefinitionId>>()
            option.values.forEachIndexed { valueIndex, assignment ->
                val path = "$name[$index].values[$valueIndex]"
                val key = assignment.componentId to assignment.fieldId
                if (!seen.add(key)) problems += problem(CharacterProfileProblemCode.DUPLICATE_ASSIGNMENT, path, "Field is assigned twice")
                val rule = rules[key]
                if (rule == null) {
                    problems += problem(CharacterProfileProblemCode.UNKNOWN_FIELD, path, "Assignment is not allowed by the profile")
                } else {
                    validateAssignment(assignment, rule, path, problems)
                }
            }
        }
    }

    internal fun validateAssignment(
        assignment: CharacterValueAssignment,
        rule: CharacterFieldRule,
        path: String,
        problems: MutableList<CharacterProfileProblem>,
    ) {
        if (assignment.value.valueType() != rule.defaultValue.valueType()) {
            problems += problem(CharacterProfileProblemCode.VALUE_TYPE_MISMATCH, path, "Assignment type is invalid")
        }
        if (assignment.value is IntegerValue && !inBounds(assignment.value.value, rule)) {
            problems += problem(CharacterProfileProblemCode.ASSIGNMENT_OUT_OF_RANGE, path, "Assignment is out of range")
        }
    }

    private fun inBounds(value: Long, rule: CharacterFieldRule): Boolean =
        (rule.minimumInteger == null || value >= rule.minimumInteger) &&
            (rule.maximumInteger == null || value <= rule.maximumInteger)

    internal fun problem(code: CharacterProfileProblemCode, path: String, message: String) =
        CharacterProfileProblem(code, path, message)
}

@Serializable
data class CharacterCreationRequest(
    val entityId: String,
    val mode: CharacterCreationMode,
    val optionId: DefinitionId? = null,
    val values: List<CharacterValueAssignment> = emptyList(),
    val narrativeBackground: String? = null,
)

sealed interface CharacterCreationResult {
    data class Success(val entity: EntitySeed, val pointsSpent: Int) : CharacterCreationResult
    data class Failure(val problems: List<CharacterProfileProblem>) : CharacterCreationResult
}

object CharacterCreator {
    fun create(
        profile: ValidatedCharacterCreationProfile,
        request: CharacterCreationRequest,
    ): CharacterCreationResult {
        val problems = mutableListOf<CharacterProfileProblem>()
        if (request.mode !in profile.source.modes) {
            problems += CharacterCreationProfileValidator.problem(
                CharacterProfileProblemCode.MODE_CONFIGURATION_MISSING,
                "mode",
                "Creation mode is not enabled",
            )
            return CharacterCreationResult.Failure(problems)
        }
        val base = profile.source.fields.associate { (it.componentId to it.fieldId) to it.defaultValue }.toMutableMap()
        val option = when (request.mode) {
            CharacterCreationMode.FIXED -> profile.source.fixedOptions.find { it.id == request.optionId }
            CharacterCreationMode.TEMPLATE -> profile.source.templates.find { it.id == request.optionId }
            else -> null
        }
        if (request.mode in setOf(CharacterCreationMode.FIXED, CharacterCreationMode.TEMPLATE) && option == null) {
            problems += CharacterCreationProfileValidator.problem(
                CharacterProfileProblemCode.MODE_CONFIGURATION_MISSING,
                "optionId",
                "Selected creation option does not exist",
            )
        }
        option?.values?.forEach { base[it.componentId to it.fieldId] = it.value }
        if (request.mode == CharacterCreationMode.FIXED && request.values.isNotEmpty()) {
            problems += CharacterCreationProfileValidator.problem(
                CharacterProfileProblemCode.MODE_CONFIGURATION_MISSING,
                "values",
                "Fixed characters cannot be overridden",
            )
        }
        if (request.mode == CharacterCreationMode.NARRATIVE) {
            val narrative = request.narrativeBackground.orEmpty()
            if (narrative.isBlank() || narrative.length > profile.source.narrativeMaximumCharacters) {
                problems += CharacterCreationProfileValidator.problem(
                    CharacterProfileProblemCode.MODE_CONFIGURATION_MISSING,
                    "narrativeBackground",
                    "Narrative background is blank or too long",
                )
            }
        }
        val seen = mutableSetOf<Pair<DefinitionId, DefinitionId>>()
        request.values.forEachIndexed { index, assignment ->
            val key = assignment.componentId to assignment.fieldId
            val rule = profile.rules[key]
            if (!seen.add(key)) {
                problems += CharacterCreationProfileValidator.problem(
                    CharacterProfileProblemCode.DUPLICATE_ASSIGNMENT,
                    "values[$index]",
                    "Field is assigned twice",
                )
            } else if (rule == null) {
                problems += CharacterCreationProfileValidator.problem(
                    CharacterProfileProblemCode.UNKNOWN_FIELD,
                    "values[$index]",
                    "Field is not allowed",
                )
            } else {
                CharacterCreationProfileValidator.validateAssignment(assignment, rule, "values[$index]", problems)
                base[key] = assignment.value
            }
        }
        val points = if (request.mode == CharacterCreationMode.POINT_BUY) {
            profile.source.fields.sumOf { rule ->
                val start = (rule.defaultValue as? IntegerValue)?.value ?: 0
                val value = (base[rule.componentId to rule.fieldId] as? IntegerValue)?.value ?: start
                if (value < start) {
                    problems += CharacterCreationProfileValidator.problem(
                        CharacterProfileProblemCode.ASSIGNMENT_OUT_OF_RANGE,
                        "values",
                        "Point-buy values cannot be below their baseline",
                    )
                    0
                } else {
                    ((value - start) * rule.pointCostPerStep).toInt()
                }
            }
        } else 0
        if (request.mode == CharacterCreationMode.POINT_BUY && points > profile.source.pointBuyBudget.orEmpty()) {
            problems += CharacterCreationProfileValidator.problem(
                CharacterProfileProblemCode.ASSIGNMENT_OUT_OF_RANGE,
                "values",
                "Point-buy budget exceeded",
            )
        }
        if (problems.isNotEmpty()) return CharacterCreationResult.Failure(problems)
        val components = base.entries.groupBy { it.key.first }.map { (componentId, fields) ->
            ComponentSeed(
                definitionId = componentId,
                fields = fields.sortedBy { it.key.second.value }.map { (key, value) -> FieldSeed(key.second, value) },
            )
        }.sortedBy { it.definitionId.value }
        return CharacterCreationResult.Success(EntitySeed(request.entityId, components), points)
    }
}

private fun Int?.orEmpty(): Int = this ?: 0
