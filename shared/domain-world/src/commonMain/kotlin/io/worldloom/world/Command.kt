package io.worldloom.world

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.EntitySeed
import kotlinx.serialization.SerialName
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable

const val CURRENT_COMMAND_SCHEMA_VERSION: Int = 1
const val CURRENT_RUN_LIFECYCLE_COMMAND_SCHEMA_VERSION: Int = 1
const val CURRENT_CHARACTER_CREATION_COMMAND_SCHEMA_VERSION: Int = 1

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
}

data class CommandAuthorization(
    val actorId: ActorId,
    val permissions: Set<CommandPermission>,
)
