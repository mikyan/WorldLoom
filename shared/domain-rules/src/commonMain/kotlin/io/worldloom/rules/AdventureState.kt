package io.worldloom.rules

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionReferenceValue
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.TextValue
import io.worldloom.definition.TypedValue
import io.worldloom.definition.ValidatedWorldDefinition
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
import io.worldloom.world.RunLifecycle
import io.worldloom.world.StateReductionError
import io.worldloom.world.StateReductionErrorCode
import io.worldloom.world.StateReductionResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val CURRENT_ADVENTURE_STATE_SCHEMA_VERSION: Int = 1
const val CURRENT_ADVENTURE_COMMAND_SCHEMA_VERSION: Int = 1
const val CURRENT_ADVENTURE_EVENT_SCHEMA_VERSION: Int = 1

val INVENTORY_MODULE_ID = DefinitionId("worldloom.rules.inventory")
val CONDITION_MODULE_ID = DefinitionId("worldloom.rules.condition")
val RELATIONSHIP_MODULE_ID = DefinitionId("worldloom.rules.relationship")
val QUEST_MODULE_ID = DefinitionId("worldloom.rules.quest")
val PROGRESS_CLOCK_MODULE_ID = DefinitionId("worldloom.rules.progress-clock")

val INVENTORY_CAPABILITY_ID = DefinitionId("worldloom.command.inventory.change")
val CONDITION_CAPABILITY_ID = DefinitionId("worldloom.command.condition.update")
val RELATIONSHIP_CAPABILITY_ID = DefinitionId("worldloom.command.relationship.adjust")
val QUEST_CAPABILITY_ID = DefinitionId("worldloom.command.quest.advance")
val PROGRESS_CLOCK_CAPABILITY_ID = DefinitionId("worldloom.command.progress-clock.advance")

@Serializable
enum class AdventureVisibility { PUBLIC, PLAYER, PRIVATE }

@Serializable
data class ItemDefinition(val id: DefinitionId, val label: String, val unitWeight: Long = 1)

@Serializable
data class ItemStackDefinition(val itemId: DefinitionId, val quantity: Long)

@Serializable
data class InventoryDefinition(
    val ownerEntityId: EntityId,
    val capacity: Long,
    val initialStacks: List<ItemStackDefinition> = emptyList(),
)

@Serializable
data class ConditionDefinition(
    val id: DefinitionId,
    val label: String,
    val maximumStacks: Long = 1,
    val durationMinutes: Long? = null,
    val visibility: AdventureVisibility = AdventureVisibility.PUBLIC,
)

@Serializable
data class RelationshipDefinition(
    val id: DefinitionId,
    val label: String,
    val sourceEntityId: EntityId,
    val targetEntityId: EntityId,
    val minimum: Long,
    val maximum: Long,
    val initialValue: Long,
    val visibility: AdventureVisibility = AdventureVisibility.PLAYER,
)

@Serializable
enum class QuestStatus { NOT_STARTED, ACTIVE, COMPLETED, FAILED }

@Serializable
data class QuestStageDefinition(val id: DefinitionId, val label: String)

@Serializable
data class QuestDefinition(
    val id: DefinitionId,
    val label: String,
    val stages: List<QuestStageDefinition>,
    val visibility: AdventureVisibility = AdventureVisibility.PUBLIC,
)

@Serializable
data class ProgressClockDefinition(
    val id: DefinitionId,
    val label: String,
    val segments: Long,
    val initialValue: Long = 0,
    val visibility: AdventureVisibility = AdventureVisibility.PUBLIC,
)

@Serializable
data class AdventureEndingCondition(
    val endingId: DefinitionId,
    val completedQuestId: DefinitionId? = null,
    val clockId: DefinitionId? = null,
    val minimumClockValue: Long? = null,
)

@Serializable
data class AdventureStateDefinition(
    val schemaVersion: Int = CURRENT_ADVENTURE_STATE_SCHEMA_VERSION,
    val items: List<ItemDefinition> = emptyList(),
    val inventory: InventoryDefinition? = null,
    val conditions: List<ConditionDefinition> = emptyList(),
    val relationships: List<RelationshipDefinition> = emptyList(),
    val quests: List<QuestDefinition> = emptyList(),
    val clocks: List<ProgressClockDefinition> = emptyList(),
    val endingConditions: List<AdventureEndingCondition> = emptyList(),
)

enum class AdventureDefinitionProblemCode {
    UNSUPPORTED_SCHEMA,
    DUPLICATE_ID,
    BLANK_LABEL,
    INVALID_BOUND,
    ENTITY_UNKNOWN,
    REFERENCE_UNKNOWN,
    ENDING_UNKNOWN,
}

data class AdventureDefinitionProblem(
    val code: AdventureDefinitionProblemCode,
    val path: String,
    val message: String,
)

sealed interface AdventureDefinitionValidationResult {
    data object Valid : AdventureDefinitionValidationResult
    data class Invalid(val problems: List<AdventureDefinitionProblem>) : AdventureDefinitionValidationResult
}

