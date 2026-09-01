package io.worldloom.rules

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionReferenceValue
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandEnvelope
import io.worldloom.world.CommandEnvelopeValidator
import io.worldloom.world.CommandPermission
import io.worldloom.world.CommandValidationError
import io.worldloom.world.CommandValidationErrorCode
import io.worldloom.world.EventEnvelope
import io.worldloom.world.EventId
import io.worldloom.world.EventReducer
import io.worldloom.world.GameCommandPayload
import io.worldloom.world.GameEventPayload
import io.worldloom.world.GameState
import io.worldloom.world.ModuleState
import io.worldloom.world.RunLifecycle
import io.worldloom.world.StateReductionError
import io.worldloom.world.StateReductionErrorCode
import io.worldloom.world.StateReductionResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

val SCENE_EXPLORATION_MODULE_ID: DefinitionId = DefinitionId("worldloom.rules.scene-exploration")
val EXPLORATION_SCHEMA_CAPABILITY_ID: DefinitionId = DefinitionId("worldloom.schema.scene-exploration")
val EXPLORATION_COMMAND_CAPABILITY_ID: DefinitionId = DefinitionId("worldloom.command.exploration.reveal")
val EXPLORATION_EVENT_CAPABILITY_ID: DefinitionId = DefinitionId("worldloom.event.exploration.revealed")
val EXPLORATION_PROJECTION_CAPABILITY_ID: DefinitionId = DefinitionId("worldloom.projection.scene-exploration")
val EXPLORATION_EVENT_TYPE_ID: DefinitionId = DefinitionId("worldloom.event.exploration.revealed")

const val CURRENT_EXPLORATION_COMMAND_SCHEMA_VERSION: Int = 1
const val CURRENT_EXPLORATION_EVENT_SCHEMA_VERSION: Int = 1

@Serializable
enum class ExplorationKnowledgeKind { NODE, CONNECTION, AFFORDANCE }

@Serializable
enum class ExplorationKnowledgeLevel { RUMORED, DISCOVERED, BLOCKED, VISITED }

@Serializable
data class ExplorationKnowledgeChange(
    val kind: ExplorationKnowledgeKind,
    val id: DefinitionId,
    val level: ExplorationKnowledgeLevel,
)

@Serializable
@SerialName("reveal-exploration-knowledge")
data class RevealExplorationKnowledgeCommand(
    val schemaVersion: Int = CURRENT_EXPLORATION_COMMAND_SCHEMA_VERSION,
    val causeId: DefinitionId,
    val changes: List<ExplorationKnowledgeChange>,
) : GameCommandPayload

@Serializable
@SerialName("exploration-knowledge-revealed")
data class ExplorationKnowledgeRevealedEvent(
    val schemaVersion: Int = CURRENT_EXPLORATION_EVENT_SCHEMA_VERSION,
    val causeId: DefinitionId,
    val changes: List<ExplorationKnowledgeChange>,
) : GameEventPayload

data class ExplorationRevealPolicy(
    val causeId: DefinitionId,
    val allowedChanges: Set<ExplorationKnowledgeChange>,
)

data class ValidatedExplorationCommand(
    val envelope: CommandEnvelope,
    val payload: RevealExplorationKnowledgeCommand,
)

sealed interface ExplorationCommandValidationResult {
    data class Valid(val command: ValidatedExplorationCommand) : ExplorationCommandValidationResult
    data class Invalid(val error: CommandValidationError) : ExplorationCommandValidationResult
}

object ExplorationState {
    fun initialize(state: GameState): GameState = if (SCENE_EXPLORATION_MODULE_ID in state.moduleStates) {
        state
    } else {
        state.copy(moduleStates = state.moduleStates + (SCENE_EXPLORATION_MODULE_ID to ModuleState(emptyMap())))
    }

    fun level(state: GameState, id: DefinitionId): ExplorationKnowledgeLevel? =
        (state.moduleStates[SCENE_EXPLORATION_MODULE_ID]?.fields?.get(id) as? DefinitionReferenceValue)
            ?.value?.let(::levelFromDefinitionId)

    fun known(state: GameState): Map<DefinitionId, ExplorationKnowledgeLevel> =
        state.moduleStates[SCENE_EXPLORATION_MODULE_ID]?.fields.orEmpty().mapNotNull { (id, value) ->
            val level = (value as? DefinitionReferenceValue)?.value?.let(::levelFromDefinitionId)
            level?.let { id to it }
        }.toMap()

    fun canUpgrade(previous: ExplorationKnowledgeLevel?, next: ExplorationKnowledgeLevel): Boolean =
        previous == null || rank(next) > rank(previous)

    fun statusId(level: ExplorationKnowledgeLevel): DefinitionId =
        DefinitionId("worldloom.exploration.level.${level.name.lowercase()}")

    private fun levelFromDefinitionId(id: DefinitionId): ExplorationKnowledgeLevel? =
        ExplorationKnowledgeLevel.entries.firstOrNull { statusId(it) == id }

    private fun rank(level: ExplorationKnowledgeLevel): Int = when (level) {
        ExplorationKnowledgeLevel.RUMORED -> 1
        ExplorationKnowledgeLevel.DISCOVERED -> 2
        ExplorationKnowledgeLevel.BLOCKED -> 3
        ExplorationKnowledgeLevel.VISITED -> 4
    }
}

