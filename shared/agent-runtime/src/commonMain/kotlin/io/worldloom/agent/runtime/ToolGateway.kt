package io.worldloom.agent.runtime

import io.worldloom.application.ActionResult
import io.worldloom.application.GameSession
import io.worldloom.application.GameSessionCommand
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.SessionCommandContext
import io.worldloom.definition.BooleanValue
import io.worldloom.definition.DefinitionId
import io.worldloom.provider.api.ProviderToolCall
import io.worldloom.provider.api.ProviderToolDefinition
import io.worldloom.provider.api.ProviderToolParameter
import io.worldloom.provider.api.ProviderToolValueType
import io.worldloom.rules.module.api.RegisteredWorldModules
import io.worldloom.rules.InventoryOperation
import io.worldloom.rules.QuestStatus
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandPermission
import io.worldloom.world.EntityId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

val NUMERIC_ADJUST_TOOL_ID: DefinitionId = DefinitionId("worldloom.tool.numeric.adjust")
val RESOLVE_CHECK_TOOL_ID: DefinitionId = DefinitionId("worldloom.tool.check.resolve")
val PERFORM_ACTION_TOOL_ID: DefinitionId = DefinitionId("worldloom.tool.action.perform")
val ADVANCE_TIME_TOOL_ID: DefinitionId = DefinitionId("worldloom.tool.time.advance")
val PERFORM_ACTIVITY_TOOL_ID: DefinitionId = DefinitionId("worldloom.tool.activity.perform")
val TRAVEL_TOOL_ID: DefinitionId = DefinitionId("worldloom.tool.travel.perform")
val INVENTORY_TOOL_ID: DefinitionId = DefinitionId("worldloom.tool.inventory.change")
val CONDITION_TOOL_ID: DefinitionId = DefinitionId("worldloom.tool.condition.update")
val RELATIONSHIP_TOOL_ID: DefinitionId = DefinitionId("worldloom.tool.relationship.adjust")
val QUEST_TOOL_ID: DefinitionId = DefinitionId("worldloom.tool.quest.advance")
val PROGRESS_CLOCK_TOOL_ID: DefinitionId = DefinitionId("worldloom.tool.progress-clock.advance")

private val NUMERIC_STATE_MODULE_ID = DefinitionId("worldloom.core.numeric-state")
private val DIRECT_ADJUSTMENT_PARAMETER_ID = DefinitionId("worldloom.parameter.direct-adjustment")

enum class ToolGatewayErrorCode {
    SESSION_NOT_LOADED,
    TOOL_NOT_REGISTERED,
    TOOL_DISABLED,
    PERMISSION_DENIED,
    INVALID_ARGUMENTS,
    COMMAND_REJECTED,
}

data class ToolGatewayError(
    val code: ToolGatewayErrorCode,
    val message: String,
)

sealed interface ToolValidationResult {
    data object Valid : ToolValidationResult

    data class Invalid(val error: ToolGatewayError) : ToolValidationResult
}

sealed interface ToolInvocationResult {
    data class Success(
        val output: String,
        val worldChanged: Boolean,
    ) : ToolInvocationResult

    data class Failure(
        val error: ToolGatewayError,
        /** True when the primary command committed before a foreground follow-up failed. */
        val worldChanged: Boolean = false,
    ) : ToolInvocationResult
}

data class GameTurnFollowUpRequest(
    val runId: io.worldloom.world.RunId,
    val afterSequence: Long,
    val committedThroughSequence: Long,
)

data class PublicFollowUp(
    val source: String,
    val summary: String,
) {
    init {
        require(source.isNotBlank() && summary.isNotBlank()) { "Public follow-up fields must not be blank" }
    }
}

sealed interface GameTurnFollowUpResult {
    data class Completed(val publicResults: List<PublicFollowUp> = emptyList()) : GameTurnFollowUpResult
    data class Failed(val message: String) : GameTurnFollowUpResult
}

/**
 * Foreground hook for deterministic Behavior processing and visible NPC results after a tool
 * commits. Implementations may only change facts through their own authoritative command gateway.
 */
fun interface GameTurnFollowUpDispatcher {
    suspend fun dispatch(request: GameTurnFollowUpRequest): GameTurnFollowUpResult
}

private data object NoGameTurnFollowUps : GameTurnFollowUpDispatcher {
    override suspend fun dispatch(request: GameTurnFollowUpRequest) = GameTurnFollowUpResult.Completed()
}

