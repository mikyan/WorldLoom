package io.worldloom.world

import io.worldloom.definition.DefinitionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable

const val CURRENT_COMMAND_SCHEMA_VERSION: Int = 1

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
}

data class CommandAuthorization(
    val actorId: ActorId,
    val permissions: Set<CommandPermission>,
)