object ExplorationCommandValidator {
    fun validate(
        state: GameState,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        policy: ExplorationRevealPolicy?,
    ): ExplorationCommandValidationResult {
        CommandEnvelopeValidator.validate(state, authorization, envelope)?.let {
            return ExplorationCommandValidationResult.Invalid(it)
        }
        val payload = envelope.payload as? RevealExplorationKnowledgeCommand
            ?: return invalid(CommandValidationErrorCode.UNSUPPORTED_COMMAND_PAYLOAD, "payload", "Exploration validator requires a reveal command")
        if (payload.schemaVersion != CURRENT_EXPLORATION_COMMAND_SCHEMA_VERSION) {
            return invalid(CommandValidationErrorCode.PAYLOAD_SCHEMA_UNSUPPORTED, "payload.schemaVersion", "Unsupported exploration command schema")
        }
        if (CommandPermission.REVEAL_EXPLORATION_KNOWLEDGE !in authorization.permissions) {
            return invalid(CommandValidationErrorCode.PERMISSION_DENIED, "payload", "Actor lacks exploration reveal permission")
        }
        if (state.lifecycle != RunLifecycle.ACTIVE) {
            return invalid(CommandValidationErrorCode.RUN_LIFECYCLE_INVALID, "payload", "Exploration reveals require an ACTIVE Run")
        }
        if (policy == null) {
            return invalid(CommandValidationErrorCode.ACTION_POLICY_REQUIRED, "payload", "Exploration reveal policy is required")
        }
        if (payload.causeId != policy.causeId) {
            return invalid(CommandValidationErrorCode.ACTION_OUTCOME_MISMATCH, "payload.causeId", "Exploration reveal cause does not match policy")
        }
        if (payload.changes.isEmpty() || payload.changes.size > 32 || payload.changes.map { it.id }.distinct().size != payload.changes.size) {
            return invalid(CommandValidationErrorCode.ACTION_OUTCOME_MISMATCH, "payload.changes", "Exploration changes must be unique and contain 1 to 32 entries")
        }
        payload.changes.forEachIndexed { index, change ->
            if (change !in policy.allowedChanges) {
                return invalid(CommandValidationErrorCode.FIELD_NOT_FOUND, "payload.changes[$index]", "Exploration knowledge is not authorized by the world contract")
            }
            if (!ExplorationState.canUpgrade(ExplorationState.level(state, change.id), change.level)) {
                return invalid(CommandValidationErrorCode.ACTION_OUTCOME_MISMATCH, "payload.changes[$index].level", "Exploration knowledge must reveal or upgrade a fact")
            }
        }
        return ExplorationCommandValidationResult.Valid(ValidatedExplorationCommand(envelope, payload))
    }

    private fun invalid(code: CommandValidationErrorCode, path: String, message: String) =
        ExplorationCommandValidationResult.Invalid(CommandValidationError(code, path, message))
}

object ExplorationRuleEngine {
    fun handle(command: ValidatedExplorationCommand, eventId: EventId): EventEnvelope = EventEnvelope(
        schemaVersion = io.worldloom.world.CURRENT_EVENT_SCHEMA_VERSION,
        eventId = eventId,
        runId = command.envelope.runId,
        sequence = command.envelope.expectedSequence + 1,
        causationId = command.envelope.commandId,
        correlationId = command.envelope.correlationId ?: command.envelope.commandId.value,
        payload = ExplorationKnowledgeRevealedEvent(
            causeId = command.payload.causeId,
            changes = command.payload.changes,
        ),
    )
}

object ExplorationEventReducer : EventReducer {
    override fun supports(payload: GameEventPayload): Boolean = payload is ExplorationKnowledgeRevealedEvent

    override fun reduce(
        state: GameState,
        definition: ValidatedWorldDefinition,
        event: EventEnvelope,
    ): StateReductionResult {
        val payload = event.payload as ExplorationKnowledgeRevealedEvent
        if (event.runId != state.runId) return failure(StateReductionErrorCode.RUN_MISMATCH, "runId", "Exploration event Run mismatch")
        if (event.sequence != state.lastSequence + 1) return failure(StateReductionErrorCode.SEQUENCE_MISMATCH, "sequence", "Exploration event sequence mismatch")
        if (payload.schemaVersion != CURRENT_EXPLORATION_EVENT_SCHEMA_VERSION) {
            return failure(StateReductionErrorCode.PAYLOAD_SCHEMA_UNSUPPORTED, "payload.schemaVersion", "Unsupported exploration event schema")
        }
        if (payload.changes.isEmpty() || payload.changes.map { it.id }.distinct().size != payload.changes.size) {
            return failure(StateReductionErrorCode.UNSUPPORTED_EVENT_PAYLOAD, "payload.changes", "Exploration event changes must be unique and non-empty")
        }
        val current = ExplorationState.initialize(state)
        val fields = current.moduleStates.getValue(SCENE_EXPLORATION_MODULE_ID).fields.toMutableMap()
        payload.changes.forEachIndexed { index, change ->
            val previous = ExplorationState.level(current.copy(moduleStates = current.moduleStates + (
                SCENE_EXPLORATION_MODULE_ID to ModuleState(fields.toMap())
            )), change.id)
            if (!ExplorationState.canUpgrade(previous, change.level)) {
                return failure(StateReductionErrorCode.PREVIOUS_VALUE_MISMATCH, "payload.changes[$index].level", "Exploration event does not upgrade knowledge")
            }
            fields[change.id] = DefinitionReferenceValue(ExplorationState.statusId(change.level))
        }
        return StateReductionResult.Success(
            current.copy(
                lastSequence = event.sequence,
                moduleStates = current.moduleStates + (
                    SCENE_EXPLORATION_MODULE_ID to ModuleState(fields.toMap())
                ),
            ),
        )
    }

    private fun failure(code: StateReductionErrorCode, path: String, message: String) =
        StateReductionResult.Failure(StateReductionError(code, path, message))
}
