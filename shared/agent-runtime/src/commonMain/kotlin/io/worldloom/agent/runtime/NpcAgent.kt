package io.worldloom.agent.runtime

import io.worldloom.world.ActorId
import io.worldloom.world.CommandPermission
import io.worldloom.world.RunId
import io.worldloom.definition.DefinitionId
import io.worldloom.world.EntityId
import io.worldloom.world.NpcDialogueAudience
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NpcAgentProfile(
    val agentId: AgentId,
    val actorId: ActorId,
    val sessionId: AgentSessionId,
    val identityPrompt: String,
    val permissions: Set<CommandPermission>,
    val wakePolicy: NpcWakePolicy,
    val runId: RunId,
    val entityId: EntityId = EntityId(actorId.value),
    val displayName: String = agentId.value,
    val visiblePresentationIds: Set<DefinitionId> = emptySet(),
    val privateKnowledge: List<String> = emptyList(),
    val revealableKnowledgeIds: Set<DefinitionId> = emptySet(),
    val publicActionIds: Set<DefinitionId> = emptySet(),
    val replyAudience: NpcDialogueAudience? = null,
    val communicationMethodId: DefinitionId? = null,
) {
    init {
        require(identityPrompt.isNotBlank()) { "NPC identity prompt must not be blank" }
        require(displayName.isNotBlank()) { "NPC display name must not be blank" }
        require(privateKnowledge.none(String::isBlank)) { "NPC private knowledge must not be blank" }
    }
}

data class NpcWakePolicy(
    val triggerKinds: Set<String> = emptySet(),
    val relatedEntityIds: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val alwaysWake: Boolean = false,
) {
    init {
        require(triggerKinds.none(String::isBlank)) { "NPC trigger kinds must not be blank" }
        require(relatedEntityIds.none(String::isBlank)) { "NPC wake entity ids must not be blank" }
        require(tags.none(String::isBlank)) { "NPC wake tags must not be blank" }
    }

    fun matches(trigger: NpcTrigger): Boolean = alwaysWake ||
        trigger.kind in triggerKinds ||
        relatedEntityIds.any(trigger.relatedEntityIds::contains) ||
        tags.any(trigger.tags::contains)
}

data class NpcTrigger(
    val id: String,
    val kind: String,
    val input: String,
    val sourceSequence: Long,
    val sourceEventIds: Set<String> = emptySet(),
    val relatedEntityIds: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val priority: Int = 0,
) {
    init {
        require(id.isNotBlank() && kind.isNotBlank()) { "NPC trigger identity must not be blank" }
        require(input.isNotBlank()) { "NPC trigger input must not be blank" }
        require(sourceSequence >= 0) { "NPC trigger sequence must not be negative" }
    }
}

data class NpcPerception(
    val currentObservation: String,
    val activeGoals: List<String> = emptyList(),
    val relationships: List<String> = emptyList(),
    val relatedEntityIds: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val estimatedContextTokens: Long,
) {
    init {
        require(currentObservation.isNotBlank()) { "NPC perception must not be blank" }
        require(estimatedContextTokens >= 0) { "NPC context estimate must not be negative" }
    }
}

data class NpcAgentPolicy(
    val contextBudgetTokens: Long = 32_000,
    val compactionPromptVersion: Int = 1,
    val compactionModelId: String = "compaction.default",
) {
    init {
        require(contextBudgetTokens > 0) { "NPC context budget must be positive" }
        require(compactionPromptVersion > 0) { "Compaction prompt version must be positive" }
        require(compactionModelId.isNotBlank()) { "Compaction model id must not be blank" }
    }
}

sealed interface NpcAgentResult {
    data class Responded(
        val agentId: AgentId,
        val text: String,
        val turnSequence: Long,
        val worldChanged: Boolean,
    ) : NpcAgentResult

    data class Failed(val agentId: AgentId, val error: AgentRunError) : NpcAgentResult
}

