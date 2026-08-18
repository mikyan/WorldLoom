package io.worldloom.world

import io.worldloom.definition.DefinitionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable

const val CURRENT_EVENT_SCHEMA_VERSION: Int = 1

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
data class EventEnvelope(
    val schemaVersion: Int,
    val eventId: EventId,
    val runId: RunId,
    val sequence: Long,
    val causationId: CommandId,
    val correlationId: String,
    @Polymorphic val payload: GameEventPayload,
)
