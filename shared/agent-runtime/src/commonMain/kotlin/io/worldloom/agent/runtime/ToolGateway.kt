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

    data class Failure(val error: ToolGatewayError) : ToolInvocationResult
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
        if (!tool.enabledByManifest(context.modules)) {
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
            ActionResult.Success -> ToolInvocationResult.Success(successOutput(), worldChanged = true)
            is ActionResult.Failure -> ToolInvocationResult.Failure(
                ToolGatewayError(ToolGatewayErrorCode.COMMAND_REJECTED, result.error.message),
            )
        }
    }

    private fun StandardAgentTool.available(
        context: SessionCommandContext,
        identity: AgentIdentity,
    ): Boolean = enabledByManifest(context.modules) && permission in identity.permissions

    private fun invalid(
        code: ToolGatewayErrorCode,
        message: String,
    ): ToolValidationResult.Invalid = ToolValidationResult.Invalid(ToolGatewayError(code, message))

    private fun successOutput(): String {
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
        }.toString()
    }
}

private data class StandardAgentTool(
    val definition: ProviderToolDefinition,
    val capabilityId: DefinitionId,
    val permission: CommandPermission,
    val additionalAvailability: (RegisteredWorldModules) -> Boolean = { true },
) {
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
        }
        null
    } catch (_: IllegalArgumentException) {
        "Tool arguments contain an invalid stable identifier"
    }
}
