package io.worldloom.rules

import io.worldloom.definition.BooleanValue
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.definition.ValueType
import io.worldloom.rules.module.api.RegisteredWorldModules
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandEnvelope
import io.worldloom.world.CommandEnvelopeValidator
import io.worldloom.world.CommandPermission
import io.worldloom.world.CommandValidationError
import io.worldloom.world.CommandValidationErrorCode
import io.worldloom.world.EntityId
import io.worldloom.world.EventEnvelope
import io.worldloom.world.EventId
import io.worldloom.world.EventReducer
import io.worldloom.world.GameCommandPayload
import io.worldloom.world.GameEventPayload
import io.worldloom.world.GameState
import io.worldloom.world.ModuleState
import io.worldloom.world.NumericComponentAdjustedEvent
import io.worldloom.world.PlayerEnteredSceneEvent
import io.worldloom.world.PlayerExitedSceneEvent
import io.worldloom.world.RunLifecycle
import io.worldloom.world.StateReductionError
import io.worldloom.world.StateReductionErrorCode
import io.worldloom.world.StateReductionResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val CURRENT_TEMPORAL_ADVENTURE_SCHEMA_VERSION: Int = 1
const val CURRENT_TEMPORAL_COMMAND_SCHEMA_VERSION: Int = 1
const val CURRENT_TEMPORAL_EVENT_SCHEMA_VERSION: Int = 1

val WORLD_TIME_MODULE_ID = DefinitionId("worldloom.rules.world-time")
val ACTIVITY_MODULE_ID = DefinitionId("worldloom.rules.activity")
val TRAVEL_MODULE_ID = DefinitionId("worldloom.rules.travel")
val WORLD_TIME_CAPABILITY_ID = DefinitionId("worldloom.command.time.advance")
val ACTIVITY_CAPABILITY_ID = DefinitionId("worldloom.command.activity.perform")
val TRAVEL_CAPABILITY_ID = DefinitionId("worldloom.command.travel.perform")
val WORLD_TIME_MINUTE_FIELD_ID = DefinitionId("worldloom.time.minute")

private const val MAX_SINGLE_TIME_ADVANCE_MINUTES: Long = 525_600

@Serializable
data class NumericEffectDefinition(
    val entityId: EntityId,
    val componentId: DefinitionId,
    val fieldId: DefinitionId,
    val delta: Long,
)

@Serializable
data class ActivityResolutionDefinition(
    val outcomeId: DefinitionId,
    val effects: List<NumericEffectDefinition> = emptyList(),
)

@Serializable
data class ActivityInterruptionDefinition(
    val outcomeId: DefinitionId,
    val elapsedMinutes: Long,
)

@Serializable
data class ActivityDefinition(
    val id: DefinitionId,
    val label: String,
    val durationMinutes: Long,
    val availableSceneIds: List<DefinitionId>,
    val checkProfileId: DefinitionId? = null,
    val resolutions: List<ActivityResolutionDefinition>,
    val interruption: ActivityInterruptionDefinition? = null,
)

@Serializable
data class TravelResolutionDefinition(
    val outcomeId: DefinitionId,
    val arrives: Boolean = true,
    val additionalDurationMinutes: Long = 0,
    val effects: List<NumericEffectDefinition> = emptyList(),
)

@Serializable
data class TravelRouteDefinition(
    val id: DefinitionId,
    val label: String,
    val fromSceneId: DefinitionId,
    val toSceneId: DefinitionId,
    val durationMinutes: Long,
    val checkProfileId: DefinitionId? = null,
    val resolutions: List<TravelResolutionDefinition>,
)

@Serializable
data class ScheduledTriggerDefinition(
    val id: DefinitionId,
    val label: String,
    val atMinute: Long,
    val effects: List<NumericEffectDefinition> = emptyList(),
)

/** Topic-neutral world-package configuration for explicit time, activities, routes, and schedules. */
@Serializable
data class TemporalAdventureDefinition(
    val schemaVersion: Int = CURRENT_TEMPORAL_ADVENTURE_SCHEMA_VERSION,
    val initialMinute: Long = 0,
    val activities: List<ActivityDefinition> = emptyList(),
    val routes: List<TravelRouteDefinition> = emptyList(),
    val scheduledTriggers: List<ScheduledTriggerDefinition> = emptyList(),
)

enum class TemporalDefinitionProblemCode {
    UNSUPPORTED_SCHEMA,
    INVALID_TIME,
    DUPLICATE_ID,
    BLANK_LABEL,
    SCENE_UNKNOWN,
    CHECK_UNKNOWN,
    OUTCOME_MISMATCH,
    EFFECT_TARGET_UNKNOWN,
    EFFECT_INVALID,
}

data class TemporalDefinitionProblem(
    val code: TemporalDefinitionProblemCode,
    val path: String,
    val message: String,
)

sealed interface TemporalDefinitionValidationResult {
    data object Valid : TemporalDefinitionValidationResult
    data class Invalid(val problems: List<TemporalDefinitionProblem>) : TemporalDefinitionValidationResult
}