/** A stable NPC identity with a private session, perception projection, memory, and Tool authority. */
class NpcAgent(
    val profile: NpcAgentProfile,
    private val runtime: AgentRuntime,
    private val memoryStore: AgentMemoryStore,
    private val contextBuilder: AgentContextBuilder = AgentContextBuilder(memoryStore),
    private val compactionCoordinator: AgentCompactionCoordinator? = null,
    private val policy: NpcAgentPolicy = NpcAgentPolicy(),
) {
    private val mutex = Mutex()

    suspend fun respond(
        trigger: NpcTrigger,
        perception: NpcPerception,
        checkpointRequested: Boolean = false,
        publishedAtEpochMillis: Long = 0,
    ): NpcAgentResult = mutex.withLock {
        val context = contextBuilder.build(
            AgentContextRequest(
                agentId = profile.agentId,
                identity = profile.identityPrompt,
                currentPerception = perception.currentObservation,
                activeGoals = perception.activeGoals,
                relationships = perception.relationships,
                relatedEntityIds = perception.relatedEntityIds + trigger.relatedEntityIds,
                tags = perception.tags + trigger.tags,
                estimatedContextTokens = perception.estimatedContextTokens,
                contextBudgetTokens = policy.contextBudgetTokens,
            ),
        )
        val identity = AgentIdentity(
            profile.agentId,
            profile.actorId,
            profile.permissions,
            profile.replyAudience,
            profile.communicationMethodId,
        )
        when (
            val result = runtime.run(
                AgentRunRequest(
                    sessionId = profile.sessionId,
                    identity = identity,
                    input = trigger.input,
                    systemPrompt = context.systemPrompt,
                    contextMessages = context.recentMessages,
                    runId = profile.runId,
                ),
            )
        ) {
            is AgentRunResult.Failure -> NpcAgentResult.Failed(profile.agentId, result.error)
            is AgentRunResult.Completed -> {
                val previousSequence = memoryStore.turns(profile.agentId).lastOrNull()?.sequence ?: 0
                val turnSequence = previousSequence + 1
                val appended = memoryStore.appendTurn(
                    AgentTurnRecord(
                        agentId = profile.agentId,
                        sessionId = profile.sessionId,
                        sequence = turnSequence,
                        input = trigger.input,
                        output = result.text,
                        tokenCount = result.usage.inputTokens + result.usage.outputTokens,
                        sourceEventIds = trigger.sourceEventIds,
                    ),
                )
                if (!appended) {
                    return@withLock NpcAgentResult.Failed(
                        profile.agentId,
                        AgentRunError(AgentRunErrorCode.SESSION_CONFLICT, "NPC memory turn was updated concurrently"),
                    )
                }
                compactionCoordinator?.schedule(
                    agentId = profile.agentId,
                    estimatedContextTokens = perception.estimatedContextTokens + result.usage.outputTokens,
                    contextBudgetTokens = policy.contextBudgetTokens,
                    checkpointRequested = checkpointRequested,
                    promptVersion = policy.compactionPromptVersion,
                    modelId = policy.compactionModelId,
                    publishedAtEpochMillis = publishedAtEpochMillis,
                )
                NpcAgentResult.Responded(profile.agentId, result.text, turnSequence, result.worldChanged)
            }
        }
    }
}

fun interface NpcPerceptionProjector {
    suspend fun project(profile: NpcAgentProfile, trigger: NpcTrigger): NpcPerception
}

/** Wakes only relevant NPCs and orders their proposals deterministically before world resolution. */
class NpcAgentScheduler(
    agents: List<NpcAgent>,
) {
    private val agents = agents.sortedBy { it.profile.agentId.value }.also { sorted ->
        require(sorted.map { it.profile.agentId }.distinct().size == sorted.size) { "NPC agent ids must be unique" }
        require(sorted.map { it.profile.sessionId }.distinct().size == sorted.size) { "NPC sessions must be private" }
    }

    suspend fun dispatch(
        trigger: NpcTrigger,
        projector: NpcPerceptionProjector,
        publishedAtEpochMillis: Long = 0,
    ): List<NpcAgentResult> = agents
        .filter { it.profile.wakePolicy.matches(trigger) }
        .sortedWith(compareByDescending<NpcAgent> { trigger.priority }.thenBy { it.profile.agentId.value })
        .map { agent ->
            agent.respond(trigger, projector.project(agent.profile, trigger), publishedAtEpochMillis = publishedAtEpochMillis)
        }
}
