package io.worldloom.agent.runtime

import io.worldloom.provider.api.LanguageModelProvider
import io.worldloom.provider.api.ProviderFailureCode
import io.worldloom.provider.api.ProviderMessage
import io.worldloom.provider.api.ProviderMessageRole
import io.worldloom.provider.api.ProviderRequest
import io.worldloom.provider.api.ProviderResult
import io.worldloom.provider.api.ProviderStreamEvent
import io.worldloom.provider.api.ProviderToolCall
import io.worldloom.provider.api.ProviderUsage
import io.worldloom.provider.api.StreamingLanguageModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class AgentRuntime(
    private val provider: LanguageModelProvider,
    private val toolGateway: AgentToolGateway,
    private val sessionStore: AgentSessionStore = InMemoryAgentSessionStore(),
    private val policy: AgentRunPolicy = AgentRunPolicy(),
) {
    suspend fun run(
        request: AgentRunRequest,
        onTextDelta: suspend (String) -> Unit = {},
    ): AgentRunResult =
        withTimeoutOrNull(policy.timeoutMillis) {
            runWithinDeadline(request, onTextDelta)
        } ?: AgentRunResult.Failure(
            AgentRunError(AgentRunErrorCode.TIMEOUT, "Agent turn exceeded ${policy.timeoutMillis} ms"),
        )

    private suspend fun runWithinDeadline(
        request: AgentRunRequest,
        onTextDelta: suspend (String) -> Unit,
    ): AgentRunResult {
        val snapshot = when (val loaded = sessionStore.load(request.sessionId, request.identity, request.runId)) {
            is AgentSessionLoadResult.Success -> loaded.snapshot
            AgentSessionLoadResult.OwnershipMismatch -> return failure(
                AgentRunErrorCode.SESSION_OWNERSHIP_MISMATCH,
                "Agent session belongs to a different identity",
            )
            is AgentSessionLoadResult.StorageFailure -> return failure(
                AgentRunErrorCode.SESSION_STORAGE_FAILURE,
                loaded.message,
            )
        }
        val tools = toolGateway.availableTools(request.identity)
        val offeredToolNames = tools.mapTo(mutableSetOf()) { it.name }
        if (tools.isNotEmpty() && !provider.capabilities.toolCalling) {
            return failure(
                AgentRunErrorCode.PROVIDER_CAPABILITY_UNAVAILABLE,
                "Configured provider does not support tool calling",
            )
        }

        val archivedConversation = snapshot.messages.toMutableList()
        val conversation = (request.contextMessages ?: snapshot.messages).toMutableList()
        val inputMessage = ProviderMessage(ProviderMessageRole.USER, request.input)
        archivedConversation += inputMessage
        conversation += inputMessage
        var steps = 0
        var toolCallCount = 0
        var usage = ProviderUsage.Zero
        var worldChanged = false
        val toolSignatures = mutableSetOf<String>()
        val toolCallIds = mutableSetOf<String>()

        while (true) {
            if (steps >= policy.maxSteps) {
                return failure(
                    AgentRunErrorCode.STEP_LIMIT_EXCEEDED,
                    "Agent exceeded the ${policy.maxSteps}-step limit",
                    worldChanged,
                )
            }
            val remainingOutputTokens = policy.maxOutputTokens - usage.outputTokens
            if (remainingOutputTokens <= 0) {
                return failure(
                    AgentRunErrorCode.OUTPUT_TOKEN_BUDGET_EXCEEDED,
                    "Agent exhausted its output token budget",
                    worldChanged,
                )
            }
            val response = try {
                val providerRequest = ProviderRequest(
                    messages = listOf(ProviderMessage(ProviderMessageRole.SYSTEM, request.systemPrompt)) + conversation,
                    tools = tools,
                    maxOutputTokens = minOf(
                        policy.maxOutputTokensPerStep.toLong(),
                        remainingOutputTokens,
                    ).toInt(),
                )
                if (provider is StreamingLanguageModelProvider && provider.capabilities.streaming) {
                    provider.completeStreaming(providerRequest) { event ->
                        if (event is ProviderStreamEvent.TextDelta) onTextDelta(event.text)
                    }
                } else {
                    provider.complete(providerRequest)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failure(
                    AgentRunErrorCode.PROVIDER_FAILURE,
                    "Provider call failed without a structured response",
                    worldChanged,
                )
            }
            steps += 1
            val turn = when (response) {
                is ProviderResult.Success -> response.turn
                is ProviderResult.Failure -> return failure(
                    AgentRunErrorCode.PROVIDER_FAILURE,
                    providerFailureMessage(response.code, response.message),
                    worldChanged,
                )
            }
            budgetFailure(usage, turn.usage, worldChanged)?.let { return it }
            usage += turn.usage

            if (turn.toolCalls.isEmpty()) {
                val text = turn.text
                    ?: return failure(
                        AgentRunErrorCode.INVALID_PROVIDER_RESPONSE,
                        "Provider returned neither final text nor tool calls",
                        worldChanged,
                    )
                val finalMessage = ProviderMessage(ProviderMessageRole.ASSISTANT, text)
                conversation += finalMessage
                archivedConversation += finalMessage
                when (
                    val saved = sessionStore.save(
                        snapshot.copy(messages = archivedConversation.toList()),
                        expectedRevision = snapshot.revision,
                    )
                ) {
                    is AgentSessionSaveResult.Success -> return AgentRunResult.Completed(
                        text = text,
                        steps = steps,
                        toolCalls = toolCallCount,
                        usage = usage,
                        worldChanged = worldChanged,
                    )

                    AgentSessionSaveResult.OwnershipMismatch -> return failure(
                        AgentRunErrorCode.SESSION_OWNERSHIP_MISMATCH,
                        "Agent session ownership changed before memory could be published",
                        worldChanged,
                    )

                    AgentSessionSaveResult.RevisionConflict -> return failure(
                        AgentRunErrorCode.SESSION_CONFLICT,
                        "Agent session was updated concurrently",
                        worldChanged,
                    )

                    is AgentSessionSaveResult.StorageFailure -> return failure(
                        AgentRunErrorCode.SESSION_STORAGE_FAILURE,
                        saved.message,
                        worldChanged,
                    )
                }
            }

            if (toolCallCount + turn.toolCalls.size > policy.maxToolCalls) {
                return failure(
                    AgentRunErrorCode.TOOL_CALL_LIMIT_EXCEEDED,
                    "Agent exceeded the ${policy.maxToolCalls}-tool-call limit",
                    worldChanged,
                )
            }
            val batchSignatures = mutableSetOf<String>()
            turn.toolCalls.forEach { call ->
                if (call.name !in offeredToolNames) {
                    return failure(
                        AgentRunErrorCode.TOOL_REJECTED,
                        "Provider called a tool that was not offered for this turn",
                        worldChanged,
                    )
                }
                if (!toolCallIds.add(call.id)) {
                    return failure(
                        AgentRunErrorCode.INVALID_PROVIDER_RESPONSE,
                        "Provider reused tool call id ${call.id}",
                        worldChanged,
                    )
                }
                val signature = call.signature()
                if (signature in toolSignatures || !batchSignatures.add(signature)) {
                    return failure(
                        AgentRunErrorCode.TOOL_LOOP_DETECTED,
                        "Agent repeated an identical tool call",
                        worldChanged,
                    )
                }
                when (val validation = toolGateway.validate(request.identity, call)) {
                    ToolValidationResult.Valid -> Unit
                    is ToolValidationResult.Invalid -> return failure(
                        AgentRunErrorCode.TOOL_REJECTED,
                        validation.error.message,
                        worldChanged,
                    )
                }
            }

            val assistantToolMessage = ProviderMessage(
                role = ProviderMessageRole.ASSISTANT,
                content = turn.text,
                toolCalls = turn.toolCalls,
            )
            conversation += assistantToolMessage
            archivedConversation += assistantToolMessage
            turn.toolCalls.forEach { call ->
                when (val invocation = toolGateway.invoke(request.identity, call)) {
                    is ToolInvocationResult.Success -> {
                        worldChanged = worldChanged || invocation.worldChanged
                        toolCallCount += 1
                        toolSignatures += call.signature()
                        val toolMessage = ProviderMessage(
                            role = ProviderMessageRole.TOOL,
                            content = invocation.output,
                            toolCallId = call.id,
                            toolName = call.name,
                        )
                        conversation += toolMessage
                        archivedConversation += toolMessage
                    }

                    is ToolInvocationResult.Failure -> return failure(
                        AgentRunErrorCode.TOOL_REJECTED,
                        invocation.error.message,
                        worldChanged || invocation.worldChanged,
                    )
                }
            }
        }
    }

    private fun budgetFailure(
        current: ProviderUsage,
        additional: ProviderUsage,
        worldChanged: Boolean,
    ): AgentRunResult.Failure? = when {
        exceeds(current.inputTokens, additional.inputTokens, policy.maxInputTokens) -> failure(
            AgentRunErrorCode.INPUT_TOKEN_BUDGET_EXCEEDED,
            "Agent exceeded its input token budget",
            worldChanged,
        )

        exceeds(current.outputTokens, additional.outputTokens, policy.maxOutputTokens) -> failure(
            AgentRunErrorCode.OUTPUT_TOKEN_BUDGET_EXCEEDED,
            "Agent exceeded its output token budget",
            worldChanged,
        )

        exceeds(current.costMicrounits, additional.costMicrounits, policy.maxCostMicrounits) -> failure(
            AgentRunErrorCode.COST_BUDGET_EXCEEDED,
            "Agent exceeded its cost budget",
            worldChanged,
        )

        else -> null
    }

    private fun exceeds(
        current: Long,
        additional: Long,
        limit: Long,
    ): Boolean = additional > limit || current > limit - additional

    private fun failure(
        code: AgentRunErrorCode,
        message: String,
        worldChanged: Boolean = false,
    ): AgentRunResult.Failure = AgentRunResult.Failure(AgentRunError(code, message, worldChanged))

    private fun providerFailureMessage(
        code: ProviderFailureCode,
        message: String,
    ): String = "Provider ${code.name.lowercase()}: $message"
}

private fun ProviderToolCall.signature(): String = "$name:${arguments.canonical()}"

private fun JsonElement.canonical(): String = when (this) {
    JsonNull -> "null"
    is JsonPrimitive -> toString()
    is JsonArray -> joinToString(prefix = "[", postfix = "]") { it.canonical() }
    is JsonObject -> entries
        .sortedBy(Map.Entry<String, JsonElement>::key)
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${JsonPrimitive(key)}:${value.canonical()}"
        }
}