object AdventureStateDefinitionValidator {
    fun validate(
        source: AdventureStateDefinition,
        world: ValidatedWorldDefinition,
        endingIds: Set<DefinitionId>,
    ): AdventureDefinitionValidationResult {
        val problems = mutableListOf<AdventureDefinitionProblem>()
        if (source.schemaVersion != CURRENT_ADVENTURE_STATE_SCHEMA_VERSION) {
            problems += problem(AdventureDefinitionProblemCode.UNSUPPORTED_SCHEMA, "schemaVersion", "Unsupported adventure-state schema")
        }
        validateDefinitions(source.items.map { it.id to it.label }, "items", problems)
        validateDefinitions(source.conditions.map { it.id to it.label }, "conditions", problems)
        validateDefinitions(source.relationships.map { it.id to it.label }, "relationships", problems)
        validateDefinitions(source.quests.map { it.id to it.label }, "quests", problems)
        validateDefinitions(source.clocks.map { it.id to it.label }, "clocks", problems)
        val itemIds = source.items.map(ItemDefinition::id).toSet()
        source.items.forEachIndexed { index, item ->
            if (item.unitWeight <= 0) problems += problem(
                AdventureDefinitionProblemCode.INVALID_BOUND,
                "items[$index].unitWeight",
                "Item unit weight must be positive",
            )
        }
        source.inventory?.let { inventory ->
            if (world.source.initialEntities.none { it.entityId == inventory.ownerEntityId.value }) {
                problems += problem(AdventureDefinitionProblemCode.ENTITY_UNKNOWN, "inventory.ownerEntityId", "Inventory owner is not initialized")
            }
            if (inventory.capacity < 0) problems += problem(
                AdventureDefinitionProblemCode.INVALID_BOUND,
                "inventory.capacity",
                "Inventory capacity cannot be negative",
            )
            duplicate(inventory.initialStacks.map(ItemStackDefinition::itemId), "inventory.initialStacks", problems)
            var weight = 0L
            inventory.initialStacks.forEachIndexed { index, stack ->
                val item = source.items.firstOrNull { it.id == stack.itemId }
                if (item == null) problems += problem(
                    AdventureDefinitionProblemCode.REFERENCE_UNKNOWN,
                    "inventory.initialStacks[$index].itemId",
                    "Inventory item is not defined",
                ) else if (stack.quantity < 0 || multiply(stack.quantity, item.unitWeight) == null) {
                    problems += problem(AdventureDefinitionProblemCode.INVALID_BOUND, "inventory.initialStacks[$index]", "Inventory stack is invalid")
                } else {
                    weight = add(weight, stack.quantity * item.unitWeight) ?: Long.MAX_VALUE
                }
            }
            if (weight > inventory.capacity) problems += problem(
                AdventureDefinitionProblemCode.INVALID_BOUND,
                "inventory.initialStacks",
                "Initial inventory exceeds capacity",
            )
        }
        source.conditions.forEachIndexed { index, condition ->
            if (condition.maximumStacks <= 0 || (condition.durationMinutes != null && condition.durationMinutes <= 0)) {
                problems += problem(AdventureDefinitionProblemCode.INVALID_BOUND, "conditions[$index]", "Condition bounds must be positive")
            }
        }
        val entityIds = world.source.initialEntities.map { EntityId(it.entityId) }.toSet()
        source.relationships.forEachIndexed { index, relationship ->
            if (relationship.sourceEntityId !in entityIds || relationship.targetEntityId !in entityIds ||
                relationship.sourceEntityId == relationship.targetEntityId
            ) problems += problem(AdventureDefinitionProblemCode.ENTITY_UNKNOWN, "relationships[$index]", "Relationship endpoints must be distinct initialized entities")
            if (relationship.minimum > relationship.maximum || relationship.initialValue !in relationship.minimum..relationship.maximum) {
                problems += problem(AdventureDefinitionProblemCode.INVALID_BOUND, "relationships[$index]", "Relationship initial value is outside its bounds")
            }
        }
        source.quests.forEachIndexed { index, quest ->
            if (quest.stages.isEmpty()) problems += problem(AdventureDefinitionProblemCode.REFERENCE_UNKNOWN, "quests[$index].stages", "Quest requires at least one stage")
            duplicate(quest.stages.map(QuestStageDefinition::id), "quests[$index].stages", problems)
            quest.stages.forEachIndexed { stageIndex, stage ->
                if (stage.label.isBlank()) problems += problem(AdventureDefinitionProblemCode.BLANK_LABEL, "quests[$index].stages[$stageIndex].label", "Stage label cannot be blank")
            }
        }
        source.clocks.forEachIndexed { index, clock ->
            if (clock.segments <= 0 || clock.initialValue !in 0..clock.segments) problems += problem(
                AdventureDefinitionProblemCode.INVALID_BOUND,
                "clocks[$index]",
                "Clock initial value must be within its positive segment count",
            )
        }
        val questIds = source.quests.map(QuestDefinition::id).toSet()
        val clocks = source.clocks.associateBy(ProgressClockDefinition::id)
        source.endingConditions.forEachIndexed { index, condition ->
            if (condition.endingId !in endingIds) problems += problem(AdventureDefinitionProblemCode.ENDING_UNKNOWN, "endingConditions[$index].endingId", "Ending is not defined")
            if (condition.completedQuestId == null && condition.clockId == null) problems += problem(
                AdventureDefinitionProblemCode.REFERENCE_UNKNOWN,
                "endingConditions[$index]",
                "Ending condition requires a quest or clock predicate",
            )
            if (condition.completedQuestId != null && condition.completedQuestId !in questIds) problems += problem(
                AdventureDefinitionProblemCode.REFERENCE_UNKNOWN,
                "endingConditions[$index].completedQuestId",
                "Ending quest is not defined",
            )
            val clock = condition.clockId?.let(clocks::get)
            if (condition.clockId != null && clock == null) problems += problem(
                AdventureDefinitionProblemCode.REFERENCE_UNKNOWN,
                "endingConditions[$index].clockId",
                "Ending clock is not defined",
            )
            if (condition.clockId != null && (condition.minimumClockValue == null ||
                    condition.minimumClockValue !in 0..(clock?.segments ?: -1))
            ) problems += problem(AdventureDefinitionProblemCode.INVALID_BOUND, "endingConditions[$index].minimumClockValue", "Ending clock threshold is invalid")
        }
        if (source.inventory != null && itemIds.isEmpty()) problems += problem(
            AdventureDefinitionProblemCode.REFERENCE_UNKNOWN,
            "items",
            "Configured inventory requires item definitions",
        )
        return if (problems.isEmpty()) AdventureDefinitionValidationResult.Valid else AdventureDefinitionValidationResult.Invalid(problems)
    }