interface AgentToolGateway {
    suspend fun availableTools(identity: AgentIdentity): List<ProviderToolDefinition>

    suspend fun validate(
        identity: AgentIdentity,
        call: ProviderToolCall,
    ): ToolValidationResult

    suspend fun invoke(
        identity: AgentIdentity,
        call: ProviderToolCall,
    ): ToolInvocationResult
}

class DefaultAgentToolGateway(
    private val session: GameSession,
    private val followUpDispatcher: GameTurnFollowUpDispatcher = NoGameTurnFollowUps,
) : AgentToolGateway {
    private val tools = StandardAgentTools.all.associateBy { it.definition.name }

    override suspend fun availableTools(identity: AgentIdentity): List<ProviderToolDefinition> {
        val context = session.commandContext() ?: return emptyList()
        return tools.values
            .filter { tool -> tool.available(context, identity) }
            .map { tool -> tool.definitionFor(context) }
            .sortedBy(ProviderToolDefinition::name)
    }

    override suspend fun validate(
        identity: AgentIdentity,
        call: ProviderToolCall,
    ): ToolValidationResult {
        val context = session.commandContext()
            ?: return invalid(ToolGatewayErrorCode.SESSION_NOT_LOADED, "Load a world before invoking tools")
        val tool = tools[call.name]
            ?: return invalid(ToolGatewayErrorCode.TOOL_NOT_REGISTERED, "Tool is not registered: ${call.name}")
        if (!tool.enabled(context)) {
            return invalid(ToolGatewayErrorCode.TOOL_DISABLED, "The world manifest did not enable ${call.name}")
        }
        if (tool.permission !in identity.permissions) {
            return invalid(ToolGatewayErrorCode.PERMISSION_DENIED, "Agent is not permitted to invoke ${call.name}")
        }
        validateArguments(tool.definitionFor(context), call.arguments)?.let { message ->
            return invalid(ToolGatewayErrorCode.INVALID_ARGUMENTS, message)
        }
        validateIdentifiers(call, context)?.let { message ->
            return invalid(ToolGatewayErrorCode.INVALID_ARGUMENTS, message)
        }
        return ToolValidationResult.Valid
    }

    override suspend fun invoke(
        identity: AgentIdentity,
        call: ProviderToolCall,
    ): ToolInvocationResult {
        when (val validation = validate(identity, call)) {
            ToolValidationResult.Valid -> Unit
            is ToolValidationResult.Invalid -> return ToolInvocationResult.Failure(validation.error)
        }
        val before = session.state.value as? GameSessionUiState.Ready
            ?: return ToolInvocationResult.Failure(
                ToolGatewayError(ToolGatewayErrorCode.SESSION_NOT_LOADED, "Active Run presentation is unavailable"),
            )
        val context = session.commandContext()
            ?: return ToolInvocationResult.Failure(
                ToolGatewayError(ToolGatewayErrorCode.SESSION_NOT_LOADED, "Active Run context is unavailable"),
            )
        val command = when (call.name) {
            NUMERIC_ADJUST_TOOL_ID.value -> GameSessionCommand.AdjustNumericComponent(
                entityId = EntityId(call.requireString("entityId")),
                componentId = DefinitionId(call.requireString("componentId")),
                fieldId = DefinitionId(call.requireString("fieldId")),
                delta = call.requireLong("delta"),
            )

            RESOLVE_CHECK_TOOL_ID.value -> GameSessionCommand.ResolveCheck(
                profileId = DefinitionId(call.requireString("profileId")),
                modifier = call.optionalLong("modifier") ?: 0,
            )

            PERFORM_ACTION_TOOL_ID.value -> GameSessionCommand.PerformAvailableAction(
                actionId = DefinitionId(call.requireString("actionId")),
                selectedOutcomeId = call.optionalString("outcomeId")?.let(::DefinitionId),
            )

            ADVANCE_TIME_TOOL_ID.value -> GameSessionCommand.AdvanceWorldTime(
                deltaMinutes = call.requireLong("deltaMinutes"),
            )

            PERFORM_ACTIVITY_TOOL_ID.value -> GameSessionCommand.PerformActivity(
                activityId = DefinitionId(call.requireString("activityId")),
                selectedOutcomeId = call.optionalString("outcomeId")?.let(::DefinitionId),
                interrupted = call.optionalBoolean("interrupted") ?: false,
            )

            TRAVEL_TOOL_ID.value -> GameSessionCommand.Travel(
                routeId = DefinitionId(call.requireString("routeId")),
                selectedOutcomeId = call.optionalString("outcomeId")?.let(::DefinitionId),
            )

            INVENTORY_TOOL_ID.value -> GameSessionCommand.ChangeInventory(
                itemId = DefinitionId(call.requireString("itemId")),
                quantity = call.requireLong("quantity"),
                operation = InventoryOperation.valueOf(call.requireString("operation")),
            )

            CONDITION_TOOL_ID.value -> GameSessionCommand.UpdateCondition(
                conditionId = DefinitionId(call.requireString("conditionId")),
                stackDelta = call.optionalLong("stackDelta") ?: 0,
                elapsedMinutes = call.optionalLong("elapsedMinutes") ?: 0,
            )

            RELATIONSHIP_TOOL_ID.value -> GameSessionCommand.AdjustRelationship(
                relationshipId = DefinitionId(call.requireString("relationshipId")),
                delta = call.requireLong("delta"),
            )

            QUEST_TOOL_ID.value -> GameSessionCommand.AdvanceQuest(
                questId = DefinitionId(call.requireString("questId")),
                stageId = DefinitionId(call.requireString("stageId")),
                status = QuestStatus.valueOf(call.requireString("status")),
            )

            PROGRESS_CLOCK_TOOL_ID.value -> GameSessionCommand.AdvanceProgressClock(
                clockId = DefinitionId(call.requireString("clockId")),
                delta = call.requireLong("delta"),
            )

            else -> return ToolInvocationResult.Failure(
                ToolGatewayError(ToolGatewayErrorCode.TOOL_NOT_REGISTERED, "Tool is not registered: ${call.name}"),
            )
        }
        return when (
            val result = session.execute(
                command,
                CommandAuthorization(identity.actorId, identity.permissions),
            )
        ) {
            ActionResult.Success -> {
                val committedThrough = session.commandContext()?.lastSequence ?: before.presentation.lastSequence
                when (
                    val followUps = followUpDispatcher.dispatch(
                        GameTurnFollowUpRequest(
                            runId = context.runId,
                            afterSequence = before.presentation.lastSequence,
                            committedThroughSequence = committedThrough,
                        ),
                    )
                ) {
                    is GameTurnFollowUpResult.Completed -> ToolInvocationResult.Success(
                        successOutput(followUps.publicResults),
                        worldChanged = true,
                    )
                    is GameTurnFollowUpResult.Failed -> ToolInvocationResult.Failure(
                        ToolGatewayError(ToolGatewayErrorCode.COMMAND_REJECTED, followUps.message),
                        worldChanged = true,
                    )
                }
            }
            is ActionResult.Failure -> ToolInvocationResult.Failure(
                ToolGatewayError(ToolGatewayErrorCode.COMMAND_REJECTED, result.error.message),
            )
        }
    }

    private fun StandardAgentTool.available(
        context: SessionCommandContext,
        identity: AgentIdentity,
    ): Boolean = enabled(context) && permission in identity.permissions

    private fun invalid(
        code: ToolGatewayErrorCode,
        message: String,
    ): ToolValidationResult.Invalid = ToolValidationResult.Invalid(ToolGatewayError(code, message))

    private fun successOutput(publicFollowUps: List<PublicFollowUp>): String {
        val ready = session.state.value as? GameSessionUiState.Ready
        return buildJsonObject {
            put("status", "success")
            put("worldChanged", true)
            ready?.presentation?.let { presentation ->
                put("lastSequence", presentation.lastSequence)
                put("visibleFields", buildJsonArray {
                    presentation.fields.forEach { field ->
                        add(buildJsonObject {
                            put("label", field.label)
                            put("value", field.value)
                        })
                    }
                })
                presentation.timeline.lastOrNull()?.let { event -> put("latestEvent", event.summary) }
                presentation.worldTimeMinutes?.let { minute -> put("worldTimeMinutes", minute) }
            }
            if (publicFollowUps.isNotEmpty()) {
                put("foregroundResults", buildJsonArray {
                    publicFollowUps.forEach { followUp ->
                        add(buildJsonObject {
                            put("source", followUp.source)
                            put("summary", followUp.summary)
                        })
                    }
                })
            }
        }.toString()
    }
}

