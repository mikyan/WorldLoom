package io.worldloom.ui.game

import io.worldloom.application.GuidancePresentation
import io.worldloom.application.PresentedTutorialStep
import io.worldloom.definition.DefinitionId

/** Non-authoritative UI preference state. No transition writes EventLog facts. */
internal data class GuidanceInteractionState(
    val skipped: Boolean = false,
    val reviewing: Boolean = false,
    val completedTutorialIds: Set<DefinitionId> = emptySet(),
) {
    fun visibleTutorials(guidance: GuidancePresentation): List<PresentedTutorialStep> = when {
        skipped -> emptyList()
        reviewing -> guidance.tutorials
        else -> guidance.tutorials.filterNot { it.id in completedTutorialIds }
    }

    fun complete(tutorialId: DefinitionId, guidance: GuidancePresentation): GuidanceInteractionState {
        val completed = completedTutorialIds + tutorialId
        return copy(
            completedTutorialIds = completed,
            reviewing = reviewing && guidance.tutorials.any { it.id !in completed },
        )
    }

    fun skip(): GuidanceInteractionState = copy(skipped = true, reviewing = false)

    fun review(): GuidanceInteractionState = copy(
        skipped = false,
        reviewing = true,
        completedTutorialIds = emptySet(),
    )
}