object TemporalAdventureDefinitionValidator {
    fun validate(
        source: TemporalAdventureDefinition,
        definition: ValidatedWorldDefinition,
        sceneIds: Set<DefinitionId>,
    ): TemporalDefinitionValidationResult {
        val problems = mutableListOf<TemporalDefinitionProblem>()
        if (source.schemaVersion != CURRENT_TEMPORAL_ADVENTURE_SCHEMA_VERSION) {
            problems += problem(TemporalDefinitionProblemCode.UNSUPPORTED_SCHEMA, "schemaVersion", "Unsupported temporal definition schema")
        }
        if (source.initialMinute < 0) {
            problems += problem(TemporalDefinitionProblemCode.INVALID_TIME, "initialMinute", "Initial world time cannot be negative")
        }
        validateUnique(source.activities.map(ActivityDefinition::id), "activities", problems)
        validateUnique(source.routes.map(TravelRouteDefinition::id), "routes", problems)
        validateUnique(source.scheduledTriggers.map(ScheduledTriggerDefinition::id), "scheduledTriggers", problems)
        source.activities.forEachIndexed { index, activity ->
            val path = "activities[$index]"
            validateLabelAndDuration(activity.label, activity.durationMinutes, path, problems)
            if (activity.availableSceneIds.isEmpty()) {
                problems += problem(TemporalDefinitionProblemCode.SCENE_UNKNOWN, "$path.availableSceneIds", "Activity requires at least one scene")
            }
            activity.availableSceneIds.forEachIndexed { sceneIndex, sceneId ->
                if (sceneId !in sceneIds) problems += problem(
                    TemporalDefinitionProblemCode.SCENE_UNKNOWN,
                    "$path.availableSceneIds[$sceneIndex]",
                    "Activity scene is not defined: $sceneId",
                )
            }
            validateResolutions(
                path,
                activity.checkProfileId,
                activity.resolutions.map(ActivityResolutionDefinition::outcomeId),
                activity.resolutions.flatMap(ActivityResolutionDefinition::effects),
                definition,
                problems,
                activity.interruption?.outcomeId?.let(::setOf).orEmpty(),
            )
            activity.interruption?.let { interruption ->
                if (interruption.outcomeId !in activity.resolutions.map(ActivityResolutionDefinition::outcomeId)) {
                    problems += problem(
                        TemporalDefinitionProblemCode.OUTCOME_MISMATCH,
                        "$path.interruption.outcomeId",
                        "Interruption outcome must reference one configured activity resolution",
                    )
                }
                if (interruption.elapsedMinutes !in 1 until activity.durationMinutes) {
                    problems += problem(
                        TemporalDefinitionProblemCode.INVALID_TIME,
                        "$path.interruption.elapsedMinutes",
                        "Interruption time must be positive and shorter than the full activity",
                    )
                }
            }
        }
        source.routes.forEachIndexed { index, route ->
            val path = "routes[$index]"
            validateLabelAndDuration(route.label, route.durationMinutes, path, problems)
            if (route.fromSceneId !in sceneIds) problems += problem(
                TemporalDefinitionProblemCode.SCENE_UNKNOWN,
                "$path.fromSceneId",
                "Route origin is not defined: ${route.fromSceneId}",
            )
            if (route.toSceneId !in sceneIds) problems += problem(
                TemporalDefinitionProblemCode.SCENE_UNKNOWN,
                "$path.toSceneId",
                "Route destination is not defined: ${route.toSceneId}",
            )
            if (route.fromSceneId == route.toSceneId) problems += problem(
                TemporalDefinitionProblemCode.SCENE_UNKNOWN,
                "$path.toSceneId",
                "Route destination must differ from its origin",
            )
            validateResolutions(
                path,
                route.checkProfileId,
                route.resolutions.map(TravelResolutionDefinition::outcomeId),
                route.resolutions.flatMap(TravelResolutionDefinition::effects),
                definition,
                problems,
                emptySet(),
            )
            route.resolutions.forEachIndexed { resolutionIndex, resolution ->
                if (resolution.additionalDurationMinutes !in 0..MAX_SINGLE_TIME_ADVANCE_MINUTES) {
                    problems += problem(
                        TemporalDefinitionProblemCode.INVALID_TIME,
                        "$path.resolutions[$resolutionIndex].additionalDurationMinutes",
                        "Additional travel time is outside the supported range",
                    )
                }
            }
        }
        source.scheduledTriggers.forEachIndexed { index, trigger ->
            val path = "scheduledTriggers[$index]"
            if (trigger.id == WORLD_TIME_MINUTE_FIELD_ID) problems += problem(
                TemporalDefinitionProblemCode.DUPLICATE_ID,
                "$path.id",
                "Scheduled trigger ID collides with reserved world-time state",
            )
            if (trigger.label.isBlank()) problems += problem(
                TemporalDefinitionProblemCode.BLANK_LABEL,
                "$path.label",
                "Scheduled trigger label cannot be blank",
            )
            if (trigger.atMinute <= source.initialMinute) problems += problem(
                TemporalDefinitionProblemCode.INVALID_TIME,
                "$path.atMinute",
                "Scheduled trigger must occur after initial world time",
            )
            validateEffects(trigger.effects, "$path.effects", definition, problems)
        }
        return if (problems.isEmpty()) TemporalDefinitionValidationResult.Valid else {
            TemporalDefinitionValidationResult.Invalid(problems)
        }
    }

