package io.worldloom.world

import io.worldloom.definition.IntegerValue
import io.worldloom.definition.TypedValue
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.definition.ValueType
import io.worldloom.definition.valueType

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
    PAYLOAD_SCHEMA_UNSUPPORTED,
    RUN_LIFECYCLE_INVALID,
    PLAYER_ALREADY_EXISTS,
    PLAYER_MISMATCH,
    COMPONENT_ALREADY_EXISTS,
    DUPLICATE_FIELD,
    MISSING_REQUIRED_FIELD,
    UNSUPPORTED_EVENT_PAYLOAD,
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

interface EventReducer {
    fun supports(payload: GameEventPayload): Boolean

    fun reduce(
        state: GameState,
        definition: ValidatedWorldDefinition,
        event: EventEnvelope,
    ): StateReductionResult
}

class EventReducerChain(
    private val reducers: List<EventReducer>,
) : EventReducer {
    override fun supports(payload: GameEventPayload): Boolean = reducers.any { it.supports(payload) }

    override fun reduce(
        state: GameState,
        definition: ValidatedWorldDefinition,
        event: EventEnvelope,
    ): StateReductionResult = reducers.firstOrNull { it.supports(event.payload) }
        ?.reduce(state, definition, event)
        ?: StateReductionResult.Failure(
            StateReductionError(
                StateReductionErrorCode.UNSUPPORTED_EVENT_PAYLOAD,
                "payload",
                "No reducer is registered for the event payload",
            ),
        )
}

object StateReducer : EventReducer {
    override fun supports(payload: GameEventPayload): Boolean =
        payload is NumericComponentAdjustedEvent ||
            payload is RunLifecycleChangedEvent ||
            payload is PlayerEntityCreatedEvent ||
            payload is PlayerComponentInitializedEvent ||
            payload is PlayerEnteredInitialSceneEvent

    override fun reduce(
        state: GameState,
        definition: ValidatedWorldDefinition,
        event: EventEnvelope,
    ): StateReductionResult {
        validateEnvelope(state, event)?.let { return StateReductionResult.Failure(it) }
        return when (val payload = event.payload) {
            is NumericComponentAdjustedEvent -> reduceNumericAdjustment(state, definition, event, payload)
            is RunLifecycleChangedEvent -> reduceLifecycle(state, event, payload)
            is PlayerEntityCreatedEvent -> reducePlayerCreated(state, event, payload)
            is PlayerComponentInitializedEvent -> reducePlayerComponent(state, definition, event, payload)
            is PlayerEnteredInitialSceneEvent -> reduceInitialScene(state, event, payload)
            else -> failure(
                StateReductionErrorCode.UNSUPPORTED_EVENT_PAYLOAD,
                "payload",
                "Event payload is not handled by the core world reducer",
            )
        }
    }

    private fun reduceLifecycle(
        state: GameState,
        event: EventEnvelope,
        payload: RunLifecycleChangedEvent,
    ): StateReductionResult {
        if (payload.schemaVersion != CURRENT_RUN_LIFECYCLE_EVENT_SCHEMA_VERSION) {
            return failure(
                StateReductionErrorCode.PAYLOAD_SCHEMA_UNSUPPORTED,
                "payload.schemaVersion",
                "Unsupported Run lifecycle event schema: ${payload.schemaVersion}",
            )
        }
        if (payload.previousLifecycle != state.lifecycle ||
            !RunLifecycleTransitions.allows(state.lifecycle, payload.lifecycle)
        ) {
            return failure(
                StateReductionErrorCode.RUN_LIFECYCLE_INVALID,
                "payload.lifecycle",
                "Run lifecycle event does not follow the current lifecycle",
            )
        }
        if (payload.lifecycle == RunLifecycle.ACTIVE &&
            (state.playerEntityId == null || state.currentSceneId == null)
        ) {
            return failure(
                StateReductionErrorCode.RUN_LIFECYCLE_INVALID,
                "payload.lifecycle",
                "Run cannot activate before player and initial scene facts exist",
            )
        }
        return StateReductionResult.Success(
            state.copy(lastSequence = event.sequence, lifecycle = payload.lifecycle),
        )
    }

    private fun reducePlayerCreated(
        state: GameState,
        event: EventEnvelope,
        payload: PlayerEntityCreatedEvent,
    ): StateReductionResult {
        if (payload.schemaVersion != CURRENT_CHARACTER_CREATION_EVENT_SCHEMA_VERSION) {
            return failure(
                StateReductionErrorCode.PAYLOAD_SCHEMA_UNSUPPORTED,
                "payload.schemaVersion",
                "Unsupported character creation event schema: ${payload.schemaVersion}",
            )
        }
        if (state.lifecycle != RunLifecycle.CHARACTER_CREATION) {
            return failure(
                StateReductionErrorCode.RUN_LIFECYCLE_INVALID,
                "payload",
                "Player can only be created during CHARACTER_CREATION",
            )
        }
        if (state.playerEntityId != null || payload.entityId in state.entities) {
            return failure(
                StateReductionErrorCode.PLAYER_ALREADY_EXISTS,
                "payload.entityId",
                "Player Entity already exists",
            )
        }
        return StateReductionResult.Success(
            state.copy(
                lastSequence = event.sequence,
                playerEntityId = payload.entityId,
                entities = state.entities + (
                    payload.entityId to EntityState(payload.entityId, emptyMap())
                    ),
            ),
        )
    }

