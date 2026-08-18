package io.worldloom.rules

import io.worldloom.definition.DefinitionId
import io.worldloom.world.GameEventPayload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CheckRecord(
    val checkId: CheckId,
    val profileId: DefinitionId,
    val baseValue: Long,
    val modifier: Long,
    val randomRecord: RandomRecord? = null,
    val total: Long,
    val outcomeId: DefinitionId,
)

@Serializable
@SerialName("check-resolved")
data class CheckResolvedEvent(
    val record: CheckRecord,
) : GameEventPayload