    private fun validateLabelAndDuration(
        label: String,
        duration: Long,
        path: String,
        problems: MutableList<TemporalDefinitionProblem>,
    ) {
        if (label.isBlank()) problems += problem(TemporalDefinitionProblemCode.BLANK_LABEL, "$path.label", "Label cannot be blank")
        if (duration !in 1..MAX_SINGLE_TIME_ADVANCE_MINUTES) problems += problem(
            TemporalDefinitionProblemCode.INVALID_TIME,
            "$path.durationMinutes",
            "Duration must be within 1..$MAX_SINGLE_TIME_ADVANCE_MINUTES minutes",
        )
    }

    private fun validateResolutions(
        path: String,
        checkProfileId: DefinitionId?,
        outcomeIds: List<DefinitionId>,
        effects: List<NumericEffectDefinition>,
        definition: ValidatedWorldDefinition,
        problems: MutableList<TemporalDefinitionProblem>,
        excludedOutcomeIds: Set<DefinitionId>,
    ) {
        validateUnique(outcomeIds, "$path.resolutions", problems)
        val normalOutcomeIds = outcomeIds.filterNot(excludedOutcomeIds::contains)
        if (checkProfileId == null) {
            if (normalOutcomeIds.size != 1) problems += problem(
                TemporalDefinitionProblemCode.OUTCOME_MISMATCH,
                "$path.resolutions",
                "An activity or route without a check requires exactly one outcome",
            )
        } else {
            val check = definition.checkProfile(checkProfileId)
            if (check == null) {
                problems += problem(TemporalDefinitionProblemCode.CHECK_UNKNOWN, "$path.checkProfileId", "Check profile is not defined")
            } else if (normalOutcomeIds.toSet() != check.outcomes.map { it.id }.toSet()) {
                problems += problem(
                    TemporalDefinitionProblemCode.OUTCOME_MISMATCH,
                    "$path.resolutions",
                    "Configured outcomes must exactly match the check profile",
                )
            }
        }
        validateEffects(effects, "$path.resolutions.effects", definition, problems)
    }

    private fun validateEffects(
        effects: List<NumericEffectDefinition>,
        path: String,
        definition: ValidatedWorldDefinition,
        problems: MutableList<TemporalDefinitionProblem>,
    ) {
        effects.forEachIndexed { index, effect ->
            val effectPath = "$path[$index]"
            val entity = definition.source.initialEntities.firstOrNull { it.entityId == effect.entityId.value }
            val component = entity?.components?.firstOrNull { it.definitionId == effect.componentId }
            val field = definition.field(effect.componentId, effect.fieldId)
            if (entity == null || component == null || component.fields.none { it.id == effect.fieldId } || field == null) {
                problems += problem(
                    TemporalDefinitionProblemCode.EFFECT_TARGET_UNKNOWN,
                    effectPath,
                    "Numeric effect target is not defined on the configured entity",
                )
            } else if (field.valueType != ValueType.INTEGER) {
                problems += problem(
                    TemporalDefinitionProblemCode.EFFECT_INVALID,
                    effectPath,
                    "Numeric effects require an INTEGER field",
                )
            }
            if (effect.delta == 0L) problems += problem(
                TemporalDefinitionProblemCode.EFFECT_INVALID,
                "$effectPath.delta",
                "Numeric effect delta cannot be zero",
            )
        }
    }

    private fun validateUnique(
        ids: List<DefinitionId>,
        path: String,
        problems: MutableList<TemporalDefinitionProblem>,
    ) {
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
            problems += problem(TemporalDefinitionProblemCode.DUPLICATE_ID, path, "Definition ID is duplicated: $id")
        }
    }

    private fun problem(code: TemporalDefinitionProblemCode, path: String, message: String) =
        TemporalDefinitionProblem(code, path, message)
}

@Serializable
@SerialName("advance-world-time")
data class AdvanceWorldTimeCommand(
    val schemaVersion: Int = CURRENT_TEMPORAL_COMMAND_SCHEMA_VERSION,
    val deltaMinutes: Long,
    val reasonId: DefinitionId,
) : GameCommandPayload

@Serializable
@SerialName("perform-activity")
data class PerformActivityCommand(
    val schemaVersion: Int = CURRENT_TEMPORAL_COMMAND_SCHEMA_VERSION,
    val activityId: DefinitionId,
    val outcomeId: DefinitionId,
    val interrupted: Boolean = false,
) : GameCommandPayload

@Serializable
@SerialName("travel-route")
data class TravelRouteCommand(
    val schemaVersion: Int = CURRENT_TEMPORAL_COMMAND_SCHEMA_VERSION,
    val routeId: DefinitionId,
    val outcomeId: DefinitionId,
    val destinationParticipantIds: List<EntityId> = emptyList(),
) : GameCommandPayload

@Serializable
@SerialName("world-time-advanced")
data class WorldTimeAdvancedEvent(
    val schemaVersion: Int = CURRENT_TEMPORAL_EVENT_SCHEMA_VERSION,
    val previousMinute: Long,
    val deltaMinutes: Long,
    val minute: Long,
    val reasonId: DefinitionId,
) : GameEventPayload