private data class StandardAgentTool(
    val definition: ProviderToolDefinition,
    val capabilityId: DefinitionId,
    val permission: CommandPermission,
    val additionalAvailability: (RegisteredWorldModules) -> Boolean = { true },
) {
    fun enabled(context: SessionCommandContext): Boolean = if (capabilityId == PERFORM_ACTION_TOOL_ID) {
        context.availableActions.isNotEmpty()
    } else if (capabilityId == PERFORM_ACTIVITY_TOOL_ID) {
        context.availableActivities.isNotEmpty()
    } else if (capabilityId == TRAVEL_TOOL_ID) {
        context.availableTravelRoutes.isNotEmpty()
    } else if (capabilityId == INVENTORY_TOOL_ID) {
        context.adventureStateDefinition?.inventory != null
    } else if (capabilityId == CONDITION_TOOL_ID) {
        context.adventureStateDefinition?.conditions?.isNotEmpty() == true
    } else if (capabilityId == RELATIONSHIP_TOOL_ID) {
        context.adventureStateDefinition?.relationships?.isNotEmpty() == true
    } else if (capabilityId == QUEST_TOOL_ID) {
        context.adventureStateDefinition?.quests?.isNotEmpty() == true
    } else if (capabilityId == PROGRESS_CLOCK_TOOL_ID) {
        context.adventureStateDefinition?.clocks?.isNotEmpty() == true
    } else {
        enabledByManifest(context.modules)
    }

    fun enabledByManifest(modules: RegisteredWorldModules): Boolean =
        modules.capability(capabilityId) != null && additionalAvailability(modules)

    fun definitionFor(context: SessionCommandContext): ProviderToolDefinition = when (capabilityId) {
        NUMERIC_ADJUST_TOOL_ID -> definition.copy(
            parameters = definition.parameters.map { parameter ->
                val allowed = when (parameter.name) {
                    "entityId" -> context.adjustmentTargets.map { it.entityId.value }
                    "componentId" -> context.adjustmentTargets.map { it.componentId.value }
                    "fieldId" -> context.adjustmentTargets.map { it.fieldId.value }
                    else -> emptyList()
                }
                parameter.copy(allowedValues = allowed.distinct().sorted())
            },
        )

        RESOLVE_CHECK_TOOL_ID -> definition.copy(
            parameters = definition.parameters.map { parameter ->
                if (parameter.name == "profileId") {
                    parameter.copy(allowedValues = context.checkProfileIds.map { it.value }.distinct().sorted())
                } else {
                    parameter
                }
            },
        )

        PERFORM_ACTION_TOOL_ID -> definition.copy(
            parameters = definition.parameters.map { parameter ->
                when (parameter.name) {
                    "actionId" -> parameter.copy(
                        allowedValues = context.availableActions.map { it.actionId.value }.distinct().sorted(),
                    )
                    "outcomeId" -> parameter.copy(
                        allowedValues = context.availableActions
                            .filter { !it.requiresCheck }
                            .flatMap { it.outcomeIds }
                            .map { it.value }
                            .distinct()
                            .sorted(),
                    )
                    else -> parameter
                }
            },
        )

        PERFORM_ACTIVITY_TOOL_ID -> definition.copy(
            parameters = definition.parameters.map { parameter ->
                when (parameter.name) {
                    "activityId" -> parameter.copy(
                        allowedValues = context.availableActivities.map { it.activityId.value }.distinct().sorted(),
                    )
                    "outcomeId" -> parameter.copy(
                        allowedValues = context.availableActivities
                            .filter { !it.requiresCheck }
                            .flatMap { it.outcomeIds }
                            .plus(context.availableActivities.mapNotNull { it.interruptionOutcomeId })
                            .map { it.value }
                            .distinct()
                            .sorted(),
                    )
                    else -> parameter
                }
            },
        )

        TRAVEL_TOOL_ID -> definition.copy(
            parameters = definition.parameters.map { parameter ->
                when (parameter.name) {
                    "routeId" -> parameter.copy(
                        allowedValues = context.availableTravelRoutes.map { it.routeId.value }.distinct().sorted(),
                    )
                    "outcomeId" -> parameter.copy(
                        allowedValues = context.availableTravelRoutes
                            .filter { !it.requiresCheck }
                            .flatMap { it.outcomeIds }
                            .map { it.value }
                            .distinct()
                            .sorted(),
                    )
                    else -> parameter
                }
            },
        )

        INVENTORY_TOOL_ID -> definition.withAllowed("itemId", context.adventureStateDefinition?.items.orEmpty().map { it.id.value })
        CONDITION_TOOL_ID -> definition.withAllowed("conditionId", context.adventureStateDefinition?.conditions.orEmpty().map { it.id.value })
        RELATIONSHIP_TOOL_ID -> definition.withAllowed("relationshipId", context.adventureStateDefinition?.relationships.orEmpty().map { it.id.value })
        QUEST_TOOL_ID -> definition.copy(
            parameters = definition.parameters.map { parameter -> when (parameter.name) {
                "questId" -> parameter.copy(allowedValues = context.adventureStateDefinition?.quests.orEmpty().map { it.id.value })
                "stageId" -> parameter.copy(allowedValues = context.adventureStateDefinition?.quests.orEmpty().flatMap { quest -> quest.stages.map { it.id.value } })
                else -> parameter
            } },
        )
        PROGRESS_CLOCK_TOOL_ID -> definition.withAllowed("clockId", context.adventureStateDefinition?.clocks.orEmpty().map { it.id.value })

        else -> definition
    }

    private fun ProviderToolDefinition.withAllowed(name: String, values: List<String>) = copy(
        parameters = parameters.map { if (it.name == name) it.copy(allowedValues = values.distinct().sorted()) else it },
    )
}