    private fun validateDefinitions(
        definitions: List<Pair<DefinitionId, String>>,
        path: String,
        problems: MutableList<AdventureDefinitionProblem>,
    ) {
        duplicate(definitions.map { it.first }, path, problems)
        definitions.forEachIndexed { index, (_, label) ->
            if (label.isBlank()) problems += problem(AdventureDefinitionProblemCode.BLANK_LABEL, "$path[$index].label", "Label cannot be blank")
        }
    }

    private fun duplicate(ids: List<DefinitionId>, path: String, problems: MutableList<AdventureDefinitionProblem>) {
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
            problems += problem(AdventureDefinitionProblemCode.DUPLICATE_ID, path, "Definition ID is duplicated: $id")
        }
    }

    private fun problem(code: AdventureDefinitionProblemCode, path: String, message: String) = AdventureDefinitionProblem(code, path, message)
    private fun add(left: Long, right: Long): Long? = safeAdd(left, right)
    private fun multiply(left: Long, right: Long): Long? = if (left == 0L || right <= Long.MAX_VALUE / left) left * right else null
}

@Serializable
enum class InventoryOperation { ACQUIRE, LOSE, USE }

@Serializable
@SerialName("change-inventory")
data class ChangeInventoryCommand(
    val schemaVersion: Int = CURRENT_ADVENTURE_COMMAND_SCHEMA_VERSION,
    val itemId: DefinitionId,
    val quantity: Long,
    val operation: InventoryOperation,
) : GameCommandPayload

@Serializable
@SerialName("update-condition")
data class UpdateConditionCommand(
    val schemaVersion: Int = CURRENT_ADVENTURE_COMMAND_SCHEMA_VERSION,
    val conditionId: DefinitionId,
    val stackDelta: Long = 0,
    val elapsedMinutes: Long = 0,
) : GameCommandPayload

@Serializable
@SerialName("adjust-relationship")
data class AdjustRelationshipCommand(
    val schemaVersion: Int = CURRENT_ADVENTURE_COMMAND_SCHEMA_VERSION,
    val relationshipId: DefinitionId,
    val delta: Long,
) : GameCommandPayload

@Serializable
@SerialName("advance-quest")
data class AdvanceQuestCommand(
    val schemaVersion: Int = CURRENT_ADVENTURE_COMMAND_SCHEMA_VERSION,
    val questId: DefinitionId,
    val stageId: DefinitionId,
    val status: QuestStatus,
) : GameCommandPayload

@Serializable
@SerialName("advance-progress-clock")
data class AdvanceProgressClockCommand(
    val schemaVersion: Int = CURRENT_ADVENTURE_COMMAND_SCHEMA_VERSION,
    val clockId: DefinitionId,
    val delta: Long,
) : GameCommandPayload

@Serializable
@SerialName("inventory-changed")
data class InventoryChangedEvent(
    val schemaVersion: Int = CURRENT_ADVENTURE_EVENT_SCHEMA_VERSION,
    val itemId: DefinitionId,
    val operation: InventoryOperation,
    val previousQuantity: Long,
    val quantityDelta: Long,
    val quantity: Long,
) : GameEventPayload

@Serializable
@SerialName("condition-updated")
data class ConditionUpdatedEvent(
    val schemaVersion: Int = CURRENT_ADVENTURE_EVENT_SCHEMA_VERSION,
    val conditionId: DefinitionId,
    val previousStacks: Long,
    val stacks: Long,
    val previousRemainingMinutes: Long,
    val remainingMinutes: Long,
) : GameEventPayload

@Serializable
@SerialName("relationship-adjusted")
data class RelationshipAdjustedEvent(
    val schemaVersion: Int = CURRENT_ADVENTURE_EVENT_SCHEMA_VERSION,
    val relationshipId: DefinitionId,
    val previousValue: Long,
    val delta: Long,
    val value: Long,
) : GameEventPayload

@Serializable
@SerialName("quest-advanced")
data class QuestAdvancedEvent(
    val schemaVersion: Int = CURRENT_ADVENTURE_EVENT_SCHEMA_VERSION,
    val questId: DefinitionId,
    val previousStageId: DefinitionId? = null,
    val stageId: DefinitionId,
    val previousStatus: QuestStatus,
    val status: QuestStatus,
) : GameEventPayload

@Serializable
@SerialName("progress-clock-advanced")
data class ProgressClockAdvancedEvent(
    val schemaVersion: Int = CURRENT_ADVENTURE_EVENT_SCHEMA_VERSION,
    val clockId: DefinitionId,
    val previousValue: Long,
    val delta: Long,
    val value: Long,
) : GameEventPayload

@Serializable
@SerialName("adventure-ending-reached")
data class AdventureEndingReachedEvent(
    val schemaVersion: Int = CURRENT_ADVENTURE_EVENT_SCHEMA_VERSION,
    val endingId: DefinitionId,
) : GameEventPayload

sealed interface ValidatedAdventureCommand {
    val envelope: CommandEnvelope

    data class Inventory(
        override val envelope: CommandEnvelope,
        val payload: ChangeInventoryCommand,
        val previous: Long,
        val value: Long,
    ) : ValidatedAdventureCommand

    data class Condition(
        override val envelope: CommandEnvelope,
        val payload: UpdateConditionCommand,
        val previousStacks: Long,
        val stacks: Long,
        val previousRemaining: Long,
        val remaining: Long,
    ) : ValidatedAdventureCommand

    data class Relationship(
        override val envelope: CommandEnvelope,
        val payload: AdjustRelationshipCommand,
        val previous: Long,
        val value: Long,
    ) : ValidatedAdventureCommand