@Serializable
@SerialName("activity-completed")
data class ActivityCompletedEvent(
    val schemaVersion: Int = CURRENT_TEMPORAL_EVENT_SCHEMA_VERSION,
    val activityId: DefinitionId,
    val outcomeId: DefinitionId,
    val durationMinutes: Long,
    val interrupted: Boolean = false,
) : GameEventPayload

@Serializable
@SerialName("travel-started")
data class TravelStartedEvent(
    val schemaVersion: Int = CURRENT_TEMPORAL_EVENT_SCHEMA_VERSION,
    val routeId: DefinitionId,
    val fromSceneId: DefinitionId,
    val toSceneId: DefinitionId,
) : GameEventPayload

@Serializable
@SerialName("travel-completed")
data class TravelCompletedEvent(
    val schemaVersion: Int = CURRENT_TEMPORAL_EVENT_SCHEMA_VERSION,
    val routeId: DefinitionId,
    val outcomeId: DefinitionId,
    val arrived: Boolean,
) : GameEventPayload

@Serializable
@SerialName("scheduled-trigger-fired")
data class ScheduledTriggerFiredEvent(
    val schemaVersion: Int = CURRENT_TEMPORAL_EVENT_SCHEMA_VERSION,
    val triggerId: DefinitionId,
    val scheduledMinute: Long,
) : GameEventPayload

data class ValidatedNumericEffect(
    val source: NumericEffectDefinition,
    val previousValue: Long,
    val newValue: Long,
)

data class ValidatedScheduledTrigger(
    val source: ScheduledTriggerDefinition,
    val effects: List<ValidatedNumericEffect>,
)

sealed interface ValidatedTemporalCommand {
    val envelope: CommandEnvelope
    val previousMinute: Long
    val minute: Long
    val directEffects: List<ValidatedNumericEffect>
    val triggers: List<ValidatedScheduledTrigger>

    data class AdvanceTime(
        override val envelope: CommandEnvelope,
        val payload: AdvanceWorldTimeCommand,
        override val previousMinute: Long,
        override val minute: Long,
        override val directEffects: List<ValidatedNumericEffect>,
        override val triggers: List<ValidatedScheduledTrigger>,
    ) : ValidatedTemporalCommand

    data class Activity(
        override val envelope: CommandEnvelope,
        val payload: PerformActivityCommand,
        val definition: ActivityDefinition,
        val resolution: ActivityResolutionDefinition,
        val durationMinutes: Long,
        override val previousMinute: Long,
        override val minute: Long,
        override val directEffects: List<ValidatedNumericEffect>,
        override val triggers: List<ValidatedScheduledTrigger>,
    ) : ValidatedTemporalCommand

    data class Travel(
        override val envelope: CommandEnvelope,
        val payload: TravelRouteCommand,
        val definition: TravelRouteDefinition,
        val resolution: TravelResolutionDefinition,
        val playerEntityId: EntityId,
        override val previousMinute: Long,
        override val minute: Long,
        override val directEffects: List<ValidatedNumericEffect>,
        override val triggers: List<ValidatedScheduledTrigger>,
    ) : ValidatedTemporalCommand
}

sealed interface TemporalCommandValidationResult {
    data class Valid(val command: ValidatedTemporalCommand) : TemporalCommandValidationResult
    data class Invalid(val error: CommandValidationError) : TemporalCommandValidationResult
}

object TemporalState {
    fun initialize(state: GameState, definition: TemporalAdventureDefinition): GameState {
        if (state.moduleStates[WORLD_TIME_MODULE_ID] != null) return state
        return state.copy(
            moduleStates = state.moduleStates + (
                WORLD_TIME_MODULE_ID to ModuleState(
                    mapOf(WORLD_TIME_MINUTE_FIELD_ID to IntegerValue(definition.initialMinute)),
                )
                ),
        )
    }

    fun minute(state: GameState, definition: TemporalAdventureDefinition): Long =
        (state.moduleStates[WORLD_TIME_MODULE_ID]?.fields?.get(WORLD_TIME_MINUTE_FIELD_ID) as? IntegerValue)?.value
            ?: definition.initialMinute

    fun firedTriggerIds(state: GameState): Set<DefinitionId> = state.moduleStates[WORLD_TIME_MODULE_ID]
        ?.fields
        .orEmpty()
        .filterValues { (it as? BooleanValue)?.value == true }
        .keys - WORLD_TIME_MINUTE_FIELD_ID
}

object TemporalCommandValidator {
    fun validate(
        state: GameState,
        world: ValidatedWorldDefinition,
        modules: RegisteredWorldModules,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        definition: TemporalAdventureDefinition,
        expectedDestinationParticipants: List<EntityId> = emptyList(),
    ): TemporalCommandValidationResult {
        CommandEnvelopeValidator.validate(state, authorization, envelope)?.let { return TemporalCommandValidationResult.Invalid(it) }
        if (state.lifecycle != RunLifecycle.ACTIVE) return invalid(
            CommandValidationErrorCode.RUN_LIFECYCLE_INVALID,
            "payload",
            "Temporal commands require an ACTIVE Run",
        )
        return when (val payload = envelope.payload) {
            is AdvanceWorldTimeCommand -> validateAdvance(state, world, modules, authorization, envelope, payload, definition)
            is PerformActivityCommand -> validateActivity(state, world, modules, authorization, envelope, payload, definition)
            is TravelRouteCommand -> validateTravel(
                state,
                world,
                modules,
                authorization,
                envelope,
                payload,
                definition,
                expectedDestinationParticipants,
            )
            else -> invalid(
                CommandValidationErrorCode.UNSUPPORTED_COMMAND_PAYLOAD,
                "payload",
                "Temporal validator requires a time, activity, or travel command",
            )
        }
    }

