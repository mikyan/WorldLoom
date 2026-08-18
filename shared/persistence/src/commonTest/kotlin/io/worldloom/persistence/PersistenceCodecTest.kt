package io.worldloom.persistence

import io.worldloom.definition.DefinitionId
import io.worldloom.rules.CheckId
import io.worldloom.rules.CheckRecord
import io.worldloom.rules.CheckResolvedEvent
import io.worldloom.rules.DiceRandomRequest
import io.worldloom.rules.RANDOM_ALGORITHM_VERSION
import io.worldloom.rules.RandomRecord
import io.worldloom.rules.RandomRecordId
import io.worldloom.rules.ActivityCompletedEvent
import io.worldloom.rules.ScheduledTriggerFiredEvent
import io.worldloom.rules.TravelCompletedEvent
import io.worldloom.rules.TravelStartedEvent
import io.worldloom.rules.WorldTimeAdvancedEvent
import io.worldloom.rules.AdventureEndingReachedEvent
import io.worldloom.rules.ConditionUpdatedEvent
import io.worldloom.rules.InventoryChangedEvent
import io.worldloom.rules.InventoryOperation
import io.worldloom.rules.ProgressClockAdvancedEvent
import io.worldloom.rules.QuestAdvancedEvent
import io.worldloom.rules.QuestStatus
import io.worldloom.rules.RelationshipAdjustedEvent
import io.worldloom.world.CommandId
import io.worldloom.world.CURRENT_EVENT_SCHEMA_VERSION
import io.worldloom.world.EventEnvelope
import io.worldloom.world.EventId
import io.worldloom.world.GameState
import io.worldloom.world.RunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PersistenceCodecTest {
    @Test
    fun roundTripsEveryAdventureStateEvent() {
        val payloads = listOf(
            InventoryChangedEvent(
                itemId = DefinitionId("test.item"),
                operation = InventoryOperation.ACQUIRE,
                previousQuantity = 1,
                quantityDelta = 2,
                quantity = 3,
            ),
            ConditionUpdatedEvent(
                conditionId = DefinitionId("test.condition"),
                previousStacks = 0,
                stacks = 1,
                previousRemainingMinutes = 0,
                remainingMinutes = 30,
            ),
            RelationshipAdjustedEvent(
                relationshipId = DefinitionId("test.relationship"),
                previousValue = 0,
                delta = 1,
                value = 1,
            ),
            QuestAdvancedEvent(
                questId = DefinitionId("test.quest"),
                stageId = DefinitionId("test.quest.stage"),
                previousStatus = QuestStatus.NOT_STARTED,
                status = QuestStatus.ACTIVE,
            ),
            ProgressClockAdvancedEvent(
                clockId = DefinitionId("test.clock"),
                previousValue = 0,
                delta = 2,
                value = 2,
            ),
            AdventureEndingReachedEvent(endingId = DefinitionId("test.ending")),
        )

        payloads.forEachIndexed { index, payload ->
            val event = EventEnvelope(
                schemaVersion = CURRENT_EVENT_SCHEMA_VERSION,
                eventId = EventId("event.adventure.$index"),
                runId = RunId("run.adventure"),
                sequence = index + 1L,
                causationId = CommandId("command.adventure"),
                correlationId = "command.adventure",
                payload = payload,
            )
            assertEquals(
                event,
                assertIs<PersistenceDecodeResult.Success<EventEnvelope>>(
                    PersistenceCodec.decodeEvent(PersistenceCodec.encodeEvent(event)),
                ).value,
            )
        }
    }

    @Test
    fun roundTripsEveryTemporalAuditEvent() {
        val payloads = listOf(
            WorldTimeAdvancedEvent(previousMinute = 10, deltaMinutes = 20, minute = 30, reasonId = DefinitionId("test.reason")),
            ActivityCompletedEvent(
                activityId = DefinitionId("test.activity"),
                outcomeId = DefinitionId("test.outcome"),
                durationMinutes = 20,
            ),
            TravelStartedEvent(
                routeId = DefinitionId("test.route"),
                fromSceneId = DefinitionId("test.scene.a"),
                toSceneId = DefinitionId("test.scene.b"),
            ),
            TravelCompletedEvent(
                routeId = DefinitionId("test.route"),
                outcomeId = DefinitionId("test.outcome"),
                arrived = true,
            ),
            ScheduledTriggerFiredEvent(triggerId = DefinitionId("test.trigger"), scheduledMinute = 30),
        )

        payloads.forEachIndexed { index, payload ->
            val event = EventEnvelope(
                schemaVersion = CURRENT_EVENT_SCHEMA_VERSION,
                eventId = EventId("event.temporal.$index"),
                runId = RunId("run.temporal"),
                sequence = index + 1L,
                causationId = CommandId("command.temporal"),
                correlationId = "command.temporal",
                payload = payload,
            )
            assertEquals(
                event,
                assertIs<PersistenceDecodeResult.Success<EventEnvelope>>(
                    PersistenceCodec.decodeEvent(PersistenceCodec.encodeEvent(event)),
                ).value,
            )
        }
    }

    @Test
    fun roundTripsPolymorphicCheckEventAndGenericState() {
        val runId = RunId("run.codec")
        val event = EventEnvelope(
            schemaVersion = CURRENT_EVENT_SCHEMA_VERSION,
            eventId = EventId("event.codec"),
            runId = runId,
            sequence = 1,
            causationId = CommandId("command.codec"),
            correlationId = "correlation.codec",
            payload = CheckResolvedEvent(
                CheckRecord(
                    checkId = CheckId("check.codec"),
                    profileId = DefinitionId("test.check.primary"),
                    baseValue = 0,
                    modifier = 0,
                    randomRecord = RandomRecord(
                        RandomRecordId("random.codec"),
                        RANDOM_ALGORITHM_VERSION,
                        DiceRandomRequest(2, 6),
                        listOf(3, 4),
                    ),
                    total = 7,
                    outcomeId = DefinitionId("test.outcome.cost"),
                ),
            ),
        )
        val state = GameState(
            runId,
            DefinitionId("contract.codec"),
            lastSequence = 1,
            entities = emptyMap(),
            variables = emptyMap(),
            moduleStates = emptyMap(),
        )

        assertEquals(
            event,
            assertIs<PersistenceDecodeResult.Success<EventEnvelope>>(
                PersistenceCodec.decodeEvent(PersistenceCodec.encodeEvent(event)),
            ).value,
        )
        assertEquals(
            state,
            assertIs<PersistenceDecodeResult.Success<GameState>>(
                PersistenceCodec.decodeState(PersistenceCodec.encodeState(state)),
            ).value,
        )
    }
}
