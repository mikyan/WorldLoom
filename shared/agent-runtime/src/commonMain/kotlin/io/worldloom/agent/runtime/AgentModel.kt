package io.worldloom.agent.runtime

import io.worldloom.provider.api.ProviderUsage
import io.worldloom.provider.api.ProviderMessage
import io.worldloom.world.ActorId
import io.worldloom.world.CommandPermission
import io.worldloom.world.RunId
import io.worldloom.world.NpcDialogueAudience
import io.worldloom.definition.DefinitionId
import kotlin.jvm.JvmInline

private val AGENT_IDENTIFIER_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9._:-]*$")

@JvmInline
value class AgentId(val value: String) {
    init {
        require(AGENT_IDENTIFIER_PATTERN.matches(value)) { "AgentId must be a stable identifier" }
    }
}

@JvmInline
value class AgentSessionId(val value: String) {
    init {
        require(AGENT_IDENTIFIER_PATTERN.matches(value)) { "AgentSessionId must be a stable identifier" }
    }
}

data class AgentIdentity(
    val agentId: AgentId,
    val actorId: ActorId,
    val permissions: Set<CommandPermission>,
    /** Optional invocation-scoped channel pin used to prevent an NPC from widening a private reply. */
    val dialogueAudience: NpcDialogueAudience? = null,
    val communicationMethodId: DefinitionId? = null,
)

data class AgentRunRequest(
    val sessionId: AgentSessionId,
    val identity: AgentIdentity,
    val input: String,
    val systemPrompt: String,
    /** Optional compacted provider view; the durable session still archives the complete turn. */
    val contextMessages: List<ProviderMessage>? = null,
    val runId: RunId? = null,
) {
    init {
        require(input.isNotBlank()) { "Agent input must not be blank" }
        require(systemPrompt.isNotBlank()) { "Agent system prompt must not be blank" }
    }
}

data class AgentRunPolicy(
    val maxSteps: Int = 8,
    val maxToolCalls: Int = 8,
    val timeoutMillis: Long = 30_000,
    val maxInputTokens: Long = 32_000,
    val maxOutputTokens: Long = 4_000,
    val maxCostMicrounits: Long = 500_000,
    val maxOutputTokensPerStep: Int = 1_024,
) {
    init {
        require(maxSteps > 0) { "maxSteps must be positive" }
        require(maxToolCalls > 0) { "maxToolCalls must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        require(maxInputTokens > 0) { "maxInputTokens must be positive" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        require(maxCostMicrounits >= 0) { "maxCostMicrounits must not be negative" }
        require(maxOutputTokensPerStep > 0) { "maxOutputTokensPerStep must be positive" }
    }
}

enum class AgentRunErrorCode {
    SESSION_OWNERSHIP_MISMATCH,
    SESSION_CONFLICT,
    SESSION_STORAGE_FAILURE,
    PROVIDER_CAPABILITY_UNAVAILABLE,
    PROVIDER_FAILURE,
    INVALID_PROVIDER_RESPONSE,
    TOOL_REJECTED,
    TOOL_LOOP_DETECTED,
    STEP_LIMIT_EXCEEDED,
    TOOL_CALL_LIMIT_EXCEEDED,
    INPUT_TOKEN_BUDGET_EXCEEDED,
    OUTPUT_TOKEN_BUDGET_EXCEEDED,
    COST_BUDGET_EXCEEDED,
    TIMEOUT,
}

data class AgentRunError(
    val code: AgentRunErrorCode,
    val message: String,
    /** True when a prior tool call in this turn already produced authoritative events. */
    val worldChanged: Boolean = false,
)

sealed interface AgentRunResult {
    data class Completed(
        val text: String,
        val steps: Int,
        val toolCalls: Int,
        val usage: ProviderUsage,
        val worldChanged: Boolean,
    ) : AgentRunResult

    data class AwaitingPlayerCheck(
        val check: PendingPlayerCheck,
        val steps: Int,
        val toolCalls: Int,
        val usage: ProviderUsage,
    ) : AgentRunResult

    data class Failure(val error: AgentRunError) : AgentRunResult
}