    private fun validateAdvance(
        state: GameState,
        world: ValidatedWorldDefinition,
        modules: RegisteredWorldModules,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        payload: AdvanceWorldTimeCommand,
        definition: TemporalAdventureDefinition,
    ): TemporalCommandValidationResult {
        common(payload.schemaVersion, payload.deltaMinutes, CommandPermission.ADVANCE_WORLD_TIME, WORLD_TIME_CAPABILITY_ID, modules, authorization)
            ?.let { return TemporalCommandValidationResult.Invalid(it) }
        return validated(state, world, envelope, definition, payload.deltaMinutes, emptyList()) { previous, minute, direct, triggers ->
            ValidatedTemporalCommand.AdvanceTime(envelope, payload, previous, minute, direct, triggers)
        }
    }

    private fun validateActivity(
        state: GameState,
        world: ValidatedWorldDefinition,
        modules: RegisteredWorldModules,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        payload: PerformActivityCommand,
        temporal: TemporalAdventureDefinition,
    ): TemporalCommandValidationResult {
        common(payload.schemaVersion, 1, CommandPermission.PERFORM_ACTIVITY, ACTIVITY_CAPABILITY_ID, modules, authorization)
            ?.let { return TemporalCommandValidationResult.Invalid(it) }
        val activity = temporal.activities.firstOrNull { it.id == payload.activityId }
            ?: return invalid(CommandValidationErrorCode.FIELD_NOT_FOUND, "payload.activityId", "Activity is not defined")
        if (state.currentSceneId !in activity.availableSceneIds) return invalid(
            CommandValidationErrorCode.CURRENT_SCENE_MISMATCH,
            "payload.activityId",
            "Activity is not available in the current scene",
        )
        val resolution = activity.resolutions.firstOrNull { it.outcomeId == payload.outcomeId }
            ?: return invalid(CommandValidationErrorCode.FIELD_NOT_FOUND, "payload.outcomeId", "Activity outcome is not configured")
        val interruption = activity.interruption
        if (payload.interrupted && (interruption == null || payload.outcomeId != interruption.outcomeId)) return invalid(
            CommandValidationErrorCode.ACTION_OUTCOME_MISMATCH,
            "payload.outcomeId",
            "Interrupted activity requires its configured interruption outcome",
        )
        if (!payload.interrupted && interruption?.outcomeId == payload.outcomeId) return invalid(
            CommandValidationErrorCode.ACTION_OUTCOME_MISMATCH,
            "payload.outcomeId",
            "Interruption outcome requires interrupted=true",
        )
        val duration = if (payload.interrupted) interruption!!.elapsedMinutes else activity.durationMinutes
        return validated(state, world, envelope, temporal, duration, resolution.effects) { previous, minute, direct, triggers ->
            ValidatedTemporalCommand.Activity(envelope, payload, activity, resolution, duration, previous, minute, direct, triggers)
        }
    }

    private fun validateTravel(
        state: GameState,
        world: ValidatedWorldDefinition,
        modules: RegisteredWorldModules,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        payload: TravelRouteCommand,
        temporal: TemporalAdventureDefinition,
        expectedDestinationParticipants: List<EntityId>,
    ): TemporalCommandValidationResult {
        common(payload.schemaVersion, 1, CommandPermission.TRAVEL, TRAVEL_CAPABILITY_ID, modules, authorization)
            ?.let { return TemporalCommandValidationResult.Invalid(it) }
        val route = temporal.routes.firstOrNull { it.id == payload.routeId }
            ?: return invalid(CommandValidationErrorCode.FIELD_NOT_FOUND, "payload.routeId", "Travel route is not defined")
        if (state.currentSceneId != route.fromSceneId) return invalid(
            CommandValidationErrorCode.CURRENT_SCENE_MISMATCH,
            "payload.routeId",
            "Travel route does not start in the current scene",
        )
        if (payload.destinationParticipantIds != expectedDestinationParticipants) return invalid(
            CommandValidationErrorCode.ACTION_OUTCOME_MISMATCH,
            "payload.destinationParticipantIds",
            "Travel destination participants do not match the validated scene",
        )
        if (payload.destinationParticipantIds.distinct().size != payload.destinationParticipantIds.size ||
            payload.destinationParticipantIds.any { it !in state.entities }
        ) return invalid(
            CommandValidationErrorCode.PARTICIPANT_NOT_FOUND,
            "payload.destinationParticipantIds",
            "Travel destination participants must be unique existing entities",
        )
        val resolution = route.resolutions.firstOrNull { it.outcomeId == payload.outcomeId }
            ?: return invalid(CommandValidationErrorCode.FIELD_NOT_FOUND, "payload.outcomeId", "Travel outcome is not configured")
        val duration = safeAdd(route.durationMinutes, resolution.additionalDurationMinutes)
            ?: return invalid(CommandValidationErrorCode.INTEGER_OVERFLOW, "payload", "Travel duration overflowed")
        if (duration !in 1..MAX_SINGLE_TIME_ADVANCE_MINUTES) return invalid(
            CommandValidationErrorCode.INTEGER_OUT_OF_RANGE,
            "payload.routeId",
            "Travel duration is outside the supported range",
        )
        val playerId = state.playerEntityId
            ?: return invalid(CommandValidationErrorCode.ENTITY_NOT_FOUND, "state.playerEntityId", "ACTIVE Run has no player")
        return validated(state, world, envelope, temporal, duration, resolution.effects) { previous, minute, direct, triggers ->
            ValidatedTemporalCommand.Travel(envelope, payload, route, resolution, playerId, previous, minute, direct, triggers)
        }
    }