    data class Quest(
        override val envelope: CommandEnvelope,
        val payload: AdvanceQuestCommand,
        val previousStageId: DefinitionId?,
        val previousStatus: QuestStatus,
    ) : ValidatedAdventureCommand

    data class Clock(
        override val envelope: CommandEnvelope,
        val payload: AdvanceProgressClockCommand,
        val previous: Long,
        val value: Long,
    ) : ValidatedAdventureCommand
}

sealed interface AdventureCommandValidationResult {
    data class Valid(val command: ValidatedAdventureCommand) : AdventureCommandValidationResult
    data class Invalid(val error: CommandValidationError) : AdventureCommandValidationResult
}

object AdventureState {
    fun initialize(state: GameState, definition: AdventureStateDefinition): GameState {
        val modules = state.moduleStates.toMutableMap()
        definition.inventory?.let { inventory ->
            if (INVENTORY_MODULE_ID !in modules) {
                modules[INVENTORY_MODULE_ID] = ModuleState(
                    inventory.initialStacks.associate { it.itemId to IntegerValue(it.quantity) },
                )
            }
        }
        if (definition.conditions.isNotEmpty() && CONDITION_MODULE_ID !in modules) {
            modules[CONDITION_MODULE_ID] = ModuleState(buildMap {
                definition.conditions.forEach { condition ->
                    put(conditionStacksField(condition.id), IntegerValue(0))
                    put(conditionRemainingField(condition.id), IntegerValue(0))
                }
            })
        }
        if (definition.relationships.isNotEmpty() && RELATIONSHIP_MODULE_ID !in modules) {
            modules[RELATIONSHIP_MODULE_ID] = ModuleState(
                definition.relationships.associate { it.id to IntegerValue(it.initialValue) },
            )
        }
        if (definition.quests.isNotEmpty() && QUEST_MODULE_ID !in modules) {
            modules[QUEST_MODULE_ID] = ModuleState(buildMap {
                definition.quests.forEach { quest -> put(questStatusField(quest.id), TextValue(QuestStatus.NOT_STARTED.name)) }
            })
        }
        if (definition.clocks.isNotEmpty() && PROGRESS_CLOCK_MODULE_ID !in modules) {
            modules[PROGRESS_CLOCK_MODULE_ID] = ModuleState(
                definition.clocks.associate { it.id to IntegerValue(it.initialValue) },
            )
        }
        return state.copy(moduleStates = modules)
    }

    fun inventoryQuantity(state: GameState, itemId: DefinitionId): Long = integer(state, INVENTORY_MODULE_ID, itemId)
    fun conditionStacks(state: GameState, id: DefinitionId): Long = integer(state, CONDITION_MODULE_ID, conditionStacksField(id))
    fun conditionRemaining(state: GameState, id: DefinitionId): Long = integer(state, CONDITION_MODULE_ID, conditionRemainingField(id))
    fun relationshipValue(state: GameState, id: DefinitionId): Long = integer(state, RELATIONSHIP_MODULE_ID, id)
    fun questStatus(state: GameState, id: DefinitionId): QuestStatus = state.moduleStates[QUEST_MODULE_ID]
        ?.fields?.get(questStatusField(id))
        .let { (it as? TextValue)?.value }
        ?.let { runCatching { QuestStatus.valueOf(it) }.getOrNull() }
        ?: QuestStatus.NOT_STARTED
    fun questStage(state: GameState, id: DefinitionId): DefinitionId? =
        (state.moduleStates[QUEST_MODULE_ID]?.fields?.get(questStageField(id)) as? DefinitionReferenceValue)?.value
    fun clockValue(state: GameState, id: DefinitionId): Long = integer(state, PROGRESS_CLOCK_MODULE_ID, id)

    private fun integer(state: GameState, moduleId: DefinitionId, fieldId: DefinitionId): Long =
        (state.moduleStates[moduleId]?.fields?.get(fieldId) as? IntegerValue)?.value ?: 0
}

object AdventureCommandValidator {
    fun validate(
        state: GameState,
        modules: RegisteredWorldModules,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        definition: AdventureStateDefinition,
    ): AdventureCommandValidationResult {
        CommandEnvelopeValidator.validate(state, authorization, envelope)?.let { return AdventureCommandValidationResult.Invalid(it) }
        if (state.lifecycle != RunLifecycle.ACTIVE) return invalid(CommandValidationErrorCode.RUN_LIFECYCLE_INVALID, "payload", "Adventure commands require an ACTIVE Run")
        return when (val payload = envelope.payload) {
            is ChangeInventoryCommand -> inventory(state, modules, authorization, envelope, payload, definition)
            is UpdateConditionCommand -> condition(state, modules, authorization, envelope, payload, definition)
            is AdjustRelationshipCommand -> relationship(state, modules, authorization, envelope, payload, definition)
            is AdvanceQuestCommand -> quest(state, modules, authorization, envelope, payload, definition)
            is AdvanceProgressClockCommand -> clock(state, modules, authorization, envelope, payload, definition)
            else -> invalid(CommandValidationErrorCode.UNSUPPORTED_COMMAND_PAYLOAD, "payload", "Adventure validator received an unsupported command")
        }
    }

