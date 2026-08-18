package io.worldloom.world

import io.worldloom.definition.IntegerValue
import io.worldloom.definition.ComponentSeed
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.TypedValue
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.definition.ValueType
import io.worldloom.definition.valueType

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
    PAYLOAD_SCHEMA_UNSUPPORTED,
    RUN_LIFECYCLE_INVALID,
    CHARACTER_POLICY_REQUIRED,
    CHARACTER_PROFILE_MISMATCH,
    INITIAL_SCENE_MISMATCH,
    INVALID_ENTITY_ID,
    ENTITY_ALREADY_EXISTS,
    DUPLICATE_COMPONENT,
    DUPLICATE_FIELD,
    MISSING_REQUIRED_FIELD,
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

    data class ChangeRunLifecycle(
        override val envelope: CommandEnvelope,
        val payload: ChangeRunLifecycleCommand,
        val previousLifecycle: RunLifecycle,
    ) : ValidatedCommand

    data class CreatePlayerCharacter(
        override val envelope: CommandEnvelope,
        val payload: CreatePlayerCharacterCommand,
        val entityId: EntityId,
    ) : ValidatedCommand
}

/** Pinned world/profile references supplied by application after package validation. */
data class CharacterCreationCommandPolicy(
    val profileId: DefinitionId,
    val playerEntityId: EntityId,
    val initialSceneId: DefinitionId,
)

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
        characterCreationPolicy: CharacterCreationCommandPolicy? = null,
    ): CommandValidationResult {
        CommandEnvelopeValidator.validate(state, authorization, envelope)?.let {
            return CommandValidationResult.Invalid(it)
        }

        return when (val payload = envelope.payload) {
            is AdjustNumericComponentCommand -> validateAdjustment(state, definition, authorization, envelope, payload)
            is ChangeRunLifecycleCommand -> validateLifecycle(state, authorization, envelope, payload)
            is CreatePlayerCharacterCommand -> validateCharacterCreation(
                state,
                definition,
                authorization,
                envelope,
                payload,
                characterCreationPolicy,
            )
            else -> invalid(
                CommandValidationErrorCode.UNSUPPORTED_COMMAND_PAYLOAD,
                "payload",
                "Command payload is not handled by the core world validator",
            )
        }
    }

    private fun validateLifecycle(
        state: GameState,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        payload: ChangeRunLifecycleCommand,
    ): CommandValidationResult {
        if (CommandPermission.MANAGE_RUN_LIFECYCLE !in authorization.permissions) {
            return invalid(CommandValidationErrorCode.PERMISSION_DENIED, "payload", "Actor cannot change Run lifecycle")
        }
        if (payload.schemaVersion != CURRENT_RUN_LIFECYCLE_COMMAND_SCHEMA_VERSION) {
            return invalid(
                CommandValidationErrorCode.PAYLOAD_SCHEMA_UNSUPPORTED,
                "payload.schemaVersion",
                "Unsupported Run lifecycle command schema: ${payload.schemaVersion}",
            )
        }
        if (!RunLifecycleTransitions.allows(state.lifecycle, payload.lifecycle)) {
            return invalid(
                CommandValidationErrorCode.RUN_LIFECYCLE_INVALID,
                "payload.lifecycle",
                "Run lifecycle cannot change from ${state.lifecycle} to ${payload.lifecycle}",
            )
        }
        if (payload.lifecycle == RunLifecycle.ACTIVE &&
            (state.playerEntityId == null || state.currentSceneId == null)
        ) {
            return invalid(
                CommandValidationErrorCode.RUN_LIFECYCLE_INVALID,
                "payload.lifecycle",
                "Run cannot activate before player and initial scene facts exist",
            )
        }
        return CommandValidationResult.Valid(ValidatedCommand.ChangeRunLifecycle(envelope, payload, state.lifecycle))
    }

    private fun validateCharacterCreation(
        state: GameState,
        definition: ValidatedWorldDefinition,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        payload: CreatePlayerCharacterCommand,
        policy: CharacterCreationCommandPolicy?,
    ): CommandValidationResult {
        if (CommandPermission.CREATE_PLAYER_CHARACTER !in authorization.permissions) {
            return invalid(CommandValidationErrorCode.PERMISSION_DENIED, "payload", "Actor cannot create the player")
        }
        if (payload.schemaVersion != CURRENT_CHARACTER_CREATION_COMMAND_SCHEMA_VERSION) {
            return invalid(
                CommandValidationErrorCode.PAYLOAD_SCHEMA_UNSUPPORTED,
                "payload.schemaVersion",
                "Unsupported character creation command schema: ${payload.schemaVersion}",
            )
        }
        if (state.lifecycle != RunLifecycle.CHARACTER_CREATION) {
            return invalid(
                CommandValidationErrorCode.RUN_LIFECYCLE_INVALID,
                "payload",
                "Player can only be created during CHARACTER_CREATION",
            )
        }
        val creationPolicy = policy ?: return invalid(
            CommandValidationErrorCode.CHARACTER_POLICY_REQUIRED,
            "payload",
            "Validated character creation policy is required",
        )
        if (payload.profileId != creationPolicy.profileId) {
            return invalid(
                CommandValidationErrorCode.CHARACTER_PROFILE_MISMATCH,
                "payload.profileId",
                "Character profile does not match the pinned world",
            )
        }
        if (payload.initialSceneId != creationPolicy.initialSceneId) {
            return invalid(
                CommandValidationErrorCode.INITIAL_SCENE_MISMATCH,
                "payload.initialSceneId",
                "Initial scene does not match the playable world contract",
            )
        }
        val entityId = try {
            EntityId(payload.entity.entityId)
        } catch (_: IllegalArgumentException) {
            return invalid(
                CommandValidationErrorCode.INVALID_ENTITY_ID,
                "payload.entity.entityId",
                "Player Entity ID is invalid",
            )
        }
        if (entityId != creationPolicy.playerEntityId) {
            return invalid(
                CommandValidationErrorCode.INVALID_ENTITY_ID,
                "payload.entity.entityId",
                "Player Entity ID does not match the playable world contract",
            )
        }
        if (entityId in state.entities || state.playerEntityId != null) {
            return invalid(
                CommandValidationErrorCode.ENTITY_ALREADY_EXISTS,
                "payload.entity.entityId",
                "Player Entity already exists",
            )
        }
        validateComponents(payload.entity.components, definition)?.let { return CommandValidationResult.Invalid(it) }
        return CommandValidationResult.Valid(ValidatedCommand.CreatePlayerCharacter(envelope, payload, entityId))
    }

    private fun validateComponents(
        components: List<ComponentSeed>,
        definition: ValidatedWorldDefinition,
    ): CommandValidationError? {
        if (components.isEmpty()) {
            return error(
                CommandValidationErrorCode.COMPONENT_NOT_FOUND,
                "payload.entity.components",
                "Player Entity requires at least one initialized component",
            )
        }
        val componentIds = mutableSetOf<DefinitionId>()
        components.forEachIndexed { componentIndex, component ->
            val componentPath = "payload.entity.components[$componentIndex]"
            if (!componentIds.add(component.definitionId)) {
                return error(
                    CommandValidationErrorCode.DUPLICATE_COMPONENT,
                    "$componentPath.definitionId",
                    "Player component is duplicated",
                )
            }
            val componentDefinition = definition.component(component.definitionId) ?: return error(
                CommandValidationErrorCode.COMPONENT_NOT_FOUND,
                "$componentPath.definitionId",
                "Player component is not defined",
            )
            val fieldsById = componentDefinition.fields.associateBy { it.id }
            val fieldIds = mutableSetOf<DefinitionId>()
            component.fields.forEachIndexed { fieldIndex, field ->
                val fieldPath = "$componentPath.fields[$fieldIndex]"
                if (!fieldIds.add(field.id)) {
                    return error(
                        CommandValidationErrorCode.DUPLICATE_FIELD,
                        "$fieldPath.id",
                        "Player component field is duplicated",
                    )
                }
                val fieldDefinition = fieldsById[field.id] ?: return error(
                    CommandValidationErrorCode.FIELD_NOT_FOUND,
                    "$fieldPath.id",
                    "Player field is not defined",
                )
                validateInitialValue(field.value, fieldDefinition.valueType, fieldDefinition.minInteger, fieldDefinition.maxInteger)
                    ?.let { message ->
                        return error(
                            if (message == "out-of-range") CommandValidationErrorCode.INTEGER_OUT_OF_RANGE
                            else CommandValidationErrorCode.VALUE_TYPE_MISMATCH,
                            "$fieldPath.value",
                            if (message == "out-of-range") "Player field is outside declared bounds"
                            else "Player field type does not match its Definition",
                        )
                    }
            }
            componentDefinition.fields.filter { it.required && it.id !in fieldIds }.firstOrNull()?.let { missing ->
                return error(
                    CommandValidationErrorCode.MISSING_REQUIRED_FIELD,
                    "$componentPath.fields",
                    "Missing required player field: ${missing.id}",
                )
            }
        }
        return null
    }

    private fun validateInitialValue(
        value: TypedValue,
        expectedType: ValueType,
        minimum: Long?,
        maximum: Long?,
    ): String? {
        if (value.valueType() != expectedType) return "type"
        if (value is IntegerValue && isOutOfRange(value.value, minimum, maximum)) return "out-of-range"
        return null
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

    private fun error(
        code: CommandValidationErrorCode,
        path: String,
        message: String,
    ): CommandValidationError = CommandValidationError(code, path, message)
}
