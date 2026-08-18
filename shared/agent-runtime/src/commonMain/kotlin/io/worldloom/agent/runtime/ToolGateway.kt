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

        else -> definition
    }
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
        }
        null
    } catch (_: IllegalArgumentException) {
        "Tool arguments contain an invalid stable identifier"
    }
}
