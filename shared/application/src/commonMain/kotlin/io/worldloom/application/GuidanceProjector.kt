package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.world.packageformat.PlayableGuidanceTarget
import io.worldloom.world.packageformat.PlayableGuidanceTargetKind
import io.worldloom.world.packageformat.PlayableTutorialTriggerKind
import io.worldloom.world.packageformat.PlayableWorldContract

enum class GuidanceTargetKind { ACTION, ACTIVITY, TRAVEL }

data class PresentedGuidanceSuggestion(
    val targetKind: GuidanceTargetKind,
    val targetId: DefinitionId,
    val label: String,
    val inputDraft: String,
)

data class PresentedTutorialStep(
    val id: DefinitionId,
    val text: String,
    val suggestion: PresentedGuidanceSuggestion,
)

data class PresentedSceneHint(
    val id: DefinitionId,
    val text: String,
    val suggestion: PresentedGuidanceSuggestion,
)

data class GuidancePresentation(
    val tutorials: List<PresentedTutorialStep> = emptyList(),
    val hints: List<PresentedSceneHint> = emptyList(),
    val suggestions: List<PresentedGuidanceSuggestion> = emptyList(),
    val playable: Boolean = true,
    val diagnostic: String? = null,
)

/** Definition-driven, read-only guidance. Suggestions only prepare natural-language input. */
object GuidanceProjector {
    fun project(
        contract: PlayableWorldContract,
        currentSceneId: DefinitionId?,
        actions: List<PresentedAction>,
        activities: List<PresentedActivity>,
        travelRoutes: List<PresentedTravelRoute>,
    ): GuidancePresentation {
        if (currentSceneId == null) return GuidancePresentation()
        val targets = buildMap {
            actions.forEach { action ->
                put(
                    TargetKey(PlayableGuidanceTargetKind.ACTION, action.id),
                    PresentedGuidanceSuggestion(
                        GuidanceTargetKind.ACTION,
                        action.id,
                        action.label,
                        "我想${action.label}。",
                    ),
                )
            }
            activities.forEach { activity ->
                put(
                    TargetKey(PlayableGuidanceTargetKind.ACTIVITY, activity.id),
                    PresentedGuidanceSuggestion(
                        GuidanceTargetKind.ACTIVITY,
                        activity.id,
                        activity.label,
                        "我想先${activity.label}。",
                    ),
                )
            }
            travelRoutes.forEach { route ->
                put(
                    TargetKey(PlayableGuidanceTargetKind.TRAVEL, route.id),
                    PresentedGuidanceSuggestion(
                        GuidanceTargetKind.TRAVEL,
                        route.id,
                        route.label,
                        "我想沿${route.label}前进。",
                    ),
                )
            }
        }
        val guidance = contract.guidance
        val tutorials = guidance?.tutorials.orEmpty().mapNotNull { tutorial ->
            val triggered = when (tutorial.trigger) {
                PlayableTutorialTriggerKind.RUN_START -> currentSceneId == contract.initialSceneId
                PlayableTutorialTriggerKind.SCENE_ENTER -> currentSceneId == tutorial.sceneId
            }
            if (!triggered) return@mapNotNull null
            targets[tutorial.target.key()]?.let { PresentedTutorialStep(tutorial.id, tutorial.text, it) }
        }
        val hints = guidance?.hints.orEmpty().mapNotNull { hint ->
            if (hint.sceneId != currentSceneId) return@mapNotNull null
            targets[hint.target.key()]?.let { PresentedSceneHint(hint.id, hint.text, it) }
        }
        val progressOptions = targets.values.toList()
        val suggestions = (hints.map(PresentedSceneHint::suggestion) + progressOptions)
            .distinctBy { it.targetKind to it.targetId }
            .take(4)
        val playable = progressOptions.isNotEmpty()
        return GuidancePresentation(
            tutorials = tutorials,
            hints = hints,
            suggestions = suggestions,
            playable = playable,
            diagnostic = if (playable) null else {
                "当前场景没有可用行动、活动或旅行；内容契约无法提供经过验证的推进出口。"
            },
        )
    }

    private data class TargetKey(val kind: PlayableGuidanceTargetKind, val id: DefinitionId)

    private fun PlayableGuidanceTarget.key(): TargetKey = TargetKey(kind, id)
}
