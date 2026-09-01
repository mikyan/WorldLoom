package io.worldloom.agent.runtime

import io.worldloom.application.GameSession
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.SessionCommittedEvent
import io.worldloom.application.SessionNpcProfile
import io.worldloom.definition.DefinitionId
import io.worldloom.world.CommandPermission
import io.worldloom.world.RunId
import io.worldloom.world.NPC_KNOWLEDGE_REVEALED_EVENT_TYPE_ID
import io.worldloom.world.NpcDialogueAudience
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

const val CURRENT_NPC_WORK_SCHEMA_VERSION: Int = 1

@Serializable
@JvmInline
value class NpcWorkId(val value: String) {
    init { require(value.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9._:-]*$"))) { "NPC work ID must be stable" } }
}

@Serializable
enum class NpcWorkStatus { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, INTERRUPTED }

@Serializable
data class NpcWorkItem(
    val schemaVersion: Int = CURRENT_NPC_WORK_SCHEMA_VERSION,
    val id: NpcWorkId,
    val runId: RunId,
    val npcId: DefinitionId,
    val sourceEventId: String,
    val sourceSequence: Long,
    val eventType: DefinitionId,
    val sceneId: DefinitionId,
    val status: NpcWorkStatus = NpcWorkStatus.PENDING,
    val revision: Long = 0,
    val acceptedSequence: Long? = null,
    val deliveredSequence: Long? = null,
    val publicEventIds: List<String> = emptyList(),
    val errorCode: String? = null,
) {
    init {
        require(schemaVersion == CURRENT_NPC_WORK_SCHEMA_VERSION) { "Unsupported NPC work schema" }
        require(sourceEventId.isNotBlank() && sourceSequence > 0) { "NPC work source must be committed" }
        require(revision >= 0) { "NPC work revision must not be negative" }
        require(acceptedSequence == null || acceptedSequence >= sourceSequence) { "NPC work acceptance precedes its source" }
        require(deliveredSequence == null || deliveredSequence >= (acceptedSequence ?: sourceSequence)) {
            "NPC work delivery precedes acceptance"
        }
        require(publicEventIds.none(String::isBlank)) { "NPC public Event IDs must be stable" }
    }
}

sealed interface NpcWorkCreateResult {
    data object Created : NpcWorkCreateResult
    data object Exists : NpcWorkCreateResult
    data class Failure(val message: String) : NpcWorkCreateResult
}

sealed interface NpcWorkUpdateResult {
    data object Updated : NpcWorkUpdateResult
    data object RevisionConflict : NpcWorkUpdateResult
    data class Failure(val message: String) : NpcWorkUpdateResult
}

interface NpcWorkStore {
    suspend fun create(item: NpcWorkItem): NpcWorkCreateResult
    suspend fun list(runId: RunId): List<NpcWorkItem>
    suspend fun update(expectedRevision: Long, item: NpcWorkItem): NpcWorkUpdateResult
}

class InMemoryNpcWorkStore : NpcWorkStore {
    private val mutex = Mutex()
    private val items = mutableMapOf<Pair<RunId, NpcWorkId>, NpcWorkItem>()

    override suspend fun create(item: NpcWorkItem): NpcWorkCreateResult = mutex.withLock {
        val key = item.runId to item.id
        if (key in items) NpcWorkCreateResult.Exists else {
            items[key] = item
            NpcWorkCreateResult.Created
        }
    }

    override suspend fun list(runId: RunId): List<NpcWorkItem> = mutex.withLock {
        items.filterKeys { it.first == runId }.values.sortedWith(NPC_WORK_ORDER)
    }

    override suspend fun update(expectedRevision: Long, item: NpcWorkItem): NpcWorkUpdateResult = mutex.withLock {
        val key = item.runId to item.id
        val current = items[key] ?: return@withLock NpcWorkUpdateResult.RevisionConflict
        if (current.revision != expectedRevision || item.revision != expectedRevision + 1) {
            return@withLock NpcWorkUpdateResult.RevisionConflict
        }
        items[key] = item
        NpcWorkUpdateResult.Updated
    }
}

val NPC_WORK_ORDER: Comparator<NpcWorkItem> =
    compareBy<NpcWorkItem> { it.sourceSequence }.thenBy { it.npcId.value }.thenBy { it.id.value }

data class NpcDispatchPolicy(
    val maxConcurrentPerScene: Int = 1,
    val maxWakesPerEvent: Int = 4,
    val maxTurnsPerDispatch: Int = 8,
) {
    init {
        require(maxConcurrentPerScene > 0) { "NPC scene concurrency must be positive" }
        require(maxWakesPerEvent > 0) { "NPC wakes per Event must be positive" }
        require(maxTurnsPerDispatch > 0) { "NPC turns per dispatch must be positive" }
    }
}

/** Projects only the configured NPC's current scene, whitelisted fields, goals, and private facts. */
class NpcContextProjector(
    private val session: GameSession,
) : NpcPerceptionProjector {
    override suspend fun project(profile: NpcAgentProfile, trigger: NpcTrigger): NpcPerception {
        val context = requireNotNull(session.commandContext()) { "NPC Run context is unavailable" }
        require(context.runId == profile.runId) { "NPC profile belongs to another Run" }
        val configured = requireNotNull(context.npcProfiles.firstOrNull { it.actorId == profile.actorId }) {
            "NPC profile is not declared by the loaded world"
        }
        val remoteCommunication = "remote-communication" in trigger.tags
        require(configured.entityId in context.currentSceneParticipantIds || remoteCommunication) {
            "NPC is neither nearby nor connected through an authorized remote channel"
        }
        val ready = requireNotNull(session.state.value as? GameSessionUiState.Ready) { "NPC presentation is unavailable" }
        val scene = requireNotNull(ready.presentation.scene) { "NPC scene is unavailable" }
        val visibleFields = ready.presentation.fields.filter { it.presentationId in configured.visiblePresentationIds }
        val observation = buildString {
            appendLine("你只能依据这里列出的感知与自己的记忆行动；未列出的世界事实对你不可见。")
            if (remoteCommunication) {
                appendLine("玩家所在场景：${scene.label} (${scene.id.value})；你正通过远程通讯与玩家联系，不在其身边。")
            } else {
                appendLine("当前场景：${scene.label} (${scene.id.value})")
            }
            appendLine("触发事件：${trigger.kind}，公开序列 ${trigger.sourceSequence}")
            if (visibleFields.isNotEmpty()) appendLine(
                "可感知状态：${visibleFields.joinToString { "${it.label}:${it.value}" }}",
            )
            if (!remoteCommunication && ready.presentation.exploration.affordances.isNotEmpty()) {
                appendLine("同场公开可见对象：")
                ready.presentation.exploration.affordances.forEach { appendLine("- ${it.label}：${it.description}") }
            }
            val privateKnowledge = configured.privateKnowledge + configured.knowledge.map { it.privateText }
            if (privateKnowledge.isNotEmpty()) {
                appendLine("仅你知道的背景：")
                privateKnowledge.forEach { appendLine("- $it") }
            }
            val revealableIds = configured.knowledge.filter { it.revealable }.map { it.id }
            if (revealableIds.isNotEmpty()) {
                appendLine("可通过 npc.speak 的 revealKnowledgeIds 公开：${revealableIds.joinToString { it.value }}")
                appendLine("只提交知识 ID；公开摘要由世界包固定，不能自行改写。")
            }
            if (configured.publicActionIds.isNotEmpty()) appendLine(
                "获准公开动作：${configured.publicActionIds.sortedBy { it.value }.joinToString { it.value }}",
            )
            if ("private-dialogue" in trigger.tags) {
                appendLine("这是私密对话。回应必须调用 npc.speak，并保持 PRIVATE audience 与同一 communicationMethodId；不得公开扩散。")
            } else {
                appendLine("对玩家可见的发言或动作必须调用 NPC 工具；最终文本只作为你的私有反思，不会公开。")
            }
        }.trim()
        return NpcPerception(
            currentObservation = observation,
            activeGoals = configured.goals,
            relatedEntityIds = context.currentSceneParticipantIds.mapTo(mutableSetOf()) { it.value },
            tags = setOf("scene:${scene.id.value}", "event:${trigger.kind}"),
            estimatedContextTokens = (observation.length / 3).toLong().coerceAtLeast(1),
        )
    }
}

/**
 * Synchronous foreground dispatcher: GM work owns provider priority, NPC turns run one at a time
 * after committed facts, and a full EventLog rescan closes the Event append -> queue crash window.
 */
class NpcSceneOrchestrator(
    private val runtime: AgentRuntime,
    private val gameSession: GameSession,
    private val workStore: NpcWorkStore = InMemoryNpcWorkStore(),
    private val memoryStoreFactory: ((RunId) -> AgentMemoryStore)? = null,
    private val policy: NpcDispatchPolicy = NpcDispatchPolicy(),
) : GameTurnFollowUpDispatcher {
    private val mutex = Mutex()
    private val fallbackMemoryStore = InMemoryAgentMemoryStore()

    override suspend fun dispatch(request: GameTurnFollowUpRequest): GameTurnFollowUpResult = mutex.withLock {
        val context = gameSession.commandContext()
            ?: return@withLock GameTurnFollowUpResult.Failed("NPC Run context is unavailable")
        if (context.runId != request.runId) return@withLock GameTurnFollowUpResult.Failed("NPC Run does not match")
        val latestSequence = context.lastSequence
        val events = gameSession.committedEvents(0, latestSequence)
        events.forEach { event -> enqueue(event, context.npcProfiles, request.runId) }

        val publicResults = mutableListOf<PublicFollowUp>()
        var turns = 0
        workStore.list(request.runId).filter { it.status == NpcWorkStatus.RUNNING }.forEach { interrupted ->
            update(
                interrupted,
                interrupted.copy(
                    status = NpcWorkStatus.INTERRUPTED,
                    revision = interrupted.revision + 1,
                    deliveredSequence = gameSession.commandContext()?.lastSequence ?: interrupted.acceptedSequence,
                    errorCode = "npc.interrupted",
                ),
            )
        }
        while (turns < policy.maxTurnsPerDispatch) {
            val work = workStore.list(request.runId).firstOrNull { it.status == NpcWorkStatus.PENDING } ?: break
            val current = gameSession.commandContext()
                ?: return@withLock GameTurnFollowUpResult.Failed("NPC Run context disappeared")
            val profile = current.npcProfiles.firstOrNull { it.id == work.npcId }
            val sourceEvent = gameSession.committedEvents(work.sourceSequence - 1, work.sourceSequence)
                .firstOrNull { it.eventId == work.sourceEventId }
            val directedToProfile = sourceEvent?.directedNpcWake == true && sourceEvent.targetNpcId == profile?.id
            val remoteDirected = directedToProfile && sourceEvent.audience == NpcDialogueAudience.PRIVATE &&
                sourceEvent.communicationMethodId in profile?.remoteCommunicationMethodIds.orEmpty()
            val visibleToProfile = sourceEvent?.visibleNpcIds?.let { profile?.id in it }
                ?: (profile?.entityId in current.currentSceneParticipantIds)
            if (profile == null || !visibleToProfile ||
                (profile.entityId !in current.currentSceneParticipantIds && !remoteDirected) ||
                sourceEvent == null ||
                (!directedToProfile && work.eventType !in profile.wakeEventTypes) ||
                work.sceneId != current.currentSceneId
            ) {
                update(work, work.copy(status = NpcWorkStatus.SKIPPED, revision = work.revision + 1))
                continue
            }
            val running = work.copy(
                status = NpcWorkStatus.RUNNING,
                revision = work.revision + 1,
                acceptedSequence = current.lastSequence,
            )
            if (!update(work, running)) continue
            turns += 1
            val agentProfile = profile.toAgentProfile(request.runId, sourceEvent)
            val memoryStore = memoryStoreFactory?.invoke(request.runId) ?: fallbackMemoryStore
            val trigger = NpcTrigger(
                id = work.sourceEventId,
                kind = work.eventType.value,
                input = sourceEvent.publicInput?.let { playerInput ->
                    when {
                        sourceEvent.audience == NpcDialogueAudience.PRIVATE ->
                            "玩家悄悄对你说：$playerInput\n回应必须调用 npc.speak，audience=PRIVATE，并保持触发消息的通讯方式。"
                        directedToProfile ->
                            "玩家在身边众人面前对你说：$playerInput\n回应必须调用获准的 NPC 工具。"
                        else ->
                            "你听见玩家在身边众人面前对 ${sourceEvent.targetNpcId?.value} 说：$playerInput\n只有确有必要时才调用获准的 NPC 工具回应。"
                    }
                } ?: "处理当前场景中序列 ${work.sourceSequence} 的已提交事件。公开反应必须调用获准的 NPC 工具。",
                sourceSequence = work.sourceSequence,
                sourceEventIds = setOf(work.sourceEventId),
                relatedEntityIds = (current.currentSceneParticipantIds + profile.entityId)
                    .mapTo(mutableSetOf()) { it.value },
                tags = buildSet {
                    if (sourceEvent.audience == NpcDialogueAudience.PRIVATE) add("private-dialogue")
                    if (sourceEvent.communicationMethodId != null) add("remote-communication")
                },
            )
            val result = try {
                NpcAgent(
                    profile = agentProfile,
                    runtime = runtime,
                    memoryStore = memoryStore,
                ).respond(trigger, NpcContextProjector(gameSession).project(agentProfile, trigger))
            } catch (_: Exception) {
                NpcAgentResult.Failed(
                    agentProfile.agentId,
                    AgentRunError(AgentRunErrorCode.INVALID_PROVIDER_RESPONSE, "NPC context projection failed"),
                )
            }
            val delivered = gameSession.commandContext()?.lastSequence ?: running.acceptedSequence ?: work.sourceSequence
            val publicActions = gameSession.publicNpcActions(running.acceptedSequence ?: work.sourceSequence, delivered)
                .filter { it.entityId == profile.entityId }
            val revealedEvents = gameSession.committedEvents(running.acceptedSequence ?: work.sourceSequence, delivered)
                .filter { it.eventType == NPC_KNOWLEDGE_REVEALED_EVENT_TYPE_ID && it.targetNpcId == profile.id }
            publicResults += publicActions.map { PublicFollowUp(it.displayName, it.content) }
            if (result is NpcAgentResult.Responded && sourceEvent.directedNpcWake) {
                val publicEpisode = buildString {
                    sourceEvent.publicInput?.let {
                        appendLine(if (sourceEvent.audience == NpcDialogueAudience.PRIVATE) "玩家私聊：$it" else "玩家说：$it")
                    }
                    publicActions.forEach {
                        appendLine(if (it.audience == NpcDialogueAudience.PRIVATE) "私密回应：${it.content}" else "公开回应：${it.content}")
                    }
                    revealedEvents.mapNotNull { it.publicInput }.forEach { appendLine("公开知识：$it") }
                }.trim()
                if (publicEpisode.isNotEmpty()) {
                    memoryStore.upsertMemory(
                        AgentMemoryRecord(
                            id = AgentMemoryId("dialogue:${work.id.value}"),
                            agentId = agentProfile.agentId,
                            kind = AgentMemoryKind.EPISODIC,
                            content = publicEpisode,
                            salience = 0.75,
                            confidence = 1.0,
                            tags = setOf("npc-dialogue", "scene:${work.sceneId.value}"),
                            relatedEntityIds = current.currentSceneParticipantIds.mapTo(mutableSetOf()) { it.value },
                            sourceEventIds = buildSet {
                                add(work.sourceEventId)
                                addAll(publicActions.map { it.eventId })
                                addAll(revealedEvents.map { it.eventId })
                            },
                            createdSequence = result.turnSequence,
                        ),
                    )
                }
            }
            val terminal = when (result) {
                is NpcAgentResult.Responded -> running.copy(
                    status = NpcWorkStatus.COMPLETED,
                    revision = running.revision + 1,
                    deliveredSequence = delivered,
                    publicEventIds = publicActions.map { it.eventId },
                )
                is NpcAgentResult.Failed -> running.copy(
                    status = NpcWorkStatus.FAILED,
                    revision = running.revision + 1,
                    deliveredSequence = delivered,
                    publicEventIds = publicActions.map { it.eventId },
                    errorCode = "agent.${result.error.code.name.lowercase()}",
                )
            }
            update(running, terminal)
            val refreshed = gameSession.commandContext()
            if (refreshed != null) {
                gameSession.committedEvents(0, refreshed.lastSequence).forEach { event ->
                    enqueue(event, refreshed.npcProfiles, request.runId)
                }
            }
        }
        GameTurnFollowUpResult.Completed(publicResults)
    }

    private suspend fun enqueue(event: SessionCommittedEvent, profiles: List<SessionNpcProfile>, runId: RunId) {
        val sceneId = event.sceneId ?: return
        profiles.asSequence()
            .filter { profile ->
                val visible = event.visibleNpcIds?.let { profile.id in it } ?: (profile.entityId in event.participantIds)
                val targeted = event.directedNpcWake && event.targetNpcId == profile.id
                visible && (targeted || event.eventType in profile.wakeEventTypes)
            }
            .sortedBy { it.id.value }
            .take(policy.maxWakesPerEvent)
            .forEach { profile ->
                val id = NpcWorkId("npc:${event.eventId}:${profile.id.value}")
                workStore.create(
                    NpcWorkItem(
                        id = id,
                        runId = runId,
                        npcId = profile.id,
                        sourceEventId = event.eventId,
                        sourceSequence = event.sequence,
                        eventType = event.eventType,
                        sceneId = sceneId,
                    ),
                )
            }
    }

    private suspend fun update(previous: NpcWorkItem, next: NpcWorkItem): Boolean =
        workStore.update(previous.revision, next) is NpcWorkUpdateResult.Updated

    private fun SessionNpcProfile.toAgentProfile(
        runId: RunId,
        sourceEvent: SessionCommittedEvent,
    ): NpcAgentProfile = NpcAgentProfile(
        agentId = AgentId("worldloom.agent.npc.${id.value}"),
        actorId = actorId,
        sessionId = AgentSessionId("${runId.value}.npc.${id.value}"),
        identityPrompt = identityPrompt,
        permissions = buildSet {
            if (canSpeak || publicActionIds.isNotEmpty()) add(CommandPermission.PUBLISH_NPC_ACTION)
            if (knowledge.any { it.revealable }) add(CommandPermission.REVEAL_NPC_KNOWLEDGE)
        },
        wakePolicy = NpcWakePolicy(triggerKinds = wakeEventTypes.mapTo(mutableSetOf()) { it.value }),
        runId = runId,
        entityId = entityId,
        displayName = displayName,
        visiblePresentationIds = visiblePresentationIds,
        privateKnowledge = privateKnowledge + knowledge.map { it.privateText },
        revealableKnowledgeIds = knowledge.filter { it.revealable }.mapTo(mutableSetOf()) { it.id },
        publicActionIds = publicActionIds,
        replyAudience = if (sourceEvent.directedNpcWake) sourceEvent.audience else null,
        communicationMethodId = if (sourceEvent.directedNpcWake) sourceEvent.communicationMethodId else null,
    )
}
