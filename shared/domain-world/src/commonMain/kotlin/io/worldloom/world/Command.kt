package io.worldloom.world

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.EntitySeed
import kotlinx.serialization.SerialName
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable

const val CURRENT_COMMAND_SCHEMA_VERSION: Int = 1
const val CURRENT_RUN_LIFECYCLE_COMMAND_SCHEMA_VERSION: Int = 1
const val CURRENT_CHARACTER_CREATION_COMMAND_SCHEMA_VERSION: Int = 1
const val CURRENT_ACTION_OUTCOME_COMMAND_SCHEMA_VERSION: Int = 1
const val CURRENT_NPC_PUBLIC_ACTION_COMMAND_SCHEMA_VERSION: Int = 1
const val CURRENT_ADDRESS_NPC_COMMAND_SCHEMA_VERSION: Int = 1
const val CURRENT_REVEAL_NPC_KNOWLEDGE_COMMAND_SCHEMA_VERSION: Int = 1
const val CURRENT_NPC_PRESENCE_COMMAND_SCHEMA_VERSION: Int = 1

@Polymorphic
interface GameCommandPayload

@Serializable
@SerialName("adjust-numeric-component")
data class AdjustNumericComponentCommand(
    val entityId: EntityId,
    val componentId: DefinitionId,
    val fieldId: DefinitionId,
    val delta: Long,
) : GameCommandPayload

@Serializable
@SerialName("change-run-lifecycle")
data class ChangeRunLifecycleCommand(
    val schemaVersion: Int = CURRENT_RUN_LIFECYCLE_COMMAND_SCHEMA_VERSION,
    val lifecycle: RunLifecycle,
) : GameCommandPayload

@Serializable
@SerialName("create-player-character")
data class CreatePlayerCharacterCommand(
    val schemaVersion: Int = CURRENT_CHARACTER_CREATION_COMMAND_SCHEMA_VERSION,
    val profileId: DefinitionId,
    val entity: EntitySeed,
    val initialSceneId: DefinitionId,
    val initialSceneParticipantIds: List<EntityId> = emptyList(),
) : GameCommandPayload

@Serializable
@SerialName("apply-action-outcome")
data class ApplyActionOutcomeCommand(
    val schemaVersion: Int = CURRENT_ACTION_OUTCOME_COMMAND_SCHEMA_VERSION,
    val actionId: DefinitionId,
    val outcomeId: DefinitionId,
    val fromSceneId: DefinitionId,
    val nextSceneId: DefinitionId? = null,
    val objectiveIds: List<DefinitionId> = emptyList(),
    val endingId: DefinitionId? = null,
    val participantIds: List<EntityId> = emptyList(),
) : GameCommandPayload

@Serializable
enum class NpcPublicActionKind { SPEECH, ACTION }

@Serializable
@SerialName("publish-npc-action")
data class PublishNpcActionCommand(
    val schemaVersion: Int = CURRENT_NPC_PUBLIC_ACTION_COMMAND_SCHEMA_VERSION,
    val entityId: EntityId,
    val sceneId: DefinitionId,
    val kind: NpcPublicActionKind,
    val actionId: DefinitionId? = null,
    val content: String,
    val audience: NpcDialogueAudience = NpcDialogueAudience.NEARBY_GROUP,
    val targetEntityId: EntityId? = null,
    val communicationMethodId: DefinitionId? = null,
) : GameCommandPayload

@Serializable
enum class NpcDialogueAudience { NEARBY_GROUP, PRIVATE }

/** Player speech addressed to one configured NPC, with an explicit audience and transport. */
@Serializable
@SerialName("address-npc")
data class AddressNpcCommand(
    val schemaVersion: Int = CURRENT_ADDRESS_NPC_COMMAND_SCHEMA_VERSION,
    val targetNpcId: DefinitionId,
    val targetEntityId: EntityId,
    val sceneId: DefinitionId,
    val content: String,
    val idempotencyKey: String,
    val audience: NpcDialogueAudience = NpcDialogueAudience.NEARBY_GROUP,
    val communicationMethodId: DefinitionId? = null,
) : GameCommandPayload

/** Adds or removes one configured NPC from the player's current-scene presence projection. */
@Serializable
@SerialName("set-npc-presence")
data class SetNpcPresenceCommand(
    val schemaVersion: Int = CURRENT_NPC_PRESENCE_COMMAND_SCHEMA_VERSION,
    val npcId: DefinitionId,
    val entityId: EntityId,
    val sceneId: DefinitionId,
    val present: Boolean,
) : GameCommandPayload

@Serializable
@SerialName("reveal-npc-knowledge")
data class RevealNpcKnowledgeCommand(
    val schemaVersion: Int = CURRENT_REVEAL_NPC_KNOWLEDGE_COMMAND_SCHEMA_VERSION,
    val npcId: DefinitionId,
    val entityId: EntityId,
    val sceneId: DefinitionId,
    val knowledgeId: DefinitionId,
    val publicSummary: String,
) : GameCommandPayload

@Serializable
data class CommandEnvelope(
    val schemaVersion: Int,
    val commandId: CommandId,
    val runId: RunId,
    val actorId: ActorId,
    val expectedSequence: Long,
    val correlationId: String? = null,
    @Polymorphic val payload: GameCommandPayload,
)

enum class CommandPermission {
    ADJUST_NUMERIC_COMPONENT,
    RESOLVE_CHECK,
    MANAGE_RUN_LIFECYCLE,
    CREATE_PLAYER_CHARACTER,
    APPLY_ACTION_OUTCOME,
    ADVANCE_WORLD_TIME,
    PERFORM_ACTIVITY,
    TRAVEL,
    MANAGE_INVENTORY,
    UPDATE_CONDITION,
    UPDATE_RELATIONSHIP,
    UPDATE_QUEST,
    ADVANCE_PROGRESS_CLOCK,
    PUBLISH_NPC_ACTION,
    ADDRESS_NPC,
    MANAGE_NPC_PRESENCE,
    REVEAL_NPC_KNOWLEDGE,
    REVEAL_EXPLORATION_KNOWLEDGE,
}

data class CommandAuthorization(
    val actorId: ActorId,
    val permissions: Set<CommandPermission>,
)
