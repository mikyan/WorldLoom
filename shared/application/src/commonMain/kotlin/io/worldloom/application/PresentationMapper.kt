package io.worldloom.application

import io.worldloom.definition.IntegerValue
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.world.EventEnvelope
import io.worldloom.world.GameState
import io.worldloom.world.NumericComponentAdjustedEvent
import io.worldloom.world.ActionOutcomeAppliedEvent
import io.worldloom.world.PlayerEnteredInitialSceneEvent
import io.worldloom.world.PlayerEnteredSceneEvent
import io.worldloom.world.PlayerExitedSceneEvent
import io.worldloom.world.NpcPublicActionPublishedEvent
import io.worldloom.world.RunLifecycleChangedEvent
import io.worldloom.rules.ActivityCompletedEvent
import io.worldloom.rules.ScheduledTriggerFiredEvent
import io.worldloom.rules.TravelCompletedEvent
import io.worldloom.rules.TravelStartedEvent
import io.worldloom.rules.WorldTimeAdvancedEvent
import io.worldloom.rules.CheckResolvedEvent
import io.worldloom.rules.AdventureEndingReachedEvent
import io.worldloom.rules.ConditionUpdatedEvent
import io.worldloom.rules.InventoryChangedEvent
import io.worldloom.rules.ProgressClockAdvancedEvent
import io.worldloom.rules.QuestAdvancedEvent
import io.worldloom.rules.RelationshipAdjustedEvent

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

        val sortedEvents = events.sortedBy { it.sequence }
        val timelineEvents = sortedEvents.takeLast(TIMELINE_WINDOW_SIZE)
        val timeline = timelineEvents.map { event -> presentEvent(definition, event) }

        return PresentationMappingResult.Success(
            GamePresentation(
                worldId = definition.source.id,
                title = definition.source.title,
                lastSequence = state.lastSequence,
                fields = fields,
                checks = definition.source.presentationChecks
                    .sortedBy { it.id.value }
                    .map { PresentedCheck(it.id, it.label) },
                timeline = timeline,
                timelineTotalCount = sortedEvents.size,
                timelineTruncated = sortedEvents.size > timeline.size,
            ),
        )
    }

    fun presentEvent(definition: ValidatedWorldDefinition, event: EventEnvelope): PresentedEvent {
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

                is CheckResolvedEvent -> {
                    val profile = definition.checkProfile(payload.record.profileId)
                    val outcome = profile?.outcomes?.firstOrNull { it.id == payload.record.outcomeId }
                    if (profile == null || outcome == null) {
                        "检定已结算"
                    } else {
                        "${profile.label}: ${payload.record.total} · ${outcome.label}"
                    }
                }

                is ActionOutcomeAppliedEvent -> "行动已结算：${payload.actionId.value} · ${payload.outcomeId.value}"
                is PlayerExitedSceneEvent -> "离开场景：${payload.sceneId.value}"
                is PlayerEnteredSceneEvent -> "进入场景：${payload.sceneId.value}"
                is PlayerEnteredInitialSceneEvent -> "进入初始场景：${payload.sceneId.value}"
                is RunLifecycleChangedEvent -> "游戏阶段：${payload.lifecycle.name}"
                is WorldTimeAdvancedEvent -> "世界时间推进 ${payload.deltaMinutes} 分钟（${payload.minute}）"
                is ActivityCompletedEvent -> if (payload.interrupted) {
                    "活动中断：${payload.activityId.value} · ${payload.outcomeId.value}"
                } else {
                    "活动完成：${payload.activityId.value} · ${payload.outcomeId.value}"
                }
                is TravelStartedEvent -> "开始旅行：${payload.fromSceneId.value} → ${payload.toSceneId.value}"
                is TravelCompletedEvent -> if (payload.arrived) "旅行抵达：${payload.routeId.value}" else "旅行受阻：${payload.routeId.value}"
                is ScheduledTriggerFiredEvent -> "计划事件触发：${payload.triggerId.value}"
                is InventoryChangedEvent -> "物品${payload.operation.name.lowercase()}：${payload.itemId.value} · ${payload.quantity}"
                is ConditionUpdatedEvent -> "状态更新：${payload.conditionId.value} · ${payload.stacks} 层"
                is RelationshipAdjustedEvent -> "关系更新：${payload.relationshipId.value} · ${payload.value}"
                is QuestAdvancedEvent -> "任务推进：${payload.questId.value} · ${payload.status.name}"
                is ProgressClockAdvancedEvent -> "进度钟：${payload.clockId.value} · ${payload.value}"
                is AdventureEndingReachedEvent -> "结局条件达成：${payload.endingId.value}"
                is NpcPublicActionPublishedEvent -> when (payload.kind) {
                    io.worldloom.world.NpcPublicActionKind.SPEECH -> "${payload.entityId.value}：${payload.content}"
                    io.worldloom.world.NpcPublicActionKind.ACTION -> "${payload.entityId.value}：${payload.content}"
                }

            else -> "事件已记录"
        }
        val check = event.payload as? CheckResolvedEvent
        return PresentedEvent(
            sequence = event.sequence,
            summary = summary,
            eventId = event.eventId.value,
            eventType = eventType(event),
            causationId = event.causationId.value,
            correlationId = event.correlationId,
            randomRecord = check?.record?.randomRecord?.let { random ->
                PresentedRandomRecord(
                    recordId = random.id.value,
                    results = random.results,
                    total = check.record.total,
                    outcomeId = check.record.outcomeId,
                )
            },
        )
    }

    private fun eventType(event: EventEnvelope): String = when (event.payload) {
        is NumericComponentAdjustedEvent -> "worldloom.event.numeric-component.adjusted"
        is CheckResolvedEvent -> "worldloom.event.check.resolved"
        is ActionOutcomeAppliedEvent -> "worldloom.event.action.outcome-applied"
        is PlayerExitedSceneEvent -> "worldloom.event.scene.exited"
        is PlayerEnteredSceneEvent, is PlayerEnteredInitialSceneEvent -> "worldloom.event.scene.entered"
        is RunLifecycleChangedEvent -> "worldloom.event.run.lifecycle-changed"
        is WorldTimeAdvancedEvent -> "worldloom.event.world-time.advanced"
        is ActivityCompletedEvent -> "worldloom.event.activity-completed"
        is TravelStartedEvent -> "worldloom.event.travel-started"
        is TravelCompletedEvent -> "worldloom.event.travel-completed"
        is ScheduledTriggerFiredEvent -> "worldloom.event.scheduled-trigger-fired"
        is InventoryChangedEvent -> "worldloom.event.inventory-changed"
        is ConditionUpdatedEvent -> "worldloom.event.condition-updated"
        is RelationshipAdjustedEvent -> "worldloom.event.relationship-adjusted"
        is QuestAdvancedEvent -> "worldloom.event.quest-advanced"
        is ProgressClockAdvancedEvent -> "worldloom.event.progress-clock.advanced"
        is AdventureEndingReachedEvent -> "worldloom.event.adventure-ending.reached"
        is NpcPublicActionPublishedEvent -> "worldloom.event.npc.public-action"
        else -> "worldloom.event.other"
    }

    private const val TIMELINE_WINDOW_SIZE = 200
}