private object StandardAgentTools {
    val all: List<StandardAgentTool> = listOf(
        StandardAgentTool(
            definition = ProviderToolDefinition(
                name = NUMERIC_ADJUST_TOOL_ID.value,
                description = "Adjust one integer field on a world entity through the authoritative command pipeline.",
                parameters = listOf(
                    stringParameter("entityId", "Stable entity identifier."),
                    stringParameter("componentId", "Namespaced component definition identifier."),
                    stringParameter("fieldId", "Namespaced integer field definition identifier."),
                    ProviderToolParameter("delta", "Signed integer adjustment.", ProviderToolValueType.INTEGER),
                ),
            ),
            capabilityId = NUMERIC_ADJUST_TOOL_ID,
            permission = CommandPermission.ADJUST_NUMERIC_COMPONENT,
            additionalAvailability = { modules ->
                val configured = modules.module(NUMERIC_STATE_MODULE_ID)
                    ?.parameters
                    ?.get(DIRECT_ADJUSTMENT_PARAMETER_ID) as? BooleanValue
                configured?.value == true
            },
        ),
        StandardAgentTool(
            definition = ProviderToolDefinition(
                name = RESOLVE_CHECK_TOOL_ID.value,
                description = "Resolve a configured check and append its auditable result event.",
                parameters = listOf(
                    stringParameter("profileId", "Namespaced check profile identifier."),
                    ProviderToolParameter(
                        name = "modifier",
                        description = "Optional signed modifier applied to the configured check.",
                        type = ProviderToolValueType.INTEGER,
                        required = false,
                    ),
                ),
            ),
            capabilityId = RESOLVE_CHECK_TOOL_ID,
            permission = CommandPermission.RESOLVE_CHECK,
        ),
        StandardAgentTool(
            definition = ProviderToolDefinition(
                name = PERFORM_ACTION_TOOL_ID.value,
                description = "Perform one action available in the current scene and commit its configured outcome.",
                parameters = listOf(
                    stringParameter("actionId", "Action identifier exposed by the current scene."),
                    ProviderToolParameter(
                        name = "outcomeId",
                        description = "Configured outcome for a choice without a CheckProfile; omit for checked actions.",
                        type = ProviderToolValueType.STRING,
                        required = false,
                    ),
                ),
            ),
            capabilityId = PERFORM_ACTION_TOOL_ID,
            permission = CommandPermission.APPLY_ACTION_OUTCOME,
        ),
        StandardAgentTool(
            definition = ProviderToolDefinition(
                name = ADVANCE_TIME_TOOL_ID.value,
                description = "Advance explicit auditable world time for waiting or another narrated reason.",
                parameters = listOf(
                    ProviderToolParameter(
                        name = "deltaMinutes",
                        description = "Positive number of world minutes to advance (maximum 525600).",
                        type = ProviderToolValueType.INTEGER,
                    ),
                ),
            ),
            capabilityId = ADVANCE_TIME_TOOL_ID,
            permission = CommandPermission.ADVANCE_WORLD_TIME,
        ),
        StandardAgentTool(
            definition = ProviderToolDefinition(
                name = PERFORM_ACTIVITY_TOOL_ID.value,
                description = "Perform one activity available in the current scene, including time, cost, and outcome.",
                parameters = listOf(
                    stringParameter("activityId", "Activity identifier exposed in the current scene."),
                    ProviderToolParameter(
                        name = "outcomeId",
                        description = "Configured outcome for an unchecked activity; omit when a check decides it.",
                        type = ProviderToolValueType.STRING,
                        required = false,
                    ),
                    ProviderToolParameter(
                        name = "interrupted",
                        description = "True to resolve the configured interruption outcome and elapsed time.",
                        type = ProviderToolValueType.BOOLEAN,
                        required = false,
                    ),
                ),
            ),
            capabilityId = PERFORM_ACTIVITY_TOOL_ID,
            permission = CommandPermission.PERFORM_ACTIVITY,
        ),
        StandardAgentTool(
            definition = ProviderToolDefinition(
                name = TRAVEL_TOOL_ID.value,
                description = "Travel along one route available from the current scene.",
                parameters = listOf(
                    stringParameter("routeId", "Travel route exposed from the current scene."),
                    ProviderToolParameter(
                        name = "outcomeId",
                        description = "Configured outcome for unchecked travel; omit when a check decides it.",
                        type = ProviderToolValueType.STRING,
                        required = false,
                    ),
                ),
            ),
            capabilityId = TRAVEL_TOOL_ID,
            permission = CommandPermission.TRAVEL,
        ),
        StandardAgentTool(
            definition = ProviderToolDefinition(
                name = INVENTORY_TOOL_ID.value,
                description = "Acquire, lose, or use a configured item through inventory capacity rules.",
                parameters = listOf(
                    stringParameter("itemId", "Configured item identifier."),
                    ProviderToolParameter("quantity", "Positive item quantity.", ProviderToolValueType.INTEGER),
                    ProviderToolParameter(
                        "operation",
                        "Inventory operation.",
                        ProviderToolValueType.STRING,
                        allowedValues = InventoryOperation.entries.map { it.name },
                    ),
                ),
            ),
            capabilityId = INVENTORY_TOOL_ID,
            permission = CommandPermission.MANAGE_INVENTORY,
        ),
        StandardAgentTool(
            definition = ProviderToolDefinition(
                name = CONDITION_TOOL_ID.value,
                description = "Apply, remove, or decay a configured condition.",
                parameters = listOf(
                    stringParameter("conditionId", "Configured condition identifier."),
                    ProviderToolParameter("stackDelta", "Optional signed stack change.", ProviderToolValueType.INTEGER, required = false),
                    ProviderToolParameter("elapsedMinutes", "Optional elapsed world minutes for duration decay.", ProviderToolValueType.INTEGER, required = false),
                ),
            ),
            capabilityId = CONDITION_TOOL_ID,
            permission = CommandPermission.UPDATE_CONDITION,
        ),
        StandardAgentTool(
            definition = ProviderToolDefinition(
                name = RELATIONSHIP_TOOL_ID.value,
                description = "Adjust one configured relationship dimension without exposing private projections.",
                parameters = listOf(
                    stringParameter("relationshipId", "Configured relationship identifier."),
                    ProviderToolParameter("delta", "Signed relationship change.", ProviderToolValueType.INTEGER),
                ),
            ),
            capabilityId = RELATIONSHIP_TOOL_ID,
            permission = CommandPermission.UPDATE_RELATIONSHIP,
        ),
        StandardAgentTool(
            definition = ProviderToolDefinition(
                name = QUEST_TOOL_ID.value,
                description = "Advance or resolve a configured quest stage.",
                parameters = listOf(
                    stringParameter("questId", "Configured quest identifier."),
                    stringParameter("stageId", "Configured stage identifier."),
                    ProviderToolParameter(
                        "status",
                        "Target quest status.",
                        ProviderToolValueType.STRING,
                        allowedValues = QuestStatus.entries.filterNot { it == QuestStatus.NOT_STARTED }.map { it.name },
                    ),
                ),
            ),
            capabilityId = QUEST_TOOL_ID,
            permission = CommandPermission.UPDATE_QUEST,
        ),
        StandardAgentTool(
            definition = ProviderToolDefinition(
                name = PROGRESS_CLOCK_TOOL_ID.value,
                description = "Advance or rewind one bounded progress clock.",
                parameters = listOf(
                    stringParameter("clockId", "Configured progress-clock identifier."),
                    ProviderToolParameter("delta", "Signed segment change.", ProviderToolValueType.INTEGER),
                ),
            ),
            capabilityId = PROGRESS_CLOCK_TOOL_ID,
            permission = CommandPermission.ADVANCE_PROGRESS_CLOCK,
        ),
    )

