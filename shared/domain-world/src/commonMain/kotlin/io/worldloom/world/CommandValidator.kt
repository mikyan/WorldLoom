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
    ACTION_POLICY_REQUIRED,
    ACTION_OUTCOME_MISMATCH,
    NPC_ACTION_POLICY_REQUIRED,
    NPC_ACTION_MISMATCH,
    CURRENT_SCENE_MISMATCH,
    PARTICIPANT_NOT_FOUND,
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

    data class ApplyActionOutcome(
        override val envelope: CommandEnvelope,
        val payload: ApplyActionOutcomeCommand,
        val playerEntityId: EntityId,
    ) : ValidatedCommand

    data class PublishNpcAction(
        override val envelope: CommandEnvelope,
        val payload: PublishNpcActionCommand,
    ) : ValidatedCommand
}

/** Pinned world/profile references supplied by application after package validation. */
data class CharacterCreationCommandPolicy(
    val profileId: DefinitionId,
    val playerEntityId: EntityId,
    val initialSceneId: DefinitionId,
    val initialSceneParticipantIds: List<EntityId> = emptyList(),
)

/** Exact progression selected from a validated playable-world contract. */
data class ActionOutcomeCommandPolicy(
    val actionId: DefinitionId,
    val outcomeId: DefinitionId,
    val fromSceneId: DefinitionId,
    val nextSceneId: DefinitionId?,
    val objectiveIds: List<DefinitionId>,
    val endingId: DefinitionId?,
    val participantIds: List<EntityId>,
)