    private fun common(
        schemaVersion: Int,
        duration: Long,
        permission: CommandPermission,
        capabilityId: DefinitionId,
        modules: RegisteredWorldModules,
        authorization: CommandAuthorization,
    ): CommandValidationError? = when {
        schemaVersion != CURRENT_TEMPORAL_COMMAND_SCHEMA_VERSION -> error(
            CommandValidationErrorCode.PAYLOAD_SCHEMA_UNSUPPORTED,
            "payload.schemaVersion",
            "Unsupported temporal command schema",
        )
        permission !in authorization.permissions -> error(CommandValidationErrorCode.PERMISSION_DENIED, "payload", "Actor lacks temporal permission")
        modules.capability(capabilityId) == null -> error(
            CommandValidationErrorCode.PERMISSION_DENIED,
            "payload",
            "World manifest did not enable the temporal capability",
        )
        duration !in 1..MAX_SINGLE_TIME_ADVANCE_MINUTES -> error(
            CommandValidationErrorCode.INTEGER_OUT_OF_RANGE,
            "payload",
            "Temporal duration is outside the supported range",
        )
        else -> null
    }

    private fun validated(
        state: GameState,
        world: ValidatedWorldDefinition,
        envelope: CommandEnvelope,
        temporal: TemporalAdventureDefinition,
        duration: Long,
        effects: List<NumericEffectDefinition>,
        factory: (
            previous: Long,
            minute: Long,
            direct: List<ValidatedNumericEffect>,
            triggers: List<ValidatedScheduledTrigger>,
        ) -> ValidatedTemporalCommand,
    ): TemporalCommandValidationResult {
        val previous = TemporalState.minute(state, temporal)
        val minute = safeAdd(previous, duration)
            ?: return invalid(CommandValidationErrorCode.INTEGER_OVERFLOW, "payload", "World time overflowed")
        val values = mutableMapOf<EffectTarget, Long>()
        val direct = when (val result = validateEffects(state, world, effects, values)) {
            is EffectValidation.Valid -> result.effects
            is EffectValidation.Invalid -> return TemporalCommandValidationResult.Invalid(result.error)
        }
        val fired = TemporalState.firedTriggerIds(state)
        val triggers = temporal.scheduledTriggers
            .filter { it.id !in fired && it.atMinute > previous && it.atMinute <= minute }
            .sortedWith(compareBy(ScheduledTriggerDefinition::atMinute, { it.id.value }))
            .map { trigger ->
                when (val result = validateEffects(state, world, trigger.effects, values)) {
                    is EffectValidation.Valid -> ValidatedScheduledTrigger(trigger, result.effects)
                    is EffectValidation.Invalid -> return TemporalCommandValidationResult.Invalid(result.error)
                }
            }
        return TemporalCommandValidationResult.Valid(factory(previous, minute, direct, triggers))
    }

    private fun validateEffects(
        state: GameState,
        world: ValidatedWorldDefinition,
        effects: List<NumericEffectDefinition>,
        values: MutableMap<EffectTarget, Long>,
    ): EffectValidation {
        val validated = mutableListOf<ValidatedNumericEffect>()
        effects.forEachIndexed { index, effect ->
            val target = EffectTarget(effect.entityId, effect.componentId, effect.fieldId)
            val current = values[target] ?: run {
                val value = state.entities[effect.entityId]
                    ?.components?.get(effect.componentId)
                    ?.fields?.get(effect.fieldId) as? IntegerValue
                    ?: return EffectValidation.Invalid(
                        error(CommandValidationErrorCode.FIELD_NOT_FOUND, "effects[$index]", "Numeric effect target is unavailable"),
                    )
                value.value
            }
            val next = safeAdd(current, effect.delta) ?: return EffectValidation.Invalid(
                error(CommandValidationErrorCode.INTEGER_OVERFLOW, "effects[$index].delta", "Numeric effect overflowed"),
            )
            val field = world.field(effect.componentId, effect.fieldId) ?: return EffectValidation.Invalid(
                error(CommandValidationErrorCode.FIELD_NOT_FOUND, "effects[$index].fieldId", "Numeric effect field is not defined"),
            )
            val minimum = field.minInteger
            val maximum = field.maxInteger
            if ((minimum != null && next < minimum) || (maximum != null && next > maximum)) {
                return EffectValidation.Invalid(
                    error(CommandValidationErrorCode.INTEGER_OUT_OF_RANGE, "effects[$index]", "Numeric effect exceeds field bounds"),
                )
            }
            values[target] = next
            validated += ValidatedNumericEffect(effect, current, next)
        }
        return EffectValidation.Valid(validated)
    }