    private fun inventory(
        state: GameState,
        modules: RegisteredWorldModules,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        payload: ChangeInventoryCommand,
        definition: AdventureStateDefinition,
    ): AdventureCommandValidationResult {
        common(payload.schemaVersion, CommandPermission.MANAGE_INVENTORY, INVENTORY_CAPABILITY_ID, modules, authorization)?.let { return AdventureCommandValidationResult.Invalid(it) }
        val inventory = definition.inventory ?: return invalid(CommandValidationErrorCode.FIELD_NOT_FOUND, "payload", "Inventory is not configured")
        val item = definition.items.firstOrNull { it.id == payload.itemId }
            ?: return invalid(CommandValidationErrorCode.FIELD_NOT_FOUND, "payload.itemId", "Item is not defined")
        if (payload.quantity <= 0) return invalid(CommandValidationErrorCode.INTEGER_OUT_OF_RANGE, "payload.quantity", "Inventory quantity must be positive")
        val previous = AdventureState.inventoryQuantity(state, payload.itemId)
        val signed = if (payload.operation == InventoryOperation.ACQUIRE) payload.quantity else -payload.quantity
        val value = safeAdd(previous, signed)
            ?: return invalid(CommandValidationErrorCode.INTEGER_OVERFLOW, "payload.quantity", "Inventory quantity overflowed")
        if (value < 0) return invalid(CommandValidationErrorCode.INTEGER_OUT_OF_RANGE, "payload.quantity", "Inventory quantity cannot become negative")
        if (payload.operation == InventoryOperation.ACQUIRE) {
            var weight = 0L
            definition.items.forEach { candidate ->
                val quantity = if (candidate.id == item.id) value else AdventureState.inventoryQuantity(state, candidate.id)
                val itemWeight = multiply(quantity, candidate.unitWeight)
                    ?: return invalid(CommandValidationErrorCode.INTEGER_OVERFLOW, "payload.quantity", "Inventory weight overflowed")
                weight = safeAdd(weight, itemWeight)
                    ?: return invalid(CommandValidationErrorCode.INTEGER_OVERFLOW, "payload.quantity", "Inventory weight overflowed")
            }
            if (weight > inventory.capacity) return invalid(CommandValidationErrorCode.INTEGER_OUT_OF_RANGE, "payload.quantity", "Inventory capacity would be exceeded")
        }
        return AdventureCommandValidationResult.Valid(ValidatedAdventureCommand.Inventory(envelope, payload, previous, value))
    }

    private fun condition(
        state: GameState,
        modules: RegisteredWorldModules,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        payload: UpdateConditionCommand,
        definition: AdventureStateDefinition,
    ): AdventureCommandValidationResult {
        common(payload.schemaVersion, CommandPermission.UPDATE_CONDITION, CONDITION_CAPABILITY_ID, modules, authorization)?.let { return AdventureCommandValidationResult.Invalid(it) }
        val configured = definition.conditions.firstOrNull { it.id == payload.conditionId }
            ?: return invalid(CommandValidationErrorCode.FIELD_NOT_FOUND, "payload.conditionId", "Condition is not defined")
        if (payload.stackDelta == 0L && payload.elapsedMinutes <= 0) return invalid(CommandValidationErrorCode.INTEGER_OUT_OF_RANGE, "payload", "Condition update requires stacks or elapsed time")
        if (payload.elapsedMinutes < 0) return invalid(CommandValidationErrorCode.INTEGER_OUT_OF_RANGE, "payload.elapsedMinutes", "Elapsed time cannot be negative")
        val previousStacks = AdventureState.conditionStacks(state, configured.id)
        val stacks = safeAdd(previousStacks, payload.stackDelta)
            ?: return invalid(CommandValidationErrorCode.INTEGER_OVERFLOW, "payload.stackDelta", "Condition stacks overflowed")
        if (stacks !in 0..configured.maximumStacks) return invalid(CommandValidationErrorCode.INTEGER_OUT_OF_RANGE, "payload.stackDelta", "Condition stacks exceed configured bounds")
        val previousRemaining = AdventureState.conditionRemaining(state, configured.id)
        val remaining = when {
            stacks == 0L -> 0L
            payload.stackDelta > 0 && configured.durationMinutes != null -> configured.durationMinutes
            configured.durationMinutes == null -> 0L
            else -> (previousRemaining - payload.elapsedMinutes).coerceAtLeast(0)
        }
        val finalStacks = if (configured.durationMinutes != null && remaining == 0L && payload.elapsedMinutes > 0) 0 else stacks
        return AdventureCommandValidationResult.Valid(
            ValidatedAdventureCommand.Condition(envelope, payload, previousStacks, finalStacks, previousRemaining, remaining),
        )
    }

    private fun relationship(
        state: GameState,
        modules: RegisteredWorldModules,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        payload: AdjustRelationshipCommand,
        definition: AdventureStateDefinition,
    ): AdventureCommandValidationResult {
        common(payload.schemaVersion, CommandPermission.UPDATE_RELATIONSHIP, RELATIONSHIP_CAPABILITY_ID, modules, authorization)?.let { return AdventureCommandValidationResult.Invalid(it) }
        val configured = definition.relationships.firstOrNull { it.id == payload.relationshipId }
            ?: return invalid(CommandValidationErrorCode.FIELD_NOT_FOUND, "payload.relationshipId", "Relationship is not defined")
        if (payload.delta == 0L) return invalid(CommandValidationErrorCode.INTEGER_OUT_OF_RANGE, "payload.delta", "Relationship delta cannot be zero")
        val previous = AdventureState.relationshipValue(state, configured.id)
        val value = safeAdd(previous, payload.delta)
            ?: return invalid(CommandValidationErrorCode.INTEGER_OVERFLOW, "payload.delta", "Relationship value overflowed")
        if (value !in configured.minimum..configured.maximum) return invalid(CommandValidationErrorCode.INTEGER_OUT_OF_RANGE, "payload.delta", "Relationship value exceeds configured bounds")
        return AdventureCommandValidationResult.Valid(ValidatedAdventureCommand.Relationship(envelope, payload, previous, value))
    }