    private fun stringParameter(
        name: String,
        description: String,
    ): ProviderToolParameter = ProviderToolParameter(name, description, ProviderToolValueType.STRING)
}

private fun validateArguments(
    definition: ProviderToolDefinition,
    arguments: Map<String, JsonElement>,
): String? {
    val parameters = definition.parameters.associateBy(ProviderToolParameter::name)
    val unknown = arguments.keys.firstOrNull { it !in parameters }
    if (unknown != null) return "Unknown argument for ${definition.name}: $unknown"
    val missing = definition.parameters.firstOrNull { it.required && it.name !in arguments }
    if (missing != null) return "Missing required argument for ${definition.name}: ${missing.name}"
    definition.parameters.forEach { parameter ->
        val value = arguments[parameter.name] ?: return@forEach
        val primitive = value as? JsonPrimitive
            ?: return "Argument ${parameter.name} must be a primitive ${parameter.type.name.lowercase()}"
        val valid = when (parameter.type) {
            ProviderToolValueType.STRING -> primitive.isString &&
                primitive.contentOrNull != null &&
                (parameter.allowedValues.isEmpty() || primitive.content in parameter.allowedValues)
            ProviderToolValueType.INTEGER -> !primitive.isString && primitive.longOrNull != null
            ProviderToolValueType.BOOLEAN -> !primitive.isString && primitive.booleanOrNull != null
        }
        if (!valid) return "Argument ${parameter.name} must be ${parameter.type.name.lowercase()}"
    }
    return null
}

