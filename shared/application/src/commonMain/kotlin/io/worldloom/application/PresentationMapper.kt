package io.worldloom.application

import io.worldloom.definition.IntegerValue
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.world.EventEnvelope
import io.worldloom.world.GameState
import io.worldloom.world.NumericComponentAdjustedEvent

sealed interface PresentationMappingResult {
    data class Success(val presentation: GamePresentation) : PresentationMappingResult

    data class Failure(
        val path: String,
        val message: String,
    ) : PresentationMappingResult
}

object PresentationMapper {
    fun map(
        definition: ValidatedWorldDefinition,
        state: GameState,
        events: List<EventEnvelope>,
    ): PresentationMappingResult {
        val fields = mutableListOf<PresentedField>()
        definition.source.presentation.sortedBy { it.id.value }.forEachIndexed { index, binding ->
            val value = state.entities.entries
                .firstOrNull { it.key.value == binding.entityId }
                ?.value
                ?.components
                ?.get(binding.componentId)
                ?.fields
                ?.get(binding.fieldId)
            if (value !is IntegerValue) {
                return PresentationMappingResult.Failure(
                    path = "presentation[$index]",
                    message = "Presentation binding does not resolve to an integer value",
                )
            }
            fields += PresentedField(
                presentationId = binding.id,
                label = binding.label,
                value = value.value,
                adjustmentStep = binding.adjustmentStep,
            )
        }

        val timeline = events.sortedBy { it.sequence }.map { event ->
            val summary = when (val payload = event.payload) {
                is NumericComponentAdjustedEvent -> {
                    val binding = definition.source.presentation.firstOrNull {
                        it.entityId == payload.entityId.value &&
                            it.componentId == payload.componentId &&
                            it.fieldId == payload.fieldId
                    }
                    if (binding == null) {
                        "状态已更新"
                    } else {
                        "${binding.label}: ${payload.previousValue} → ${payload.newValue}"
                    }
                }
            }
            PresentedEvent(event.sequence, summary)
        }

        return PresentationMappingResult.Success(
            GamePresentation(
                worldId = definition.source.id,
                title = definition.source.title,
                lastSequence = state.lastSequence,
                fields = fields,
                timeline = timeline,
            ),
        )
    }
}