    private fun reducePlayerComponent(
        state: GameState,
        definition: ValidatedWorldDefinition,
        event: EventEnvelope,
        payload: PlayerComponentInitializedEvent,
    ): StateReductionResult {
        if (payload.schemaVersion != CURRENT_CHARACTER_CREATION_EVENT_SCHEMA_VERSION) {
            return failure(
                StateReductionErrorCode.PAYLOAD_SCHEMA_UNSUPPORTED,
                "payload.schemaVersion",
                "Unsupported character creation event schema: ${payload.schemaVersion}",
            )
        }
        if (state.lifecycle != RunLifecycle.CHARACTER_CREATION || state.playerEntityId != payload.entityId) {
            return failure(
                StateReductionErrorCode.PLAYER_MISMATCH,
                "payload.entityId",
                "Component target is not the player being created",
            )
        }
        val entity = state.entities[payload.entityId] ?: return failure(
            StateReductionErrorCode.ENTITY_NOT_FOUND,
            "payload.entityId",
            "Player Entity has not been created",
        )
        if (payload.component.definitionId in entity.components) {
            return failure(
                StateReductionErrorCode.COMPONENT_ALREADY_EXISTS,
                "payload.component.definitionId",
                "Player component was already initialized",
            )
        }
        val componentDefinition = definition.component(payload.component.definitionId) ?: return failure(
            StateReductionErrorCode.COMPONENT_NOT_FOUND,
            "payload.component.definitionId",
            "Player component is not defined",
        )
        val fieldsById = componentDefinition.fields.associateBy { it.id }
        val values = mutableMapOf<io.worldloom.definition.DefinitionId, TypedValue>()
        payload.component.fields.forEachIndexed { index, seed ->
            val value = seed.value
            if (values.put(seed.id, value) != null) {
                return failure(
                    StateReductionErrorCode.DUPLICATE_FIELD,
                    "payload.component.fields[$index].id",
                    "Player component field is duplicated",
                )
            }
            val fieldDefinition = fieldsById[seed.id] ?: return failure(
                StateReductionErrorCode.FIELD_NOT_FOUND,
                "payload.component.fields[$index].id",
                "Player field is not defined",
            )
            if (value.valueType() != fieldDefinition.valueType) {
                return failure(
                    StateReductionErrorCode.VALUE_TYPE_MISMATCH,
                    "payload.component.fields[$index].value",
                    "Player field type does not match its Definition",
                )
            }
            if (value is IntegerValue && CommandValidator.isOutOfRange(
                    value.value,
                    fieldDefinition.minInteger,
                    fieldDefinition.maxInteger,
                )
            ) {
                return failure(
                    StateReductionErrorCode.INTEGER_OUT_OF_RANGE,
                    "payload.component.fields[$index].value",
                    "Player field is outside declared bounds",
                )
            }
        }
        componentDefinition.fields.firstOrNull { it.required && it.id !in values }?.let { missing ->
            return failure(
                StateReductionErrorCode.MISSING_REQUIRED_FIELD,
                "payload.component.fields",
                "Missing required player field: ${missing.id}",
            )
        }
        val component = ComponentInstance(payload.component.definitionId, values.toMap())
        val updatedEntity = entity.copy(components = entity.components + (component.definitionId to component))
        return StateReductionResult.Success(
            state.copy(lastSequence = event.sequence, entities = state.entities + (payload.entityId to updatedEntity)),
        )
    }

    private fun reduceInitialScene(
        state: GameState,
        event: EventEnvelope,
        payload: PlayerEnteredInitialSceneEvent,
    ): StateReductionResult {
        if (payload.schemaVersion != CURRENT_CHARACTER_CREATION_EVENT_SCHEMA_VERSION) {
            return failure(
                StateReductionErrorCode.PAYLOAD_SCHEMA_UNSUPPORTED,
                "payload.schemaVersion",
                "Unsupported character creation event schema: ${payload.schemaVersion}",
            )
        }
        if (state.lifecycle != RunLifecycle.CHARACTER_CREATION || state.playerEntityId != payload.entityId) {
            return failure(
                StateReductionErrorCode.PLAYER_MISMATCH,
                "payload.entityId",
                "Initial scene target is not the player being created",
            )
        }
        if (payload.entityId !in state.entities) {
            return failure(StateReductionErrorCode.ENTITY_NOT_FOUND, "payload.entityId", "Player Entity not found")
        }
        return StateReductionResult.Success(
            state.copy(lastSequence = event.sequence, currentSceneId = payload.sceneId),
        )
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
