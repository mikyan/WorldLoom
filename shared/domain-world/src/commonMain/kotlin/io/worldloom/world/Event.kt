package io.worldloom.world

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.ComponentSeed
import kotlinx.serialization.SerialName
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable

const val CURRENT_EVENT_SCHEMA_VERSION: Int = 1
const val CURRENT_RUN_LIFECYCLE_EVENT_SCHEMA_VERSION: Int = 1
const val CURRENT_CHARACTER_CREATION_EVENT_SCHEMA_VERSION: Int = 1
const val CURRENT_SCENE_EVENT_SCHEMA_VERSION: Int = 1
const val CURRENT_NPC_PUBLIC_ACTION_EVENT_SCHEMA_VERSION: Int = 1
const val CURRENT_NPC_ADDRESSED_EVENT_SCHEMA_VERSION: Int = 1
val NPC_ADDRESSED_EVENT_TYPE_ID: DefinitionId = DefinitionId("worldloom.event.npc.addressed")
const val CURRENT_NPC_PRESENCE_EVENT_SCHEMA_VERSION: Int = 1
val NPC_PRESENCE_CHANGED_EVENT_TYPE_ID: DefinitionId = DefinitionId("worldloom.event.npc.presence-changed")
const val CURRENT_NPC_KNOWLEDGE_REVEALED_EVENT_SCHEMA_VERSION: Int = 1
val NPC_KNOWLEDGE_REVEALED_EVENT_TYPE_ID: DefinitionId = DefinitionId("worldloom.event.npc.knowledge-revealed")

@Polymorphic
interface GameEventPayload

@Serializable
@SerialName("numeric-component-adjusted")
data class NumericComponentAdjustedEvent(
    val entityId: EntityId,
    val componentId: DefinitionId,
    val fieldId: DefinitionId,
    val previousValue: Long,
    val delta: Long,
    val newValue: Long,
) : GameEventPayload

@Serializable
@SerialName("run-lifecycle-changed")
data class RunLifecycleChangedEvent(
    val schemaVersion: Int = CURRENT_RUN_LIFECYCLE_EVENT_SCHEMA_VERSION,
    val previousLifecycle: RunLifecycle,
    val lifecycle: RunLifecycle,
) : GameEventPayload

@Serializable
@SerialName("player-entity-created")
data class PlayerEntityCreatedEvent(
    val schemaVersion: Int = CURRENT_CHARACTER_CREATION_EVENT_SCHEMA_VERSION,
    val entityId: EntityId,
    val profileId: DefinitionId,
) : GameEventPayload

@Serializable
@SerialName("player-component-initialized")
data class PlayerComponentInitializedEvent(
    val schemaVersion: Int = CURRENT_CHARACTER_CREATION_EVENT_SCHEMA_VERSION,
    val entityId: EntityId,
    val component: ComponentSeed,
) : GameEventPayload

@Serializable
@SerialName("player-entered-initial-scene")
data class PlayerEnteredInitialSceneEvent(
    val schemaVersion: Int = CURRENT_CHARACTER_CREATION_EVENT_SCHEMA_VERSION,
    val entityId: EntityId,
    val sceneId: DefinitionId,
    val participantIds: List<EntityId> = emptyList(),
) : GameEventPayload

@Serializable
@SerialName("action-outcome-applied")
data class ActionOutcomeAppliedEvent(
    val schemaVersion: Int = CURRENT_SCENE_EVENT_SCHEMA_VERSION,
    val actionId: DefinitionId,
    val outcomeId: DefinitionId,
    val objectiveIds: List<DefinitionId>,
    val endingId: DefinitionId? = null,
) : GameEventPayload

@Serializable
@SerialName("player-exited-scene")
data class PlayerExitedSceneEvent(
    val schemaVersion: Int = CURRENT_SCENE_EVENT_SCHEMA_VERSION,
    val entityId: EntityId,
    val sceneId: DefinitionId,
) : GameEventPayload

@Serializable
@SerialName("player-entered-scene")
data class PlayerEnteredSceneEvent(
    val schemaVersion: Int = CURRENT_SCENE_EVENT_SCHEMA_VERSION,
    val entityId: EntityId,
    val sceneId: DefinitionId,
    val participantIds: List<EntityId> = emptyList(),
) : GameEventPayload

@Serializable
@SerialName("npc-public-action-published")
data class NpcPublicActionPublishedEvent(
    val schemaVersion: Int = CURRENT_NPC_PUBLIC_ACTION_EVENT_SCHEMA_VERSION,
    val entityId: EntityId,
    val sceneId: DefinitionId,
    @SerialName("actionKind")
    val kind: NpcPublicActionKind,
    val actionId: DefinitionId? = null,
    val content: String,
    val audience: NpcDialogueAudience = NpcDialogueAudience.NEARBY_GROUP,
    val targetEntityId: EntityId? = null,
    val communicationMethodId: DefinitionId? = null,
) : GameEventPayload

@Serializable
@SerialName("npc-addressed")
data class NpcAddressedEvent(
    val schemaVersion: Int = CURRENT_NPC_ADDRESSED_EVENT_SCHEMA_VERSION,
    val targetNpcId: DefinitionId,
    val targetEntityId: EntityId,
    val sceneId: DefinitionId,
    val content: String,
    val idempotencyKey: String,
    val audience: NpcDialogueAudience = NpcDialogueAudience.NEARBY_GROUP,
    val communicationMethodId: DefinitionId? = null,
) : GameEventPayload

@Serializable
@SerialName("npc-presence-changed")
data class NpcPresenceChangedEvent(
    val schemaVersion: Int = CURRENT_NPC_PRESENCE_EVENT_SCHEMA_VERSION,
    val npcId: DefinitionId,
    val entityId: EntityId,
    val sceneId: DefinitionId,
    val present: Boolean,
) : GameEventPayload

@Serializable
@SerialName("npc-knowledge-revealed")
data class NpcKnowledgeRevealedEvent(
    val schemaVersion: Int = CURRENT_NPC_KNOWLEDGE_REVEALED_EVENT_SCHEMA_VERSION,
    val npcId: DefinitionId,
    val entityId: EntityId,
    val sceneId: DefinitionId,
    val knowledgeId: DefinitionId,
    val publicSummary: String,
) : GameEventPayload

@Serializable
data class EventEnvelope(
    val schemaVersion: Int,
    val eventId: EventId,
    val runId: RunId,
    val sequence: Long,
    val causationId: CommandId,
    val correlationId: String,
    @Polymorphic val payload: GameEventPayload,
)