    private fun quest(
        state: GameState,
        modules: RegisteredWorldModules,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        payload: AdvanceQuestCommand,
        definition: AdventureStateDefinition,
    ): AdventureCommandValidationResult {
        common(payload.schemaVersion, CommandPermission.UPDATE_QUEST, QUEST_CAPABILITY_ID, modules, authorization)?.let { return AdventureCommandValidationResult.Invalid(it) }
        val configured = definition.quests.firstOrNull { it.id == payload.questId }
            ?: return invalid(CommandValidationErrorCode.FIELD_NOT_FOUND, "payload.questId", "Quest is not defined")
        val stageIndex = configured.stages.indexOfFirst { it.id == payload.stageId }
        if (stageIndex < 0) return invalid(CommandValidationErrorCode.FIELD_NOT_FOUND, "payload.stageId", "Quest stage is not defined")
        val previousStatus = AdventureState.questStatus(state, configured.id)
        val previousStage = AdventureState.questStage(state, configured.id)
        val previousIndex = configured.stages.indexOfFirst { it.id == previousStage }
        val valid = when (previousStatus) {
            QuestStatus.NOT_STARTED -> payload.status == QuestStatus.ACTIVE && stageIndex == 0
            QuestStatus.ACTIVE -> when (payload.status) {
                QuestStatus.ACTIVE -> stageIndex in previousIndex..(previousIndex + 1).coerceAtMost(configured.stages.lastIndex)
                QuestStatus.COMPLETED -> previousIndex == configured.stages.lastIndex && stageIndex == previousIndex
                QuestStatus.FAILED -> stageIndex == previousIndex
                QuestStatus.NOT_STARTED -> false
            }
            QuestStatus.COMPLETED, QuestStatus.FAILED -> false
        }
        if (!valid) return invalid(CommandValidationErrorCode.ACTION_OUTCOME_MISMATCH, "payload", "Quest transition is not allowed")
        return AdventureCommandValidationResult.Valid(ValidatedAdventureCommand.Quest(envelope, payload, previousStage, previousStatus))
    }

    private fun clock(
        state: GameState,
        modules: RegisteredWorldModules,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
        payload: AdvanceProgressClockCommand,
        definition: AdventureStateDefinition,
    ): AdventureCommandValidationResult {
        common(payload.schemaVersion, CommandPermission.ADVANCE_PROGRESS_CLOCK, PROGRESS_CLOCK_CAPABILITY_ID, modules, authorization)?.let { return AdventureCommandValidationResult.Invalid(it) }
        val configured = definition.clocks.firstOrNull { it.id == payload.clockId }
            ?: return invalid(CommandValidationErrorCode.FIELD_NOT_FOUND, "payload.clockId", "Progress clock is not defined")
        if (payload.delta == 0L) return invalid(CommandValidationErrorCode.INTEGER_OUT_OF_RANGE, "payload.delta", "Clock delta cannot be zero")
        val previous = AdventureState.clockValue(state, configured.id)
        val value = safeAdd(previous, payload.delta)
            ?: return invalid(CommandValidationErrorCode.INTEGER_OVERFLOW, "payload.delta", "Clock value overflowed")
        if (value !in 0..configured.segments) return invalid(CommandValidationErrorCode.INTEGER_OUT_OF_RANGE, "payload.delta", "Clock value exceeds configured segments")
        return AdventureCommandValidationResult.Valid(ValidatedAdventureCommand.Clock(envelope, payload, previous, value))
    }

    private fun common(
        schemaVersion: Int,
        permission: CommandPermission,
        capability: DefinitionId,
        modules: RegisteredWorldModules,
        authorization: CommandAuthorization,
    ): CommandValidationError? = when {
        schemaVersion != CURRENT_ADVENTURE_COMMAND_SCHEMA_VERSION -> error(CommandValidationErrorCode.PAYLOAD_SCHEMA_UNSUPPORTED, "payload.schemaVersion", "Unsupported adventure command schema")
        permission !in authorization.permissions -> error(CommandValidationErrorCode.PERMISSION_DENIED, "payload", "Actor lacks adventure-state permission")
        modules.capability(capability) == null -> error(CommandValidationErrorCode.PERMISSION_DENIED, "payload", "World manifest did not enable the adventure-state capability")
        else -> null
    }

    private fun invalid(code: CommandValidationErrorCode, path: String, message: String) =
        AdventureCommandValidationResult.Invalid(error(code, path, message))
    private fun error(code: CommandValidationErrorCode, path: String, message: String) = CommandValidationError(code, path, message)
}

object AdventureRuleEngine {
    fun requiredEventCount(command: ValidatedAdventureCommand, state: GameState, definition: AdventureStateDefinition): Int =
        payloads(command, state, definition).size