/** Exact NPC identity, scene and public action capabilities pinned by the validated world package. */
data class NpcPublicActionCommandPolicy(
    val entityId: EntityId,
    val sceneId: DefinitionId,
    val allowedActionIds: Set<DefinitionId>,
    val canSpeak: Boolean,
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
        actionOutcomePolicy: ActionOutcomeCommandPolicy? = null,
        npcPublicActionPolicy: NpcPublicActionCommandPolicy? = null,
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
            is ApplyActionOutcomeCommand -> validateActionOutcome(
                state,
                authorization,
                envelope,
                payload,
                actionOutcomePolicy,
            )
            is PublishNpcActionCommand -> validateNpcPublicAction(
                state,
                authorization,
                envelope,
                payload,
                npcPublicActionPolicy,
            )
            else -> invalid(
                CommandValidationErrorCode.UNSUPPORTED_COMMAND_PAYLOAD,
                "payload",
                "Command payload is not handled by the core world validator",
            )
        }
    }

    private fun validateNpcPublicAction(
        state: GameState,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        payload: PublishNpcActionCommand,
        policy: NpcPublicActionCommandPolicy?,
    ): CommandValidationResult {
        if (CommandPermission.PUBLISH_NPC_ACTION !in authorization.permissions) {
            return invalid(CommandValidationErrorCode.PERMISSION_DENIED, "payload", "Actor cannot publish NPC actions")
        }
        if (payload.schemaVersion != CURRENT_NPC_PUBLIC_ACTION_COMMAND_SCHEMA_VERSION) {
            return invalid(CommandValidationErrorCode.PAYLOAD_SCHEMA_UNSUPPORTED, "payload.schemaVersion", "Unsupported NPC action schema")
        }
        if (state.lifecycle != RunLifecycle.ACTIVE) {
            return invalid(CommandValidationErrorCode.RUN_LIFECYCLE_INVALID, "payload", "NPC actions require an ACTIVE Run")
        }
        val expected = policy ?: return invalid(
            CommandValidationErrorCode.NPC_ACTION_POLICY_REQUIRED,
            "payload",
            "Validated NPC action policy is required",
        )
        if (payload.entityId != expected.entityId || payload.sceneId != expected.sceneId ||
            payload.sceneId != state.currentSceneId || payload.entityId !in state.sceneParticipantIds
        ) {
            return invalid(CommandValidationErrorCode.NPC_ACTION_MISMATCH, "payload", "NPC is not present in the current scene")
        }
        val content = payload.content.trim()
        if (content.isEmpty() || content.length > 500) {
            return invalid(CommandValidationErrorCode.NPC_ACTION_MISMATCH, "payload.content", "NPC public content must contain 1 to 500 characters")
        }
        when (payload.kind) {
            NpcPublicActionKind.SPEECH -> if (!expected.canSpeak || payload.actionId != null) {
                return invalid(CommandValidationErrorCode.NPC_ACTION_MISMATCH, "payload", "NPC speech is not allowed")
            }
            NpcPublicActionKind.ACTION -> if (payload.actionId == null || payload.actionId !in expected.allowedActionIds) {
                return invalid(CommandValidationErrorCode.NPC_ACTION_MISMATCH, "payload.actionId", "NPC public action is not allowed")
            }
        }
        if (payload.entityId !in state.entities) {
            return invalid(CommandValidationErrorCode.ENTITY_NOT_FOUND, "payload.entityId", "NPC Entity is not initialized")
        }
        return CommandValidationResult.Valid(ValidatedCommand.PublishNpcAction(envelope, payload.copy(content = content)))
    }

    private fun validateActionOutcome(
        state: GameState,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        payload: ApplyActionOutcomeCommand,
        policy: ActionOutcomeCommandPolicy?,
    ): CommandValidationResult {
        if (CommandPermission.APPLY_ACTION_OUTCOME !in authorization.permissions) {
            return invalid(CommandValidationErrorCode.PERMISSION_DENIED, "payload", "Actor cannot apply action outcomes")
        }
        if (payload.schemaVersion != CURRENT_ACTION_OUTCOME_COMMAND_SCHEMA_VERSION) {
            return invalid(
                CommandValidationErrorCode.PAYLOAD_SCHEMA_UNSUPPORTED,
                "payload.schemaVersion",
                "Unsupported action outcome command schema: ${payload.schemaVersion}",
            )
        }
        if (state.lifecycle != RunLifecycle.ACTIVE) {
            return invalid(CommandValidationErrorCode.RUN_LIFECYCLE_INVALID, "payload", "Actions require an ACTIVE Run")
        }
        if (state.currentSceneId != payload.fromSceneId) {
            return invalid(
                CommandValidationErrorCode.CURRENT_SCENE_MISMATCH,
                "payload.fromSceneId",
                "Action does not start in the current scene",
            )
        }
        val expected = policy ?: return invalid(
            CommandValidationErrorCode.ACTION_POLICY_REQUIRED,
            "payload",
            "Validated playable action policy is required",
        )
        val matches = payload.actionId == expected.actionId &&
            payload.outcomeId == expected.outcomeId &&
            payload.fromSceneId == expected.fromSceneId &&
            payload.nextSceneId == expected.nextSceneId &&
            payload.objectiveIds == expected.objectiveIds &&
            payload.endingId == expected.endingId &&
            payload.participantIds == expected.participantIds
        if (!matches) {
            return invalid(
                CommandValidationErrorCode.ACTION_OUTCOME_MISMATCH,
                "payload",
                "Action outcome does not match the validated playable contract",
            )
        }
        if (payload.participantIds.distinct().size != payload.participantIds.size) {
            return invalid(CommandValidationErrorCode.DUPLICATE_FIELD, "payload.participantIds", "Scene participant is duplicated")
        }
        payload.participantIds.firstOrNull { it !in state.entities }?.let { participantId ->
            return invalid(
                CommandValidationErrorCode.PARTICIPANT_NOT_FOUND,
                "payload.participantIds",
                "Scene participant is not present in world state: $participantId",
            )
        }
        val playerId = state.playerEntityId ?: return invalid(
            CommandValidationErrorCode.ENTITY_NOT_FOUND,
            "state.playerEntityId",
            "ACTIVE Run has no player Entity",
        )
        return CommandValidationResult.Valid(ValidatedCommand.ApplyActionOutcome(envelope, payload, playerId))
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
        if (payload.initialSceneParticipantIds != creationPolicy.initialSceneParticipantIds ||
            payload.initialSceneParticipantIds.distinct().size != payload.initialSceneParticipantIds.size
        ) {
            return invalid(
                CommandValidationErrorCode.INITIAL_SCENE_MISMATCH,
                "payload.initialSceneParticipantIds",
                "Initial scene participants do not match the playable world contract",
            )
        }
        payload.initialSceneParticipantIds.firstOrNull { it !in state.entities }?.let { participantId ->
            return invalid(
                CommandValidationErrorCode.PARTICIPANT_NOT_FOUND,
                "payload.initialSceneParticipantIds",
                "Initial scene participant is not initialized: $participantId",
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