    private data class EffectTarget(val entityId: EntityId, val componentId: DefinitionId, val fieldId: DefinitionId)

    private sealed interface EffectValidation {
        data class Valid(val effects: List<ValidatedNumericEffect>) : EffectValidation
        data class Invalid(val error: CommandValidationError) : EffectValidation
    }

    private fun invalid(code: CommandValidationErrorCode, path: String, message: String) =
        TemporalCommandValidationResult.Invalid(error(code, path, message))

    private fun error(code: CommandValidationErrorCode, path: String, message: String) = CommandValidationError(code, path, message)

    private fun safeAdd(left: Long, right: Long): Long? = when {
        right > 0 && left > Long.MAX_VALUE - right -> null
        right < 0 && left < Long.MIN_VALUE - right -> null
        else -> left + right
    }
}

object TemporalRuleEngine {
    fun requiredEventCount(command: ValidatedTemporalCommand): Int = payloads(command).size

    fun handle(command: ValidatedTemporalCommand, eventIds: List<EventId>): List<EventEnvelope> {
        val payloads = payloads(command)
        require(eventIds.size == payloads.size) { "Expected ${payloads.size} temporal event IDs" }
        return payloads.mapIndexed { index, payload ->
            EventEnvelope(
                schemaVersion = io.worldloom.world.CURRENT_EVENT_SCHEMA_VERSION,
                eventId = eventIds[index],
                runId = command.envelope.runId,
                sequence = command.envelope.expectedSequence + index + 1L,
                causationId = command.envelope.commandId,
                correlationId = command.envelope.correlationId ?: command.envelope.commandId.value,
                payload = payload,
            )
        }
    }

    private fun payloads(command: ValidatedTemporalCommand): List<GameEventPayload> = buildList {
        when (command) {
            is ValidatedTemporalCommand.AdvanceTime -> add(
                WorldTimeAdvancedEvent(
                    previousMinute = command.previousMinute,
                    deltaMinutes = command.payload.deltaMinutes,
                    minute = command.minute,
                    reasonId = command.payload.reasonId,
                ),
            )
            is ValidatedTemporalCommand.Activity -> {
                add(
                    ActivityCompletedEvent(
                        activityId = command.payload.activityId,
                        outcomeId = command.payload.outcomeId,
                        durationMinutes = command.durationMinutes,
                        interrupted = command.payload.interrupted,
                    ),
                )
                add(
                    WorldTimeAdvancedEvent(
                        previousMinute = command.previousMinute,
                        deltaMinutes = command.durationMinutes,
                        minute = command.minute,
                        reasonId = command.payload.activityId,
                    ),
                )
            }
            is ValidatedTemporalCommand.Travel -> {
                add(TravelStartedEvent(routeId = command.payload.routeId, fromSceneId = command.definition.fromSceneId, toSceneId = command.definition.toSceneId))
                add(
                    WorldTimeAdvancedEvent(
                        previousMinute = command.previousMinute,
                        deltaMinutes = command.minute - command.previousMinute,
                        minute = command.minute,
                        reasonId = command.payload.routeId,
                    ),
                )
            }
        }
        addEffects(command.directEffects)
        command.triggers.forEach { trigger ->
            add(ScheduledTriggerFiredEvent(triggerId = trigger.source.id, scheduledMinute = trigger.source.atMinute))
            addEffects(trigger.effects)
        }
        if (command is ValidatedTemporalCommand.Travel) {
            if (command.resolution.arrives) {
                add(
                    PlayerExitedSceneEvent(
                        entityId = command.playerEntityId,
                        sceneId = command.definition.fromSceneId,
                    ),
                )
            }
            add(TravelCompletedEvent(routeId = command.payload.routeId, outcomeId = command.payload.outcomeId, arrived = command.resolution.arrives))
            if (command.resolution.arrives) {
                add(
                    PlayerEnteredSceneEvent(
                        entityId = command.playerEntityId,
                        sceneId = command.definition.toSceneId,
                        participantIds = command.payload.destinationParticipantIds,
                    ),
                )
            }
        }
    }

    private fun MutableList<GameEventPayload>.addEffects(effects: List<ValidatedNumericEffect>) {
        effects.forEach { effect ->
            add(
                NumericComponentAdjustedEvent(
                    entityId = effect.source.entityId,
                    componentId = effect.source.componentId,
                    fieldId = effect.source.fieldId,
                    previousValue = effect.previousValue,
                    delta = effect.source.delta,
                    newValue = effect.newValue,
                ),
            )
        }
    }
}

object TemporalEventReducer : EventReducer {
    override fun supports(payload: GameEventPayload): Boolean = payload is WorldTimeAdvancedEvent ||
        payload is ActivityCompletedEvent || payload is TravelStartedEvent || payload is TravelCompletedEvent ||
        payload is ScheduledTriggerFiredEvent

