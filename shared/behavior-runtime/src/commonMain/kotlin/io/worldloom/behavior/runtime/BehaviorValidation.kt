package io.worldloom.behavior.runtime

import io.worldloom.definition.BooleanValue
import io.worldloom.definition.DefinitionReferenceValue
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.TextValue
import io.worldloom.definition.TypedValue
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.definition.ValueType
import io.worldloom.definition.valueType
import io.worldloom.world.AdjustNumericComponentCommand
import io.worldloom.world.CommandPermission
import io.worldloom.world.EntityId
import io.worldloom.world.GameCommandPayload

data class BehaviorCommandSchema(
    val argumentTypes: Map<String, ValueType>,
    val permission: CommandPermission,
    val build: (Map<String, TypedValue>) -> GameCommandPayload,
)

class BehaviorCommandRegistry(
    schemas: Map<io.worldloom.definition.DefinitionId, BehaviorCommandSchema>,
) {
    val schemas = schemas.toMap()

    companion object {
        fun standard(): BehaviorCommandRegistry = BehaviorCommandRegistry(
            mapOf(
                io.worldloom.definition.DefinitionId("worldloom.command.adjust-numeric-component") to BehaviorCommandSchema(
                    argumentTypes = mapOf(
                        "entityId" to ValueType.TEXT,
                        "componentId" to ValueType.DEFINITION_REFERENCE,
                        "fieldId" to ValueType.DEFINITION_REFERENCE,
                        "delta" to ValueType.INTEGER,
                    ),
                    permission = CommandPermission.ADJUST_NUMERIC_COMPONENT,
                    build = { arguments ->
                        AdjustNumericComponentCommand(
                            entityId = EntityId((arguments.getValue("entityId") as TextValue).value),
                            componentId = (arguments.getValue("componentId") as DefinitionReferenceValue).value,
                            fieldId = (arguments.getValue("fieldId") as DefinitionReferenceValue).value,
                            delta = (arguments.getValue("delta") as IntegerValue).value,
                        )
                    },
                ),
            ),
        )
    }
}

enum class BehaviorProblemCode {
    UNSUPPORTED_SCHEMA,
    EMPTY_EFFECTS,
    UNKNOWN_PATH,
    TYPE_MISMATCH,
    UNKNOWN_COMPONENT_FIELD,
    COMMAND_NOT_ALLOWED,
    COMMAND_ARGUMENT_MISSING,
    COMMAND_ARGUMENT_UNKNOWN,
}

data class BehaviorProblem(val code: BehaviorProblemCode, val path: String, val message: String)

@ConsistentCopyVisibility
data class ValidatedBehavior internal constructor(
    val source: BehaviorDefinition,
    internal val commandSchemas: Map<io.worldloom.definition.DefinitionId, BehaviorCommandSchema>,
)

sealed interface BehaviorValidationResult {
    data class Valid(val behavior: ValidatedBehavior) : BehaviorValidationResult
    data class Invalid(val problems: List<BehaviorProblem>) : BehaviorValidationResult
}

object BehaviorValidator {
    fun validate(
        behavior: BehaviorDefinition,
        definition: ValidatedWorldDefinition,
        pathTypes: Map<String, ValueType>,
        commands: BehaviorCommandRegistry = BehaviorCommandRegistry.standard(),
    ): BehaviorValidationResult {
        val problems = mutableListOf<BehaviorProblem>()
        if (behavior.schema != BEHAVIOR_SCHEMA_V1) {
            problems += problem(BehaviorProblemCode.UNSUPPORTED_SCHEMA, "schema", "Unsupported Behavior schema")
        }
        if (behavior.effects.isEmpty()) {
            problems += problem(BehaviorProblemCode.EMPTY_EFFECTS, "effects", "Behavior requires at least one effect")
        }
        val conditionType = infer(behavior.condition, definition, pathTypes, "condition", problems)
        if (conditionType != null && conditionType != ValueType.BOOLEAN) {
            problems += problem(BehaviorProblemCode.TYPE_MISMATCH, "condition", "Behavior condition must be BOOLEAN")
        }
        behavior.effects.forEachIndexed { index, effect ->
            val path = "effects[$index]"
            val schema = commands.schemas[effect.commandId]
            if (schema == null) {
                problems += problem(BehaviorProblemCode.COMMAND_NOT_ALLOWED, "$path.commandId", "Command is not whitelisted")
                return@forEachIndexed
            }
            schema.argumentTypes.forEach { (name, expected) ->
                val expression = effect.arguments[name]
                if (expression == null) {
                    problems += problem(BehaviorProblemCode.COMMAND_ARGUMENT_MISSING, "$path.arguments.$name", "Argument is required")
                } else {
                    val actual = infer(expression, definition, pathTypes, "$path.arguments.$name", problems)
                    if (actual != null && actual != expected) {
                        problems += problem(
                            BehaviorProblemCode.TYPE_MISMATCH,
                            "$path.arguments.$name",
                            "Expected $expected, found $actual",
                        )
                    }
                }
            }
            effect.arguments.keys.filterNot(schema.argumentTypes::containsKey).forEach { name ->
                problems += problem(BehaviorProblemCode.COMMAND_ARGUMENT_UNKNOWN, "$path.arguments.$name", "Argument is unknown")
            }
        }
        return if (problems.isEmpty()) {
            BehaviorValidationResult.Valid(ValidatedBehavior(behavior, commands.schemas))
        } else {
            BehaviorValidationResult.Invalid(problems)
        }
    }