    fun handle(
        command: ValidatedAdventureCommand,
        state: GameState,
        definition: AdventureStateDefinition,
        eventIds: List<EventId>,
    ): List<EventEnvelope> {
        val payloads = payloads(command, state, definition)
        require(payloads.size == eventIds.size) { "Expected ${payloads.size} adventure event IDs" }
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

    private fun payloads(
        command: ValidatedAdventureCommand,
        state: GameState,
        definition: AdventureStateDefinition,
    ): List<GameEventPayload> {
        val primary: GameEventPayload = when (command) {
            is ValidatedAdventureCommand.Inventory -> InventoryChangedEvent(
                itemId = command.payload.itemId,
                operation = command.payload.operation,
                previousQuantity = command.previous,
                quantityDelta = command.value - command.previous,
                quantity = command.value,
            )
            is ValidatedAdventureCommand.Condition -> ConditionUpdatedEvent(
                conditionId = command.payload.conditionId,
                previousStacks = command.previousStacks,
                stacks = command.stacks,
                previousRemainingMinutes = command.previousRemaining,
                remainingMinutes = command.remaining,
            )
            is ValidatedAdventureCommand.Relationship -> RelationshipAdjustedEvent(
                relationshipId = command.payload.relationshipId,
                previousValue = command.previous,
                delta = command.payload.delta,
                value = command.value,
            )
            is ValidatedAdventureCommand.Quest -> QuestAdvancedEvent(
                questId = command.payload.questId,
                previousStageId = command.previousStageId,
                stageId = command.payload.stageId,
                previousStatus = command.previousStatus,
                status = command.payload.status,
            )
            is ValidatedAdventureCommand.Clock -> ProgressClockAdvancedEvent(
                clockId = command.payload.clockId,
                previousValue = command.previous,
                delta = command.payload.delta,
                value = command.value,
            )
        }
        val ending = matchingEnding(command, state, definition)
        return if (ending == null || state.endingId != null) {
            listOf(primary)
        } else {
            listOf(primary, AdventureEndingReachedEvent(endingId = ending))
        }
    }

    private fun matchingEnding(
        command: ValidatedAdventureCommand,
        state: GameState,
        definition: AdventureStateDefinition,
    ): DefinitionId? = definition.endingConditions.sortedBy { it.endingId.value }.firstOrNull { condition ->
        val questComplete = condition.completedQuestId?.let { questId ->
            if (command is ValidatedAdventureCommand.Quest && command.payload.questId == questId) {
                command.payload.status == QuestStatus.COMPLETED
            } else AdventureState.questStatus(state, questId) == QuestStatus.COMPLETED
        } ?: true
        val clockReached = condition.clockId?.let { clockId ->
            val value = if (command is ValidatedAdventureCommand.Clock && command.payload.clockId == clockId) command.value else AdventureState.clockValue(state, clockId)
            value >= requireNotNull(condition.minimumClockValue)
        } ?: true
        questComplete && clockReached
    }?.endingId
}

object AdventureEventReducer : EventReducer {
    override fun supports(payload: GameEventPayload): Boolean = payload is InventoryChangedEvent ||
        payload is ConditionUpdatedEvent || payload is RelationshipAdjustedEvent || payload is QuestAdvancedEvent ||
        payload is ProgressClockAdvancedEvent || payload is AdventureEndingReachedEvent

    override fun reduce(state: GameState, definition: ValidatedWorldDefinition, event: EventEnvelope): StateReductionResult {
        envelopeError(state, event)?.let { return StateReductionResult.Failure(it) }
        return when (val payload = event.payload) {
            is InventoryChangedEvent -> reduceInteger(
                state, event, payload.schemaVersion, INVENTORY_MODULE_ID, payload.itemId,
                payload.previousQuantity, payload.quantityDelta, payload.quantity,
            )
            is RelationshipAdjustedEvent -> reduceInteger(
                state, event, payload.schemaVersion, RELATIONSHIP_MODULE_ID, payload.relationshipId,
                payload.previousValue, payload.delta, payload.value,
            )
            is ProgressClockAdvancedEvent -> reduceInteger(
                state, event, payload.schemaVersion, PROGRESS_CLOCK_MODULE_ID, payload.clockId,
                payload.previousValue, payload.delta, payload.value,
            )
            is ConditionUpdatedEvent -> reduceCondition(state, event, payload)
            is QuestAdvancedEvent -> reduceQuest(state, event, payload)
            is AdventureEndingReachedEvent -> reduceEnding(state, event, payload)
            else -> failure(StateReductionErrorCode.UNSUPPORTED_EVENT_PAYLOAD, "payload", "Adventure reducer received an unsupported event")
        }
    }

    private fun reduceInteger(
        state: GameState,
        event: EventEnvelope,
        schemaVersion: Int,
        moduleId: DefinitionId,
        fieldId: DefinitionId,
        previous: Long,
        delta: Long,
        value: Long,
    ): StateReductionResult {
        if (schemaVersion != CURRENT_ADVENTURE_EVENT_SCHEMA_VERSION || safeAdd(previous, delta) != value) return failure(
            StateReductionErrorCode.INVALID_EVENT_ARITHMETIC, "payload", "Adventure integer event is invalid",
        )
        val module = state.moduleStates[moduleId] ?: return failure(StateReductionErrorCode.COMPONENT_NOT_FOUND, "moduleStates", "Adventure module state is not initialized")
        val stored = (module.fields[fieldId] as? IntegerValue)?.value ?: 0L
        if (stored != previous) return failure(StateReductionErrorCode.PREVIOUS_VALUE_MISMATCH, "payload.previousValue", "Adventure event does not follow stored state")
        return success(state, event, moduleId, module.copy(fields = module.fields + (fieldId to IntegerValue(value))))
    }

    private fun reduceCondition(state: GameState, event: EventEnvelope, payload: ConditionUpdatedEvent): StateReductionResult {
        if (payload.schemaVersion != CURRENT_ADVENTURE_EVENT_SCHEMA_VERSION || payload.stacks < 0 || payload.remainingMinutes < 0) return failure(
            StateReductionErrorCode.INVALID_EVENT_ARITHMETIC, "payload", "Condition event is invalid",
        )
        val module = state.moduleStates[CONDITION_MODULE_ID] ?: return failure(StateReductionErrorCode.COMPONENT_NOT_FOUND, "moduleStates", "Condition state is not initialized")
        val stacksField = conditionStacksField(payload.conditionId)
        val remainingField = conditionRemainingField(payload.conditionId)
        if ((module.fields[stacksField] as? IntegerValue)?.value != payload.previousStacks ||
            (module.fields[remainingField] as? IntegerValue)?.value != payload.previousRemainingMinutes
        ) return failure(StateReductionErrorCode.PREVIOUS_VALUE_MISMATCH, "payload", "Condition event does not follow stored state")
        val updated = module.copy(fields = module.fields + mapOf(
            stacksField to IntegerValue(payload.stacks),
            remainingField to IntegerValue(payload.remainingMinutes),
        ))
        return success(state, event, CONDITION_MODULE_ID, updated)
    }