    override fun reduce(
        state: GameState,
        definition: ValidatedWorldDefinition,
        event: EventEnvelope,
    ): StateReductionResult {
        envelopeError(state, event)?.let { return StateReductionResult.Failure(it) }
        return when (val payload = event.payload) {
            is WorldTimeAdvancedEvent -> reduceTime(state, event, payload)
            is ScheduledTriggerFiredEvent -> reduceTrigger(state, event, payload)
            is ActivityCompletedEvent -> audit(state, event, payload.schemaVersion, payload.durationMinutes > 0)
            is TravelStartedEvent -> audit(state, event, payload.schemaVersion, payload.fromSceneId != payload.toSceneId)
            is TravelCompletedEvent -> audit(state, event, payload.schemaVersion, true)
            else -> failure(StateReductionErrorCode.UNSUPPORTED_EVENT_PAYLOAD, "payload", "Temporal reducer received an unsupported event")
        }
    }

    private fun reduceTime(state: GameState, event: EventEnvelope, payload: WorldTimeAdvancedEvent): StateReductionResult {
        if (payload.schemaVersion != CURRENT_TEMPORAL_EVENT_SCHEMA_VERSION || payload.deltaMinutes <= 0 ||
            payload.previousMinute > Long.MAX_VALUE - payload.deltaMinutes ||
            payload.previousMinute + payload.deltaMinutes != payload.minute
        ) return failure(StateReductionErrorCode.INVALID_EVENT_ARITHMETIC, "payload", "World time event arithmetic is invalid")
        val module = state.moduleStates[WORLD_TIME_MODULE_ID] ?: return failure(
            StateReductionErrorCode.COMPONENT_NOT_FOUND,
            "moduleStates",
            "World time module state is not initialized",
        )
        val stored = (module.fields[WORLD_TIME_MINUTE_FIELD_ID] as? IntegerValue)?.value
        if (stored == null || stored != payload.previousMinute) return failure(
            StateReductionErrorCode.PREVIOUS_VALUE_MISMATCH,
            "payload.previousMinute",
            "World time event does not follow stored time",
        )
        return StateReductionResult.Success(
            state.copy(
                lastSequence = event.sequence,
                moduleStates = state.moduleStates + (
                    WORLD_TIME_MODULE_ID to module.copy(
                        fields = module.fields + (WORLD_TIME_MINUTE_FIELD_ID to IntegerValue(payload.minute)),
                    )
                    ),
            ),
        )
    }

    private fun reduceTrigger(state: GameState, event: EventEnvelope, payload: ScheduledTriggerFiredEvent): StateReductionResult {
        if (payload.schemaVersion != CURRENT_TEMPORAL_EVENT_SCHEMA_VERSION || payload.scheduledMinute < 0 ||
            payload.triggerId == WORLD_TIME_MINUTE_FIELD_ID
        ) return failure(
            StateReductionErrorCode.INVALID_EVENT_ARITHMETIC,
            "payload",
            "Scheduled trigger event is invalid",
        )
        val module = state.moduleStates[WORLD_TIME_MODULE_ID] ?: return failure(
            StateReductionErrorCode.COMPONENT_NOT_FOUND,
            "moduleStates",
            "Scheduled trigger requires initialized world time",
        )
        val minute = (module.fields[WORLD_TIME_MINUTE_FIELD_ID] as? IntegerValue)?.value ?: return failure(
            StateReductionErrorCode.FIELD_NOT_FOUND,
            "moduleStates.worldTime",
            "Scheduled trigger requires current world time",
        )
        if (payload.scheduledMinute > minute || (module.fields[payload.triggerId] as? BooleanValue)?.value == true) return failure(
            StateReductionErrorCode.INVALID_EVENT_ARITHMETIC,
            "payload.triggerId",
            "Scheduled trigger is early or has already fired",
        )
        return StateReductionResult.Success(
            state.copy(
                lastSequence = event.sequence,
                moduleStates = state.moduleStates + (
                    WORLD_TIME_MODULE_ID to module.copy(fields = module.fields + (payload.triggerId to BooleanValue(true)))
                    ),
            ),
        )
    }

    private fun audit(state: GameState, event: EventEnvelope, schemaVersion: Int, valid: Boolean): StateReductionResult =
        if (schemaVersion != CURRENT_TEMPORAL_EVENT_SCHEMA_VERSION || !valid) {
            failure(StateReductionErrorCode.INVALID_EVENT_ARITHMETIC, "payload", "Temporal audit event is invalid")
        } else {
            StateReductionResult.Success(state.copy(lastSequence = event.sequence))
        }

    private fun envelopeError(state: GameState, event: EventEnvelope): StateReductionError? = when {
        event.schemaVersion != io.worldloom.world.CURRENT_EVENT_SCHEMA_VERSION -> StateReductionError(
            StateReductionErrorCode.UNSUPPORTED_SCHEMA_VERSION,
            "schemaVersion",
            "Unsupported event schema",
        )
        event.runId != state.runId -> StateReductionError(StateReductionErrorCode.RUN_MISMATCH, "runId", "Event Run does not match state")
        event.sequence != state.lastSequence + 1 -> StateReductionError(
            StateReductionErrorCode.SEQUENCE_MISMATCH,
            "sequence",
            "Temporal event sequence must immediately follow state",
        )
        else -> null
    }

    private fun failure(code: StateReductionErrorCode, path: String, message: String) =
        StateReductionResult.Failure(StateReductionError(code, path, message))
}
