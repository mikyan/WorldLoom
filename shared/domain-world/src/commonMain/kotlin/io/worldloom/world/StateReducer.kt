package io.worldloom.world

import io.worldloom.definition.IntegerValue
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.definition.ValueType

enum class StateReductionErrorCode {
    UNSUPPORTED_SCHEMA_VERSION,
    RUN_MISMATCH,
    SEQUENCE_MISMATCH,
    ENTITY_NOT_FOUND,
    COMPONENT_NOT_FOUND,
    FIELD_NOT_FOUND,
    VALUE_TYPE_MISMATCH,
    PREVIOUS_VALUE_MISMATCH,
    INVALID_EVENT_ARITHMETIC,
    INTEGER_OUT_OF_RANGE,
}

data class StateReductionError(
    val code: StateReductionErrorCode,
    val path: String,
    val message: String,
)

sealed interface StateReductionResult {
    data class Success(val state: GameState) : StateReductionResult

    data class Failure(val error: StateReductionError) : StateReductionResult
}

object StateReducer {
    fun reduce(
        state: GameState,
        definition: ValidatedWorldDefinition,
        event: EventEnvelope,
    ): StateReductionResult {
        validateEnvelope(state, event)?.let { return StateReductionResult.Failure(it) }
        return when (val payload = event.payload) {
            is NumericComponentAdjustedEvent -> reduceNumericAdjustment(state, definition, event, payload)
        }
    }

    private fun validateEnvelope(
        state: GameState,
        event: EventEnvelope,
    ): StateReductionError? =
        when {
            event.schemaVersion != CURRENT_EVENT_SCHEMA_VERSION -> error(
                StateReductionErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                "schemaVersion",
                "Unsupported event schema version: ${event.schemaVersion}",
            )

            event.runId != state.runId -> error(
                StateReductionErrorCode.RUN_MISMATCH,
                "runId",
                "Event run does not match the current state",
            )

            event.sequence != state.lastSequence + 1 -> error(
                StateReductionErrorCode.SEQUENCE_MISMATCH,
                "sequence",
                "Event sequence must immediately follow the current state",
            )

            else -> null
        }

    private fun reduceNumericAdjustment(
        state: GameState,
        definition: ValidatedWorldDefinition,
        event: EventEnvelope,
        payload: NumericComponentAdjustedEvent,
    ): StateReductionResult {
        val entity = state.entities[payload.entityId]
            ?: return failure(StateReductionErrorCode.ENTITY_NOT_FOUND, "payload.entityId", "Entity not found")
        val component = entity.components[payload.componentId]
            ?: return failure(
                StateReductionErrorCode.COMPONENT_NOT_FOUND,
                "payload.componentId",
                "Component not found on entity",
            )
        val fieldDefinition = definition.field(payload.componentId, payload.fieldId)
            ?: return failure(StateReductionErrorCode.FIELD_NOT_FOUND, "payload.fieldId", "Field definition not found")
        val currentValue = component.fields[payload.fieldId]
            ?: return failure(StateReductionErrorCode.FIELD_NOT_FOUND, "payload.fieldId", "Field value not found")

        if (fieldDefinition.valueType != ValueType.INTEGER || currentValue !is IntegerValue) {
            return failure(
                StateReductionErrorCode.VALUE_TYPE_MISMATCH,
                "payload.fieldId",
                "Numeric adjustment event requires an INTEGER field",
            )
        }
        if (currentValue.value != payload.previousValue) {
            return failure(
                StateReductionErrorCode.PREVIOUS_VALUE_MISMATCH,
                "payload.previousValue",
                "Event previous value does not match the current state",
            )
        }
        if (CommandValidator.wouldOverflow(payload.previousValue, payload.delta) ||
            payload.previousValue + payload.delta != payload.newValue
        ) {
            return failure(
                StateReductionErrorCode.INVALID_EVENT_ARITHMETIC,
                "payload.newValue",
                "Event arithmetic is inconsistent",
            )
        }
        if (CommandValidator.isOutOfRange(payload.newValue, fieldDefinition.minInteger, fieldDefinition.maxInteger)) {
            return failure(
                StateReductionErrorCode.INTEGER_OUT_OF_RANGE,
                "payload.newValue",
                "Event value exceeds the field bounds",
            )
        }

        val updatedComponent = component.copy(fields = component.fields + (payload.fieldId to IntegerValue(payload.newValue)))
        val updatedEntity = entity.copy(components = entity.components + (payload.componentId to updatedComponent))
        return StateReductionResult.Success(
            state.copy(
                lastSequence = event.sequence,
                entities = state.entities + (payload.entityId to updatedEntity),
            ),
        )
    }

    private fun failure(
        code: StateReductionErrorCode,
        path: String,
        message: String,
    ): StateReductionResult.Failure = StateReductionResult.Failure(error(code, path, message))

    private fun error(
        code: StateReductionErrorCode,
        path: String,
        message: String,
    ): StateReductionError = StateReductionError(code, path, message)
}