    private fun reduceQuest(state: GameState, event: EventEnvelope, payload: QuestAdvancedEvent): StateReductionResult {
        if (payload.schemaVersion != CURRENT_ADVENTURE_EVENT_SCHEMA_VERSION) return failure(StateReductionErrorCode.PAYLOAD_SCHEMA_UNSUPPORTED, "payload.schemaVersion", "Unsupported quest event schema")
        val module = state.moduleStates[QUEST_MODULE_ID] ?: return failure(StateReductionErrorCode.COMPONENT_NOT_FOUND, "moduleStates", "Quest state is not initialized")
        if (AdventureState.questStatus(state, payload.questId) != payload.previousStatus || AdventureState.questStage(state, payload.questId) != payload.previousStageId) {
            return failure(StateReductionErrorCode.PREVIOUS_VALUE_MISMATCH, "payload", "Quest event does not follow stored state")
        }
        val updated = module.copy(fields = module.fields + mapOf(
            questStatusField(payload.questId) to TextValue(payload.status.name),
            questStageField(payload.questId) to DefinitionReferenceValue(payload.stageId),
        ))
        return success(state, event, QUEST_MODULE_ID, updated)
    }

    private fun reduceEnding(state: GameState, event: EventEnvelope, payload: AdventureEndingReachedEvent): StateReductionResult {
        if (payload.schemaVersion != CURRENT_ADVENTURE_EVENT_SCHEMA_VERSION || state.endingId != null) return failure(
            StateReductionErrorCode.INVALID_EVENT_ARITHMETIC, "payload", "Adventure ending event is invalid",
        )
        return StateReductionResult.Success(state.copy(lastSequence = event.sequence, endingId = payload.endingId))
    }

    private fun success(state: GameState, event: EventEnvelope, moduleId: DefinitionId, module: ModuleState) =
        StateReductionResult.Success(state.copy(lastSequence = event.sequence, moduleStates = state.moduleStates + (moduleId to module)))

    private fun envelopeError(state: GameState, event: EventEnvelope): StateReductionError? = when {
        event.schemaVersion != io.worldloom.world.CURRENT_EVENT_SCHEMA_VERSION -> StateReductionError(StateReductionErrorCode.UNSUPPORTED_SCHEMA_VERSION, "schemaVersion", "Unsupported event schema")
        event.runId != state.runId -> StateReductionError(StateReductionErrorCode.RUN_MISMATCH, "runId", "Event Run does not match state")
        event.sequence != state.lastSequence + 1 -> StateReductionError(StateReductionErrorCode.SEQUENCE_MISMATCH, "sequence", "Event sequence is not contiguous")
        else -> null
    }

    private fun failure(code: StateReductionErrorCode, path: String, message: String) =
        StateReductionResult.Failure(StateReductionError(code, path, message))
}

data class PresentedInventoryItem(val id: DefinitionId, val label: String, val quantity: Long)
data class PresentedCondition(val id: DefinitionId, val label: String, val stacks: Long, val remainingMinutes: Long)
data class PresentedRelationship(val id: DefinitionId, val label: String, val value: Long)
data class PresentedQuest(val id: DefinitionId, val label: String, val stageLabel: String?, val status: QuestStatus)
data class PresentedProgressClock(val id: DefinitionId, val label: String, val value: Long, val segments: Long)

data class AdventureStatePresentation(
    val inventory: List<PresentedInventoryItem>,
    val conditions: List<PresentedCondition>,
    val relationships: List<PresentedRelationship>,
    val quests: List<PresentedQuest>,
    val clocks: List<PresentedProgressClock>,
)

object AdventureStateProjector {
    fun project(state: GameState, definition: AdventureStateDefinition, includePrivate: Boolean = false): AdventureStatePresentation {
        fun visible(visibility: AdventureVisibility) = includePrivate || visibility != AdventureVisibility.PRIVATE
        return AdventureStatePresentation(
            inventory = definition.items.mapNotNull { item ->
                AdventureState.inventoryQuantity(state, item.id).takeIf { it > 0 }?.let { PresentedInventoryItem(item.id, item.label, it) }
            },
            conditions = definition.conditions.filter { visible(it.visibility) }.mapNotNull { condition ->
                val stacks = AdventureState.conditionStacks(state, condition.id)
                stacks.takeIf { it > 0 }?.let {
                    PresentedCondition(condition.id, condition.label, stacks, AdventureState.conditionRemaining(state, condition.id))
                }
            },
            relationships = definition.relationships.filter { visible(it.visibility) }.map {
                PresentedRelationship(it.id, it.label, AdventureState.relationshipValue(state, it.id))
            },
            quests = definition.quests.filter { visible(it.visibility) }.map { quest ->
                val stage = AdventureState.questStage(state, quest.id)
                PresentedQuest(quest.id, quest.label, quest.stages.firstOrNull { it.id == stage }?.label, AdventureState.questStatus(state, quest.id))
            },
            clocks = definition.clocks.filter { visible(it.visibility) }.map {
                PresentedProgressClock(it.id, it.label, AdventureState.clockValue(state, it.id), it.segments)
            },
        )
    }
}

private fun conditionStacksField(id: DefinitionId) = DefinitionId("${id.value}.stacks")
private fun conditionRemainingField(id: DefinitionId) = DefinitionId("${id.value}.remaining-minutes")
private fun questStatusField(id: DefinitionId) = DefinitionId("${id.value}.status")
private fun questStageField(id: DefinitionId) = DefinitionId("${id.value}.stage")

private fun safeAdd(left: Long, right: Long): Long? = when {
    right > 0 && left > Long.MAX_VALUE - right -> null
    right < 0 && left < Long.MIN_VALUE - right -> null
    else -> left + right
}

private fun multiply(left: Long, right: Long): Long? = when {
    left < 0 || right < 0 -> null
    left == 0L || right == 0L -> 0
    left <= Long.MAX_VALUE / right -> left * right
    else -> null
}
