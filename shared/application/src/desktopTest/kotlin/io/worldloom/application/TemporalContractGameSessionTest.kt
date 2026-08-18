package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.rules.CheckResolvedEvent
import io.worldloom.rules.ScheduledTriggerFiredEvent
import io.worldloom.rules.ActivityCompletedEvent
import io.worldloom.world.ActorId
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandPermission
import io.worldloom.world.EventAppendResult
import io.worldloom.world.EventEnvelope
import io.worldloom.world.EventStore
import io.worldloom.world.EventStoreError
import io.worldloom.world.EventStoreErrorCode
import io.worldloom.world.InMemoryEventStore
import io.worldloom.world.RunId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TemporalContractGameSessionTest {
    @Test
    fun configuredActivityInterruptionUsesElapsedTimeWithoutRunningItsCheck() = runTest {
        val store = InMemoryEventStore()
        val session = session("war-survival", store, "interrupted-activity")
        assertIs<LoadResult.Success>(session.load(DefinitionId("contract.war-survival")))
        assertIs<ActionResult.Success>(session.confirmCharacter())

        assertIs<ActionResult.Success>(
            session.execute(
                GameSessionCommand.PerformActivity(
                    activityId = DefinitionId("war.activity.search"),
                    interrupted = true,
                ),
                CommandAuthorization(ActorId("gm.test"), setOf(CommandPermission.PERFORM_ACTIVITY)),
            ),
        )

        val ready = ready(session)
        assertEquals(510, ready.presentation.worldTimeMinutes)
        assertTrue(ready.presentation.timeline.last { it.summary.contains("活动") }.summary.startsWith("活动中断"))
        val events = store.read(RunId("interrupted-activity.run.1"))
        assertEquals(0, events.count { it.payload is CheckResolvedEvent })
        assertTrue(events.any { (it.payload as? ActivityCompletedEvent)?.interrupted == true })
    }

    @Test
    fun warWorldAdvancesTimeFiresScheduleOnceAndReplaysExactly() = runTest {
        val store = InMemoryEventStore()
        val session = session("war-survival", store, "war-time")

        assertIs<LoadResult.Success>(session.load(DefinitionId("contract.war-survival")))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        val initial = ready(session)
        assertEquals(480, initial.presentation.worldTimeMinutes)
        assertTrue(initial.presentation.activities.any { it.id.value == "war.activity.search" })
        assertTrue(initial.presentation.travelRoutes.any { it.id.value == "war.travel.ruins-to-shelter" })

        assertIs<ActionResult.Success>(session.perform(GameSessionAction.AdvanceWorldTime(120)))
        val nightfall = ready(session)
        assertEquals(600, nightfall.presentation.worldTimeMinutes)
        assertEquals(6, nightfall.presentation.fields.single().value)

        assertIs<ActionResult.Success>(session.perform(GameSessionAction.AdvanceWorldTime(1_440)))
        val nextDay = ready(session)
        assertEquals(2_040, nextDay.presentation.worldTimeMinutes)
        val events = store.read(RunId("war-time.run.1"))
        assertEquals(1, events.count { it.payload is ScheduledTriggerFiredEvent })
        assertTrue(events.zipWithNext().all { (left, right) -> right.sequence == left.sequence + 1 })

        assertIs<SessionReplayResult.Success>(session.replay())
        assertEquals(nextDay.presentation, ready(session).presentation)
    }

    @Test
    fun stationTravelActivitiesAndConcurrentWaitsUseTheSameRuntime() = runTest {
        val store = InMemoryEventStore()
        val session = session("station-ai", store, "station-time")

        assertIs<LoadResult.Success>(session.load(DefinitionId("contract.station-ai")))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        val initialEnergy = ready(session).presentation.fields.single().value
        assertIs<ActionResult.Success>(
            session.perform(GameSessionAction.Travel(DefinitionId("station.travel.core-to-relay"))),
        )
        assertEquals(20, ready(session).presentation.worldTimeMinutes)
        assertEquals("station.scene.relay", ready(session).presentation.scene?.id?.value)

        assertIs<ActionResult.Success>(
            session.perform(GameSessionAction.PerformActivity(DefinitionId("station.activity.wait-cycle"))),
        )
        assertIs<ActionResult.Success>(
            session.perform(GameSessionAction.Travel(DefinitionId("station.travel.relay-to-core"))),
        )
        val returned = ready(session)
        assertEquals(70, returned.presentation.worldTimeMinutes)
        assertEquals("station.scene.core", returned.presentation.scene?.id?.value)
        assertEquals(initialEnergy - 5, returned.presentation.fields.single().value)

        val results = listOf(
            async { session.perform(GameSessionAction.AdvanceWorldTime(15)) },
            async { session.perform(GameSessionAction.AdvanceWorldTime(15)) },
        ).awaitAll()
        assertTrue(results.all { it is ActionResult.Success })
        assertEquals(100, ready(session).presentation.worldTimeMinutes)
        assertEquals(1, store.read(RunId("station-time.run.1")).count { it.payload is ScheduledTriggerFiredEvent })
        assertIs<SessionReplayResult.Success>(session.replay())
        assertEquals(100, ready(session).presentation.worldTimeMinutes)
    }

    @Test
    fun rejectedAtomicActivityAppendDoesNotRerollOnRetry() = runTest {
        val rejectingStore = RejectOnceAppendStore(rejectAppendNumber = 3)
        val retried = session("war-survival", rejectingStore, "retry-random")
        assertIs<LoadResult.Success>(retried.load(DefinitionId("contract.war-survival")))
        assertIs<ActionResult.Success>(retried.confirmCharacter())

        val rejected = assertIs<ActionResult.Failure>(
            retried.perform(GameSessionAction.PerformActivity(DefinitionId("war.activity.search"))),
        )
        assertEquals(SessionErrorCode.EVENT_STORE_REJECTED, rejected.error.code)
        assertEquals(5, ready(retried).presentation.lastSequence)
        assertIs<ActionResult.Success>(
            retried.perform(GameSessionAction.PerformActivity(DefinitionId("war.activity.search"))),
        )
        val retriedCheck = rejectingStore.read(RunId("retry-random.run.1"))
            .mapNotNull { it.payload as? CheckResolvedEvent }
            .single()

        val referenceStore = InMemoryEventStore()
        val reference = session("war-survival", referenceStore, "reference-random")
        assertIs<LoadResult.Success>(reference.load(DefinitionId("contract.war-survival")))
        assertIs<ActionResult.Success>(reference.confirmCharacter())
        assertIs<ActionResult.Success>(
            reference.perform(GameSessionAction.PerformActivity(DefinitionId("war.activity.search"))),
        )
        val referenceCheck = referenceStore.read(RunId("reference-random.run.1"))
            .mapNotNull { it.payload as? CheckResolvedEvent }
            .single()

        assertEquals(referenceCheck.record.outcomeId, retriedCheck.record.outcomeId)
        assertEquals(referenceCheck.record.total, retriedCheck.record.total)
        assertEquals(referenceCheck.record.randomRecord?.results, retriedCheck.record.randomRecord?.results)
    }

    @Test
    fun invalidWaitDoesNotAppendOrChangeWorldTime() = runTest {
        val store = InMemoryEventStore()
        val session = session("station-ai", store, "invalid-time")
        assertIs<LoadResult.Success>(session.load(DefinitionId("contract.station-ai")))
        assertIs<ActionResult.Success>(session.confirmCharacter())

        val failure = assertIs<ActionResult.Failure>(session.perform(GameSessionAction.AdvanceWorldTime(0)))

        assertEquals(SessionErrorCode.COMMAND_REJECTED, failure.error.code)
        assertEquals(0, ready(session).presentation.worldTimeMinutes)
        assertEquals(5, store.read(RunId("invalid-time.run.1")).size)
    }

    private fun TestScope.session(directory: String, store: EventStore, prefix: String): DefaultGameSession {
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(listOf(loadPackage(directory))),
        ).catalog
        return DefaultGameSession(
            catalog = catalog,
            eventStore = store,
            idSource = SequentialSessionIdSource(prefix),
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    private fun ready(session: DefaultGameSession): GameSessionUiState.Ready =
        assertIs<GameSessionUiState.Ready>(session.state.value)

    private fun loadPackage(directory: String) = WorldPackageSource(
        manifestJson = resource("$directory/manifest.json"),
        files = mapOf(
            "world.json" to resource("$directory/world.json"),
            "playable-world.json" to resource("$directory/playable-world.json"),
            "character-profile.json" to resource("$directory/character-profile.json"),
            "behaviors/activity-starts-quest.json" to resource("$directory/behaviors/activity-starts-quest.json"),
            "behaviors/quest-raises-threat.json" to resource("$directory/behaviors/quest-raises-threat.json"),
            "behaviors/timed-supply.json" to resource("$directory/behaviors/timed-supply.json"),
        ),
    )

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()

    private class RejectOnceAppendStore(private val rejectAppendNumber: Int) : EventStore {
        private val delegate = InMemoryEventStore()
        private var appendCount = 0
        private var rejected = false

        override suspend fun append(runId: RunId, expectedSequence: Long, events: List<EventEnvelope>): EventAppendResult {
            appendCount += 1
            if (!rejected && appendCount == rejectAppendNumber) {
                rejected = true
                return EventAppendResult.Failure(
                    EventStoreError(EventStoreErrorCode.STORAGE_FAILURE, "Injected atomic append failure"),
                )
            }
            return delegate.append(runId, expectedSequence, events)
        }

        override suspend fun read(runId: RunId, afterSequence: Long): List<EventEnvelope> =
            delegate.read(runId, afterSequence)
    }
}