private fun ProviderToolCall.requireString(name: String): String =
    (arguments.getValue(name) as JsonPrimitive).content

private fun ProviderToolCall.requireLong(name: String): Long =
    requireNotNull((arguments.getValue(name) as JsonPrimitive).longOrNull)

private fun ProviderToolCall.optionalLong(name: String): Long? =
    (arguments[name] as? JsonPrimitive)?.longOrNull

private fun ProviderToolCall.optionalString(name: String): String? =
    (arguments[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun ProviderToolCall.optionalBoolean(name: String): Boolean? =
    (arguments[name] as? JsonPrimitive)?.booleanOrNull

private fun validateIdentifiers(
    call: ProviderToolCall,
    context: SessionCommandContext,
): String? {
    return try {
        when (call.name) {
            NUMERIC_ADJUST_TOOL_ID.value -> {
                val target = io.worldloom.application.SessionAdjustmentTarget(
                    EntityId(call.requireString("entityId")),
                    DefinitionId(call.requireString("componentId")),
                    DefinitionId(call.requireString("fieldId")),
                )
                if (target !in context.adjustmentTargets) return "Tool arguments do not identify one configured target"
            }

            RESOLVE_CHECK_TOOL_ID.value -> {
                val profileId = DefinitionId(call.requireString("profileId"))
                if (profileId !in context.checkProfileIds) return "Tool arguments do not identify one configured check"
            }

            PERFORM_ACTION_TOOL_ID.value -> {
                val actionId = DefinitionId(call.requireString("actionId"))
                val action = context.availableActions.firstOrNull { it.actionId == actionId }
                    ?: return "Tool action is not available in the current scene"
                val outcomeId = call.optionalString("outcomeId")?.let(::DefinitionId)
                if (action.requiresCheck && outcomeId != null) return "Checked actions derive their outcome from the audit record"
                if (!action.requiresCheck && outcomeId != null && outcomeId !in action.outcomeIds) {
                    return "Tool outcome is not configured for the selected action"
                }
            }

            ADVANCE_TIME_TOOL_ID.value -> {
                val delta = call.requireLong("deltaMinutes")
                if (delta !in 1..525_600) return "World time delta is outside the supported range"
            }

            PERFORM_ACTIVITY_TOOL_ID.value -> {
                val activityId = DefinitionId(call.requireString("activityId"))
                val activity = context.availableActivities.firstOrNull { it.activityId == activityId }
                    ?: return "Tool activity is not available in the current scene"
                val outcomeId = call.optionalString("outcomeId")?.let(::DefinitionId)
                val interrupted = call.optionalBoolean("interrupted") ?: false
                if (interrupted && activity.interruptionOutcomeId == null) return "Selected activity cannot be interrupted"
                if (interrupted && outcomeId != null && outcomeId != activity.interruptionOutcomeId) {
                    return "Interrupted activity must use its configured interruption outcome"
                }
                if (!interrupted && activity.requiresCheck && outcomeId != null) {
                    return "Checked activities derive their outcome from the audit record"
                }
                if (!interrupted && outcomeId != null && outcomeId == activity.interruptionOutcomeId) {
                    return "Interruption outcome requires interrupted=true"
                }
                if (!interrupted && !activity.requiresCheck && outcomeId != null && outcomeId !in activity.outcomeIds) {
                    return "Tool outcome is not configured for the selected activity"
                }
            }

            TRAVEL_TOOL_ID.value -> {
                val routeId = DefinitionId(call.requireString("routeId"))
                val route = context.availableTravelRoutes.firstOrNull { it.routeId == routeId }
                    ?: return "Tool route is not available from the current scene"
                val outcomeId = call.optionalString("outcomeId")?.let(::DefinitionId)
                if (route.requiresCheck && outcomeId != null) return "Checked travel derives its outcome from the audit record"
                if (!route.requiresCheck && outcomeId != null && outcomeId !in route.outcomeIds) {
                    return "Tool outcome is not configured for the selected route"
                }
            }

            INVENTORY_TOOL_ID.value -> {
                val definition = context.adventureStateDefinition ?: return "Adventure-state definition is unavailable"
                val itemId = DefinitionId(call.requireString("itemId"))
                if (definition.items.none { it.id == itemId }) return "Tool item is not configured"
                if (call.requireLong("quantity") <= 0) return "Inventory quantity must be positive"
                InventoryOperation.valueOf(call.requireString("operation"))
            }

            CONDITION_TOOL_ID.value -> {
                val definition = context.adventureStateDefinition ?: return "Adventure-state definition is unavailable"
                val conditionId = DefinitionId(call.requireString("conditionId"))
                if (definition.conditions.none { it.id == conditionId }) return "Tool condition is not configured"
                val stackDelta = call.optionalLong("stackDelta") ?: 0
                val elapsed = call.optionalLong("elapsedMinutes") ?: 0
                if (stackDelta == 0L && elapsed <= 0) return "Condition update requires stacks or elapsed time"
            }

            RELATIONSHIP_TOOL_ID.value -> {
                val definition = context.adventureStateDefinition ?: return "Adventure-state definition is unavailable"
                val relationshipId = DefinitionId(call.requireString("relationshipId"))
                if (definition.relationships.none { it.id == relationshipId }) return "Tool relationship is not configured"
                if (call.requireLong("delta") == 0L) return "Relationship delta cannot be zero"
            }

            QUEST_TOOL_ID.value -> {
                val definition = context.adventureStateDefinition ?: return "Adventure-state definition is unavailable"
                val questId = DefinitionId(call.requireString("questId"))
                val stageId = DefinitionId(call.requireString("stageId"))
                val quest = definition.quests.firstOrNull { it.id == questId } ?: return "Tool quest is not configured"
                if (quest.stages.none { it.id == stageId }) return "Tool stage does not belong to the selected quest"
                QuestStatus.valueOf(call.requireString("status"))
            }

            PROGRESS_CLOCK_TOOL_ID.value -> {
                val definition = context.adventureStateDefinition ?: return "Adventure-state definition is unavailable"
                val clockId = DefinitionId(call.requireString("clockId"))
                if (definition.clocks.none { it.id == clockId }) return "Tool progress clock is not configured"
                if (call.requireLong("delta") == 0L) return "Progress-clock delta cannot be zero"
            }
        }
        null
    } catch (_: IllegalArgumentException) {
        "Tool arguments contain an invalid stable identifier"
    }
}
