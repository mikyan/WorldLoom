package io.worldloom.provider.api

import kotlinx.serialization.json.JsonObject

enum class ProviderMessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

data class ProviderToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
) {
    init {
        require(id.isNotBlank()) { "Tool call id must not be blank" }
        require(name.isNotBlank()) { "Tool name must not be blank" }
    }
}

/** Vendor-neutral conversation item. Provider DTOs must remain inside provider adapters. */
data class ProviderMessage(
    val role: ProviderMessageRole,
    val content: String? = null,
    val toolCalls: List<ProviderToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
) {
    init {
        require(content != null || toolCalls.isNotEmpty()) { "A provider message must contain text or tool calls" }
        if (role == ProviderMessageRole.TOOL) {
            require(!toolCallId.isNullOrBlank()) { "Tool result messages require a tool call id" }
            require(!toolName.isNullOrBlank()) { "Tool result messages require a tool name" }
        }
    }
}

enum class ProviderToolValueType {
    STRING,
    INTEGER,
    BOOLEAN,
}

data class ProviderToolParameter(
    val name: String,
    val description: String,
    val type: ProviderToolValueType,
    val required: Boolean = true,
    val allowedValues: List<String> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "Tool parameter name must not be blank" }
        require(description.isNotBlank()) { "Tool parameter description must not be blank" }
        require(type == ProviderToolValueType.STRING || allowedValues.isEmpty()) {
            "Only string parameters may declare allowed values"
        }
        require(allowedValues.distinct().size == allowedValues.size) { "Allowed values must be unique" }
    }
}

data class ProviderToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ProviderToolParameter>,
) {
    init {
        require(name.isNotBlank()) { "Tool name must not be blank" }
        require(description.isNotBlank()) { "Tool description must not be blank" }
        require(parameters.map(ProviderToolParameter::name).distinct().size == parameters.size) {
            "Tool parameter names must be unique"
        }
    }
}

data class ProviderUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val costMicrounits: Long = 0,
) {
    init {
        require(inputTokens >= 0) { "Input token usage must not be negative" }
        require(outputTokens >= 0) { "Output token usage must not be negative" }
        require(costMicrounits >= 0) { "Provider cost must not be negative" }
    }

    operator fun plus(other: ProviderUsage): ProviderUsage = ProviderUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        costMicrounits = costMicrounits + other.costMicrounits,
    )

    companion object {
        val Zero: ProviderUsage = ProviderUsage(0, 0, 0)
    }
}

data class ProviderCapabilities(
    val toolCalling: Boolean,
    val streaming: Boolean,
    val structuredOutput: Boolean,
)

data class ProviderRequest(
    val messages: List<ProviderMessage>,
    val tools: List<ProviderToolDefinition> = emptyList(),
    val maxOutputTokens: Int,
) {
    init {
        require(messages.isNotEmpty()) { "Provider request messages must not be empty" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
    }
}

data class ProviderTurn(
    val text: String? = null,
    val toolCalls: List<ProviderToolCall> = emptyList(),
    val usage: ProviderUsage,
) {
    init {
        require(text != null || toolCalls.isNotEmpty()) { "Provider turn must contain text or tool calls" }
    }
}

enum class ProviderFailureCode {
    AUTHENTICATION,
    RATE_LIMITED,
    TIMEOUT,
    INVALID_REQUEST,
    INVALID_RESPONSE,
    NETWORK,
    UNAVAILABLE,
    UNKNOWN,
}

sealed interface ProviderResult {
    data class Success(val turn: ProviderTurn) : ProviderResult

    data class Failure(
        val code: ProviderFailureCode,
        val message: String,
        val retryable: Boolean,
    ) : ProviderResult
}

interface LanguageModelProvider {
    val capabilities: ProviderCapabilities

    suspend fun complete(request: ProviderRequest): ProviderResult
}

sealed interface ProviderStreamEvent {
    data class TextDelta(val text: String) : ProviderStreamEvent
}

interface StreamingLanguageModelProvider : LanguageModelProvider {
    suspend fun completeStreaming(
        request: ProviderRequest,
        onEvent: suspend (ProviderStreamEvent) -> Unit,
    ): ProviderResult
}
