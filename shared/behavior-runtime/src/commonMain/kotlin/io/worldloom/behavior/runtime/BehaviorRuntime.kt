package io.worldloom.behavior.runtime

import io.worldloom.definition.BooleanValue
import io.worldloom.definition.DecimalValue
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.TextValue
import io.worldloom.definition.TypedValue
import io.worldloom.world.ActorId
import io.worldloom.world.CommandEnvelope
import io.worldloom.world.CommandId
import io.worldloom.world.CommandPermission
import io.worldloom.world.CURRENT_COMMAND_SCHEMA_VERSION
import io.worldloom.world.EntityId
import io.worldloom.world.GameState

data class BehaviorEventContext(
    val eventType: io.worldloom.definition.DefinitionId,
    val sourceEventId: String,
    val values: Map<String, TypedValue>,
)

data class BehaviorCommandSubmission(
    val envelope: CommandEnvelope,
    val requiredPermission: CommandPermission,
)

sealed interface BehaviorCommandSubmitResult {
    data class Accepted(val sequence: Long) : BehaviorCommandSubmitResult
    data class Rejected(val message: String) : BehaviorCommandSubmitResult
}

fun interface BehaviorCommandSink {
    suspend fun submit(submission: BehaviorCommandSubmission): BehaviorCommandSubmitResult
}

fun interface BehaviorCommandIdSource {
    fun commandId(behaviorId: io.worldloom.definition.DefinitionId, effectIndex: Int): CommandId
}

sealed interface BehaviorExecutionResult {
    data object NotTriggered : BehaviorExecutionResult
    data object ConditionFalse : BehaviorExecutionResult
    data class Applied(val commandCount: Int, val finalSequence: Long) : BehaviorExecutionResult
    data class Failed(val message: String) : BehaviorExecutionResult
}

class BehaviorRuntime(
    private val sink: BehaviorCommandSink,
) {
    suspend fun execute(
        behavior: ValidatedBehavior,
        event: BehaviorEventContext,
        state: GameState,
        actorId: ActorId,
        commandIds: BehaviorCommandIdSource,
    ): BehaviorExecutionResult {
        if (behavior.source.trigger.eventType != event.eventType) return BehaviorExecutionResult.NotTriggered
        val condition = evaluate(behavior.source.condition, event.values, state)
            ?: return BehaviorExecutionResult.Failed("Behavior condition could not be evaluated")
        if ((condition as? BooleanValue)?.value != true) return BehaviorExecutionResult.ConditionFalse
        var sequence = state.lastSequence
        behavior.source.effects.forEachIndexed { index, effect ->
            val schema = behavior.commandSchemas.getValue(effect.commandId)
            val arguments = effect.arguments.mapValues { (_, expression) ->
                evaluate(expression, event.values, state)
                    ?: return BehaviorExecutionResult.Failed("Behavior effect argument could not be evaluated")
            }
            val payload = try {
                schema.build(arguments)
            } catch (_: Exception) {
                return BehaviorExecutionResult.Failed("Behavior effect arguments are invalid")
            }
            val envelope = CommandEnvelope(
                schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
                commandId = commandIds.commandId(behavior.source.id, index),
                runId = state.runId,
                actorId = actorId,
                expectedSequence = sequence,
                correlationId = event.sourceEventId,
                payload = payload,
            )
            when (val submitted = sink.submit(BehaviorCommandSubmission(envelope, schema.permission))) {
                is BehaviorCommandSubmitResult.Accepted -> sequence = submitted.sequence
                is BehaviorCommandSubmitResult.Rejected -> return BehaviorExecutionResult.Failed(submitted.message)
            }
        }
        return BehaviorExecutionResult.Applied(behavior.source.effects.size, sequence)
    }

    private fun evaluate(
        expression: BehaviorExpression,
        paths: Map<String, TypedValue>,
        state: GameState,
    ): TypedValue? = when (expression) {
        is ValueExpression -> expression.value
        is PathExpression -> paths[expression.path]
        is ComponentFieldExpression -> {
            val entityId = (evaluate(expression.entity, paths, state) as? TextValue)?.value ?: return null
            state.entities[EntityId(entityId)]?.components?.get(expression.componentId)?.fields?.get(expression.fieldId)
        }
        is AllExpression -> BooleanValue(expression.terms.all { (evaluate(it, paths, state) as? BooleanValue)?.value == true })
        is AnyExpression -> BooleanValue(expression.terms.any { (evaluate(it, paths, state) as? BooleanValue)?.value == true })
        is NotExpression -> BooleanValue(!(evaluate(expression.term, paths, state) as? BooleanValue)?.value.orFalse())
        is ComparisonExpression -> compare(expression, paths, state)
        is ArithmeticExpression -> arithmetic(expression, paths, state)
    }

    private fun compare(
        expression: ComparisonExpression,
        paths: Map<String, TypedValue>,
        state: GameState,
    ): TypedValue? {
        val left = evaluate(expression.left, paths, state) ?: return null
        val right = evaluate(expression.right, paths, state) ?: return null
        val comparison = when {
            left is IntegerValue && right is IntegerValue -> left.value.compareTo(right.value)
            left is DecimalValue && right is DecimalValue && left.scale == right.scale ->
                left.unscaledValue.compareTo(right.unscaledValue)
            left is TextValue && right is TextValue -> left.value.compareTo(right.value)
            left is BooleanValue && right is BooleanValue -> left.value.compareTo(right.value)
            else -> return null
        }
        return BooleanValue(
            when (expression.operator) {
                ComparisonOperator.EQ -> comparison == 0
                ComparisonOperator.NEQ -> comparison != 0
                ComparisonOperator.LT -> comparison < 0
                ComparisonOperator.LTE -> comparison <= 0
                ComparisonOperator.GT -> comparison > 0
                ComparisonOperator.GTE -> comparison >= 0
            },
        )
    }

    private fun arithmetic(
        expression: ArithmeticExpression,
        paths: Map<String, TypedValue>,
        state: GameState,
    ): TypedValue? {
        val left = (evaluate(expression.left, paths, state) as? IntegerValue)?.value ?: return null
        val right = (evaluate(expression.right, paths, state) as? IntegerValue)?.value ?: return null
        val value = try {
            when (expression.operator) {
                ArithmeticOperator.ADD -> left.addExact(right)
                ArithmeticOperator.SUBTRACT -> left.subtractExact(right)
                ArithmeticOperator.MULTIPLY -> left.multiplyExact(right)
                ArithmeticOperator.DIVIDE -> if (right == 0L || left == Long.MIN_VALUE && right == -1L) return null else left / right
                ArithmeticOperator.MIN -> minOf(left, right)
                ArithmeticOperator.MAX -> maxOf(left, right)
            }
        } catch (_: ArithmeticException) {
            return null
        }
        return IntegerValue(value)
    }
}

private fun Boolean?.orFalse(): Boolean = this ?: false

private fun Long.addExact(other: Long): Long {
    val result = this + other
    if ((this xor result) and (other xor result) < 0) throw ArithmeticException()
    return result
}

private fun Long.subtractExact(other: Long): Long {
    val result = this - other
    if ((this xor other) and (this xor result) < 0) throw ArithmeticException()
    return result
}

private fun Long.multiplyExact(other: Long): Long {
    if (this == 0L || other == 0L) return 0
    val result = this * other
    if (result / other != this || this == Long.MIN_VALUE && other == -1L) throw ArithmeticException()
    return result
}
