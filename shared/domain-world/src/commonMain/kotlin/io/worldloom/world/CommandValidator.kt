package io.worldloom.world

import io.worldloom.definition.IntegerValue
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.definition.ValueType

enum class CommandValidationErrorCode {
    UNSUPPORTED_SCHEMA_VERSION,
    RUN_MISMATCH,
    ACTOR_MISMATCH,
    PERMISSION_DENIED,
    SEQUENCE_CONFLICT,
    ENTITY_NOT_FOUND,
    COMPONENT_NOT_FOUND,
    FIELD_NOT_FOUND,
    VALUE_TYPE_MISMATCH,
    INTEGER_OVERFLOW,
    INTEGER_OUT_OF_RANGE,
    UNSUPPORTED_COMMAND_PAYLOAD,
}

data class CommandValidationError(
    val code: CommandValidationErrorCode,
    val path: String,
    val message: String,
)

sealed interface CommandValidationResult {
    data class Valid(val command: ValidatedCommand) : CommandValidationResult

    data class Invalid(val error: CommandValidationError) : CommandValidationResult
}

sealed interface ValidatedCommand {
    val envelope: CommandEnvelope

    data class AdjustNumericComponent(
        override val envelope: CommandEnvelope,
        val payload: AdjustNumericComponentCommand,
        val previousValue: Long,
        val newValue: Long,
    ) : ValidatedCommand
}

/** Shared envelope checks used before dispatching a payload to its owning module validator. */
object CommandEnvelopeValidator {
    fun validate(
        state: GameState,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
    ): CommandValidationError? =
        when {
            envelope.schemaVersion != CURRENT_COMMAND_SCHEMA_VERSION -> error(
                CommandValidationErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                "schemaVersion",
                "Unsupported command schema version: ${envelope.schemaVersion}",
            )

            envelope.runId != state.runId -> error(
                CommandValidationErrorCode.RUN_MISMATCH,
                "runId",
                "Command run does not match the current state",
            )

            envelope.actorId != authorization.actorId -> error(
                CommandValidationErrorCode.ACTOR_MISMATCH,
                "actorId",
                "Command actor does not match its authorization context",
            )

            envelope.expectedSequence != state.lastSequence -> error(
                CommandValidationErrorCode.SEQUENCE_CONFLICT,
                "expectedSequence",
                "Expected sequence ${envelope.expectedSequence}, current sequence is ${state.lastSequence}",
            )

            else -> null
        }

    private fun error(
        code: CommandValidationErrorCode,
        path: String,
        message: String,
    ): CommandValidationError = CommandValidationError(code, path, message)
}

object CommandValidator {
    fun validate(
        state: GameState,
        definition: ValidatedWorldDefinition,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
    ): CommandValidationResult {
        CommandEnvelopeValidator.validate(state, authorization, envelope)?.let {
            return CommandValidationResult.Invalid(it)
        }

        return when (val payload = envelope.payload) {
            is AdjustNumericComponentCommand -> validateAdjustment(state, definition, authorization, envelope, payload)
            else -> invalid(
                CommandValidationErrorCode.UNSUPPORTED_COMMAND_PAYLOAD,
                "payload",
                "Command payload is not handled by the core world validator",
            )
        }
    }

    private fun validateAdjustment(
        state: GameState,
        definition: ValidatedWorldDefinition,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        payload: AdjustNumericComponentCommand,
    ): CommandValidationResult {
        if (CommandPermission.ADJUST_NUMERIC_COMPONENT !in authorization.permissions) {
            return invalid(
                CommandValidationErrorCode.PERMISSION_DENIED,
                "payload",
                "Actor is not permitted to adjust numeric components",
            )
        }

        val entity = state.entities[payload.entityId]
            ?: return invalid(CommandValidationErrorCode.ENTITY_NOT_FOUND, "payload.entityId", "Entity not found")
        val component = entity.components[payload.componentId]
            ?: return invalid(
                CommandValidationErrorCode.COMPONENT_NOT_FOUND,
                "payload.componentId",
                "Component not found on entity",
            )
        val fieldDefinition = definition.field(payload.componentId, payload.fieldId)
            ?: return invalid(CommandValidationErrorCode.FIELD_NOT_FOUND, "payload.fieldId", "Field definition not found")
        val currentValue = component.fields[payload.fieldId]
            ?: return invalid(CommandValidationErrorCode.FIELD_NOT_FOUND, "payload.fieldId", "Field value not found")

        if (fieldDefinition.valueType != ValueType.INTEGER || currentValue !is IntegerValue) {
            return invalid(
                CommandValidationErrorCode.VALUE_TYPE_MISMATCH,
                "payload.fieldId",
                "Numeric adjustment requires an INTEGER field",
            )
        }
        if (wouldOverflow(currentValue.value, payload.delta)) {
            return invalid(
                CommandValidationErrorCode.INTEGER_OVERFLOW,
                "payload.delta",
                "Numeric adjustment would overflow a signed 64-bit integer",
            )
        }

        val newValue = currentValue.value + payload.delta
        if (isOutOfRange(newValue, fieldDefinition.minInteger, fieldDefinition.maxInteger)) {
            return invalid(
                CommandValidationErrorCode.INTEGER_OUT_OF_RANGE,
                "payload.delta",
                "Numeric adjustment would exceed the field bounds",
            )
        }

        return CommandValidationResult.Valid(
            ValidatedCommand.AdjustNumericComponent(
                envelope = envelope,
                payload = payload,
                previousValue = currentValue.value,
                newValue = newValue,
            ),
        )
    }

    internal fun wouldOverflow(current: Long, delta: Long): Boolean =
        when {
            delta > 0 -> current > Long.MAX_VALUE - delta
            delta < 0 -> current < Long.MIN_VALUE - delta
            else -> false
        }

    internal fun isOutOfRange(value: Long, minimum: Long?, maximum: Long?): Boolean =
        (minimum != null && value < minimum) || (maximum != null && value > maximum)

    private fun invalid(
        code: CommandValidationErrorCode,
        path: String,
        message: String,
    ): CommandValidationResult.Invalid = CommandValidationResult.Invalid(CommandValidationError(code, path, message))
}
