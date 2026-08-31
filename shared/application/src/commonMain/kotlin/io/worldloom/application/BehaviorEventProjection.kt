package io.worldloom.application

import io.worldloom.behavior.runtime.BehaviorEventContext
import io.worldloom.definition.BooleanValue
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionReferenceValue
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.TextValue
import io.worldloom.rules.ActivityCompletedEvent
import io.worldloom.rules.AdventureEndingReachedEvent
import io.worldloom.rules.CheckResolvedEvent
import io.worldloom.rules.ConditionUpdatedEvent
import io.worldloom.rules.InventoryChangedEvent
import io.worldloom.rules.ProgressClockAdvancedEvent
import io.worldloom.rules.QuestAdvancedEvent
import io.worldloom.rules.RelationshipAdjustedEvent
import io.worldloom.rules.ScheduledTriggerFiredEvent
import io.worldloom.rules.TravelCompletedEvent
import io.worldloom.rules.TravelStartedEvent
import io.worldloom.rules.WorldTimeAdvancedEvent
import io.worldloom.world.ActionOutcomeAppliedEvent
import io.worldloom.world.EventEnvelope
import io.worldloom.world.NumericComponentAdjustedEvent
import io.worldloom.world.NpcPublicActionPublishedEvent
import io.worldloom.world.NpcAddressedEvent
import io.worldloom.world.NPC_ADDRESSED_EVENT_TYPE_ID
import io.worldloom.world.NpcKnowledgeRevealedEvent
import io.worldloom.world.NPC_KNOWLEDGE_REVEALED_EVENT_TYPE_ID
import io.worldloom.world.NpcPresenceChangedEvent
import io.worldloom.world.NPC_PRESENCE_CHANGED_EVENT_TYPE_ID
import io.worldloom.world.PlayerEnteredInitialSceneEvent
import io.worldloom.world.PlayerEnteredSceneEvent
import io.worldloom.world.PlayerEntityCreatedEvent
import io.worldloom.world.PlayerExitedSceneEvent
import io.worldloom.world.RunLifecycleChangedEvent

