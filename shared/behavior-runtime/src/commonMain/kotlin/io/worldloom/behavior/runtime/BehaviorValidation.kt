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
import io.worldloom.rules.AdjustRelationshipCommand
import io.worldloom.rules.AdvanceProgressClockCommand
import io.worldloom.rules.AdvanceQuestCommand
import io.worldloom.rules.ChangeInventoryCommand
import io.worldloom.rules.InventoryOperation
import io.worldloom.rules.QuestStatus
import io.worldloom.rules.UpdateConditionCommand
import io.worldloom.rules.module.api.RegisteredWorldModules

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
        private val NUMERIC_CAPABILITY = io.worldloom.definition.DefinitionId("worldloom.command.numeric-adjust")

        fun standard(): BehaviorCommandRegistry = BehaviorCommandRegistry(standardSchemas())

        fun forWorld(modules: RegisteredWorldModules): BehaviorCommandRegistry = BehaviorCommandRegistry(
            standardSchemas().filterKeys { commandId ->
                val capabilityId = when (commandId.value) {
                    "worldloom.command.adjust-numeric-component" -> NUMERIC_CAPABILITY
                    else -> commandId
                }
                modules.capability(capabilityId) != null
            },
        )

        private fun standardSchemas(): Map<io.worldloom.definition.DefinitionId, BehaviorCommandSchema> = mapOf(
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
                io.worldloom.definition.DefinitionId("worldloom.command.inventory.change") to BehaviorCommandSchema(
                    argumentTypes = mapOf(
                        "itemId" to ValueType.DEFINITION_REFERENCE,
                        "quantity" to ValueType.INTEGER,
                        "operation" to ValueType.TEXT,
                    ),
                    permission = CommandPermission.MANAGE_INVENTORY,
                    build = { arguments ->
                        ChangeInventoryCommand(
                            itemId = (arguments.getValue("itemId") as DefinitionReferenceValue).value,
                            quantity = (arguments.getValue("quantity") as IntegerValue).value,
                            operation = InventoryOperation.valueOf((arguments.getValue("operation") as TextValue).value),
                        )
                    },
                ),
                io.worldloom.definition.DefinitionId("worldloom.command.condition.update") to BehaviorCommandSchema(
                    argumentTypes = mapOf(
                        "conditionId" to ValueType.DEFINITION_REFERENCE,
                        "stackDelta" to ValueType.INTEGER,
                        "elapsedMinutes" to ValueType.INTEGER,
                    ),
                    permission = CommandPermission.UPDATE_CONDITION,
                    build = { arguments ->
                        UpdateConditionCommand(
                            conditionId = (arguments.getValue("conditionId") as DefinitionReferenceValue).value,
                            stackDelta = (arguments.getValue("stackDelta") as IntegerValue).value,
                            elapsedMinutes = (arguments.getValue("elapsedMinutes") as IntegerValue).value,
                        )
                    },
                ),
                io.worldloom.definition.DefinitionId("worldloom.command.relationship.adjust") to BehaviorCommandSchema(
                    argumentTypes = mapOf(
                        "relationshipId" to ValueType.DEFINITION_REFERENCE,
                        "delta" to ValueType.INTEGER,
                    ),
                    permission = CommandPermission.UPDATE_RELATIONSHIP,
                    build = { arguments ->
                        AdjustRelationshipCommand(
                            relationshipId = (arguments.getValue("relationshipId") as DefinitionReferenceValue).value,
                            delta = (arguments.getValue("delta") as IntegerValue).value,
                        )
                    },
                ),
                io.worldloom.definition.DefinitionId("worldloom.command.quest.advance") to BehaviorCommandSchema(
                    argumentTypes = mapOf(
                        "questId" to ValueType.DEFINITION_REFERENCE,
                        "stageId" to ValueType.DEFINITION_REFERENCE,
                        "status" to ValueType.TEXT,
                    ),
                    permission = CommandPermission.UPDATE_QUEST,
                    build = { arguments ->
                        AdvanceQuestCommand(
                            questId = (arguments.getValue("questId") as DefinitionReferenceValue).value,
                            stageId = (arguments.getValue("stageId") as DefinitionReferenceValue).value,
                            status = QuestStatus.valueOf((arguments.getValue("status") as TextValue).value),
                        )
                    },
                ),
                io.worldloom.definition.DefinitionId("worldloom.command.progress-clock.advance") to BehaviorCommandSchema(
                    argumentTypes = mapOf(
                        "clockId" to ValueType.DEFINITION_REFERENCE,
                        "delta" to ValueType.INTEGER,
                    ),
                    permission = CommandPermission.ADVANCE_PROGRESS_CLOCK,
                    build = { arguments ->
                        AdvanceProgressClockCommand(
                            clockId = (arguments.getValue("clockId") as DefinitionReferenceValue).value,
                            delta = (arguments.getValue("delta") as IntegerValue).value,
                        )
                    },
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
    TRIGGER_NOT_ALLOWED,
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
        allowedEventTypes: Set<io.worldloom.definition.DefinitionId>? = null,
    ): BehaviorValidationResult {
        val problems = mutableListOf<BehaviorProblem>()
        if (behavior.schema != BEHAVIOR_SCHEMA_V1) {
            problems += problem(BehaviorProblemCode.UNSUPPORTED_SCHEMA, "schema", "Unsupported Behavior schema")
        }
        if (behavior.effects.isEmpty()) {
            problems += problem(BehaviorProblemCode.EMPTY_EFFECTS, "effects", "Behavior requires at least one effect")
        }
        if (allowedEventTypes != null && behavior.trigger.eventType !in allowedEventTypes) {
            problems += problem(BehaviorProblemCode.TRIGGER_NOT_ALLOWED, "trigger.eventType", "Trigger event is not registered by the world")
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