    private fun infer(
        expression: BehaviorExpression,
        definition: ValidatedWorldDefinition,
        pathTypes: Map<String, ValueType>,
        path: String,
        problems: MutableList<BehaviorProblem>,
    ): ValueType? = when (expression) {
        is ValueExpression -> expression.value.valueType()
        is PathExpression -> pathTypes[expression.path] ?: run {
            problems += problem(BehaviorProblemCode.UNKNOWN_PATH, path, "Unknown bound path: ${expression.path}")
            null
        }
        is ComponentFieldExpression -> {
            val entityType = infer(expression.entity, definition, pathTypes, "$path.entity", problems)
            if (entityType != null && entityType != ValueType.TEXT) {
                problems += problem(BehaviorProblemCode.TYPE_MISMATCH, "$path.entity", "Entity expression must be TEXT")
            }
            definition.field(expression.componentId, expression.fieldId)?.valueType ?: run {
                problems += problem(BehaviorProblemCode.UNKNOWN_COMPONENT_FIELD, path, "Component field is not defined")
                null
            }
        }
        is AllExpression -> inferBooleanTerms(expression.terms, definition, pathTypes, path, problems)
        is AnyExpression -> inferBooleanTerms(expression.terms, definition, pathTypes, path, problems)
        is NotExpression -> {
            val type = infer(expression.term, definition, pathTypes, "$path.term", problems)
            if (type != null && type != ValueType.BOOLEAN) {
                problems += problem(BehaviorProblemCode.TYPE_MISMATCH, "$path.term", "NOT requires BOOLEAN")
            }
            ValueType.BOOLEAN
        }
        is ComparisonExpression -> {
            val left = infer(expression.left, definition, pathTypes, "$path.left", problems)
            val right = infer(expression.right, definition, pathTypes, "$path.right", problems)
            if (left != null && right != null && left != right) {
                problems += problem(BehaviorProblemCode.TYPE_MISMATCH, path, "Comparison operands must have the same type")
            }
            if (expression.operator !in setOf(ComparisonOperator.EQ, ComparisonOperator.NEQ) &&
                left != null && left !in setOf(ValueType.INTEGER, ValueType.DECIMAL)
            ) {
                problems += problem(BehaviorProblemCode.TYPE_MISMATCH, path, "Ordered comparison requires a number")
            }
            ValueType.BOOLEAN
        }
        is ArithmeticExpression -> {
            val left = infer(expression.left, definition, pathTypes, "$path.left", problems)
            val right = infer(expression.right, definition, pathTypes, "$path.right", problems)
            if (left != null && (left != ValueType.INTEGER || right != ValueType.INTEGER)) {
                problems += problem(BehaviorProblemCode.TYPE_MISMATCH, path, "v1 arithmetic requires INTEGER operands")
            }
            ValueType.INTEGER
        }
    }

    private fun inferBooleanTerms(
        terms: List<BehaviorExpression>,
        definition: ValidatedWorldDefinition,
        pathTypes: Map<String, ValueType>,
        path: String,
        problems: MutableList<BehaviorProblem>,
    ): ValueType {
        if (terms.isEmpty()) problems += problem(BehaviorProblemCode.TYPE_MISMATCH, path, "Boolean group must not be empty")
        terms.forEachIndexed { index, term ->
            val type = infer(term, definition, pathTypes, "$path[$index]", problems)
            if (type != null && type != ValueType.BOOLEAN) {
                problems += problem(BehaviorProblemCode.TYPE_MISMATCH, "$path[$index]", "Expected BOOLEAN")
            }
        }
        return ValueType.BOOLEAN
    }

    private fun problem(code: BehaviorProblemCode, path: String, message: String) = BehaviorProblem(code, path, message)
}