internal object BehaviorEventProjector {
    fun project(event: EventEnvelope): BehaviorEventContext? {
        val values = mutableMapOf(
            "event.id" to TextValue(event.eventId.value),
            "event.sequence" to IntegerValue(event.sequence),
            "event.causationId" to TextValue(event.causationId.value),
        )
        val eventType = when (val payload = event.payload) {
            is NumericComponentAdjustedEvent -> DefinitionId("worldloom.event.numeric-adjusted").also {
                values["event.entityId"] = TextValue(payload.entityId.value)
                values["event.componentId"] = DefinitionReferenceValue(payload.componentId)
                values["event.fieldId"] = DefinitionReferenceValue(payload.fieldId)
                values["event.value"] = IntegerValue(payload.newValue)
            }
            is CheckResolvedEvent -> DefinitionId("worldloom.event.check-resolved").also {
                values["event.outcomeId"] = DefinitionReferenceValue(payload.record.outcomeId)
                values["event.total"] = IntegerValue(payload.record.total)
            }
            is RunLifecycleChangedEvent -> DefinitionId("worldloom.event.run-lifecycle.changed").also {
                values["event.lifecycle"] = TextValue(payload.lifecycle.name)
            }
            is PlayerEntityCreatedEvent -> DefinitionId("worldloom.event.player.created").also {
                values["event.entityId"] = TextValue(payload.entityId.value)
            }
            is PlayerEnteredInitialSceneEvent -> DefinitionId("worldloom.event.scene.entered").also {
                values["event.sceneId"] = DefinitionReferenceValue(payload.sceneId)
                values["event.initial"] = BooleanValue(true)
            }
            is PlayerEnteredSceneEvent -> DefinitionId("worldloom.event.scene.entered").also {
                values["event.sceneId"] = DefinitionReferenceValue(payload.sceneId)
                values["event.initial"] = BooleanValue(false)
            }
            is PlayerExitedSceneEvent -> DefinitionId("worldloom.event.scene.exited").also {
                values["event.sceneId"] = DefinitionReferenceValue(payload.sceneId)
            }
            is ActionOutcomeAppliedEvent -> DefinitionId("worldloom.event.action-outcome.applied").also {
                values["event.actionId"] = DefinitionReferenceValue(payload.actionId)
                values["event.outcomeId"] = DefinitionReferenceValue(payload.outcomeId)
            }
            is WorldTimeAdvancedEvent -> DefinitionId("worldloom.event.time-advanced").also {
                values["event.previousMinute"] = IntegerValue(payload.previousMinute)
                values["event.minute"] = IntegerValue(payload.minute)
            }
            is ActivityCompletedEvent -> DefinitionId("worldloom.event.activity-completed").also {
                values["event.activityId"] = DefinitionReferenceValue(payload.activityId)
                values["event.outcomeId"] = DefinitionReferenceValue(payload.outcomeId)
            }
            is TravelStartedEvent -> DefinitionId("worldloom.event.travel-started").also {
                values["event.routeId"] = DefinitionReferenceValue(payload.routeId)
            }
            is TravelCompletedEvent -> DefinitionId("worldloom.event.travel-completed").also {
                values["event.routeId"] = DefinitionReferenceValue(payload.routeId)
                values["event.outcomeId"] = DefinitionReferenceValue(payload.outcomeId)
                values["event.arrived"] = BooleanValue(payload.arrived)
            }
            is ScheduledTriggerFiredEvent -> DefinitionId("worldloom.event.schedule.fired").also {
                values["event.triggerId"] = DefinitionReferenceValue(payload.triggerId)
                values["event.scheduledMinute"] = IntegerValue(payload.scheduledMinute)
            }
            is InventoryChangedEvent -> DefinitionId("worldloom.event.inventory.changed").also {
                values["event.itemId"] = DefinitionReferenceValue(payload.itemId)
                values["event.quantity"] = IntegerValue(payload.quantity)
            }
            is ConditionUpdatedEvent -> DefinitionId("worldloom.event.condition.updated").also {
                values["event.conditionId"] = DefinitionReferenceValue(payload.conditionId)
                values["event.stacks"] = IntegerValue(payload.stacks)
            }
            is RelationshipAdjustedEvent -> DefinitionId("worldloom.event.relationship.adjusted").also {
                values["event.relationshipId"] = DefinitionReferenceValue(payload.relationshipId)
                values["event.value"] = IntegerValue(payload.value)
            }
            is QuestAdvancedEvent -> DefinitionId("worldloom.event.quest.advanced").also {
                values["event.questId"] = DefinitionReferenceValue(payload.questId)
                values["event.stageId"] = DefinitionReferenceValue(payload.stageId)
                values["event.status"] = TextValue(payload.status.name)
            }
            is ProgressClockAdvancedEvent -> DefinitionId("worldloom.event.progress-clock.advanced").also {
                values["event.clockId"] = DefinitionReferenceValue(payload.clockId)
                values["event.value"] = IntegerValue(payload.value)
            }
            is AdventureEndingReachedEvent -> DefinitionId("worldloom.event.adventure-ending.reached").also {
                values["event.endingId"] = DefinitionReferenceValue(payload.endingId)
            }
            is NpcPublicActionPublishedEvent -> DefinitionId("worldloom.event.npc.public-action").also {
                values["event.entityId"] = TextValue(payload.entityId.value)
                values["event.actionKind"] = TextValue(payload.kind.name)
                payload.actionId?.let { values["event.actionId"] = DefinitionReferenceValue(it) }
            }
            is NpcAddressedEvent -> NPC_ADDRESSED_EVENT_TYPE_ID.also {
                values["event.targetNpcId"] = DefinitionReferenceValue(payload.targetNpcId)
                values["event.targetEntityId"] = TextValue(payload.targetEntityId.value)
            }
            is NpcKnowledgeRevealedEvent -> NPC_KNOWLEDGE_REVEALED_EVENT_TYPE_ID.also {
                values["event.npcId"] = DefinitionReferenceValue(payload.npcId)
                values["event.knowledgeId"] = DefinitionReferenceValue(payload.knowledgeId)
            }
            is NpcPresenceChangedEvent -> NPC_PRESENCE_CHANGED_EVENT_TYPE_ID.also {
                values["event.npcId"] = DefinitionReferenceValue(payload.npcId)
                values["event.entityId"] = TextValue(payload.entityId.value)
                values["event.present"] = BooleanValue(payload.present)
            }
            else -> return null
        }
        return BehaviorEventContext(eventType, event.eventId.value, values)
    }
}
