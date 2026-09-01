package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.world.packageformat.PlayableGuidanceTarget
import io.worldloom.world.packageformat.PlayableGuidanceTargetKind
import io.worldloom.world.packageformat.PlayableTutorialTriggerKind
import io.worldloom.world.packageformat.PlayableSuggestionTargetKind
import io.worldloom.world.packageformat.PlayableSuggestionTier
import io.worldloom.world.packageformat.PlayableWorldContract

enum class GuidanceTargetKind { ACTION, ACTIVITY, TRAVEL, NPC, DRAFT }

data class PresentedGuidanceSuggestion(
    val targetKind: GuidanceTargetKind,
    val targetId: DefinitionId,
    val label: String,
    val inputDraft: String,
    val rationale: String? = null,
    val tradeoff: String? = null,
    val tier: PlayableSuggestionTier = PlayableSuggestionTier.EXAMPLE,
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
        addressableNpcIds: Set<DefinitionId> = emptySet(),
        visibleKnowledgeIds: Set<DefinitionId> = emptySet(),
        availableItemIds: Set<DefinitionId> = emptySet(),
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
        val authored = contract.exploration?.suggestions.orEmpty().mapNotNull { suggestion ->
            if (
                suggestion.sceneId != currentSceneId ||
                suggestion.groundingKnowledgeIds.any { it !in visibleKnowledgeIds } ||
                suggestion.requiredItemIds.any { it !in availableItemIds }
            ) {
                return@mapNotNull null
            }
            val projectedTarget = when (suggestion.target.kind) {
                PlayableSuggestionTargetKind.ACTION -> suggestion.target.id?.let { targets[TargetKey(PlayableGuidanceTargetKind.ACTION, it)] }
                PlayableSuggestionTargetKind.ACTIVITY -> suggestion.target.id?.let { targets[TargetKey(PlayableGuidanceTargetKind.ACTIVITY, it)] }
                PlayableSuggestionTargetKind.TRAVEL -> suggestion.target.id?.let { targets[TargetKey(PlayableGuidanceTargetKind.TRAVEL, it)] }
                PlayableSuggestionTargetKind.NPC -> suggestion.target.id?.takeIf { it in addressableNpcIds }?.let {
                    PresentedGuidanceSuggestion(GuidanceTargetKind.NPC, it, suggestion.label, suggestion.inputDraft)
                }
                PlayableSuggestionTargetKind.DRAFT -> PresentedGuidanceSuggestion(
                    GuidanceTargetKind.DRAFT,
                    suggestion.id,
                    suggestion.label,
                    suggestion.inputDraft,
                )
            } ?: return@mapNotNull null
            projectedTarget.copy(
                label = suggestion.label,
                inputDraft = suggestion.inputDraft,
                rationale = suggestion.rationale,
                tradeoff = suggestion.tradeoff?.takeIf { suggestion.tradeoffEvidenceIds.all(visibleKnowledgeIds::contains) },
                tier = suggestion.tier,
            )
        }
        val suggestions = authored.filter { it.tier == PlayableSuggestionTier.EXAMPLE }.take(4)
        val authoredHints = authored.filter { it.tier != PlayableSuggestionTier.EXAMPLE }.map { suggestion ->
            PresentedSceneHint(suggestion.targetId, suggestion.rationale ?: suggestion.label, suggestion)
        }
        val playable = targets.isNotEmpty()
        return GuidancePresentation(
            tutorials = tutorials,
            hints = (hints + authoredHints).distinctBy(PresentedSceneHint::id),
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
