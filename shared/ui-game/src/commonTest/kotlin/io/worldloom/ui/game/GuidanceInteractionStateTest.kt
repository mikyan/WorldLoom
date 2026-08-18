package io.worldloom.ui.game

import io.worldloom.application.GuidancePresentation
import io.worldloom.application.GuidanceTargetKind
import io.worldloom.application.PresentedGuidanceSuggestion
import io.worldloom.application.PresentedTutorialStep
import io.worldloom.definition.DefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuidanceInteractionStateTest {
    @Test
    fun tutorialCanBeCompletedSkippedAndReviewedWithoutWorldCommands() {
        val guidance = GuidancePresentation(
            tutorials = listOf(tutorial("tutorial.one"), tutorial("tutorial.two")),
        )
        val initial = GuidanceInteractionState()

        val afterFirst = initial.complete(DefinitionId("tutorial.one"), guidance)
        assertEquals(listOf(DefinitionId("tutorial.two")), afterFirst.visibleTutorials(guidance).map { it.id })

        val skipped = afterFirst.skip()
        assertTrue(skipped.skipped)
        assertTrue(skipped.visibleTutorials(guidance).isEmpty())

        val reviewing = skipped.review()
        assertFalse(reviewing.skipped)
        assertEquals(guidance.tutorials, reviewing.visibleTutorials(guidance))
        assertTrue(reviewing.completedTutorialIds.isEmpty())
    }

    private fun tutorial(id: String) = PresentedTutorialStep(
        id = DefinitionId(id),
        text = "Tutorial $id",
        suggestion = PresentedGuidanceSuggestion(
            GuidanceTargetKind.ACTION,
            DefinitionId("action.$id"),
            "Action",
            "I try the action",
        ),
    )
}
