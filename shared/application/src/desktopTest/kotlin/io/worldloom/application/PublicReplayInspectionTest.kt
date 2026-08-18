package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.world.EventAppendResult
import io.worldloom.world.EventEnvelope
import io.worldloom.world.EventStore
import io.worldloom.world.InMemoryEventStore
import io.worldloom.world.RunId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PublicReplayInspectionTest {
    @Test
    fun publicReplayIncludesAuditDetailsButNoPrivateAgentMaterial() = runTest {
        val session = session(InMemoryEventStore(), "public-replay")
        assertIs<LoadResult.Success>(session.load(DefinitionId("contract.war-survival")))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        assertIs<ActionResult.Success>(session.perform(GameSessionAction.PerformActivity(DefinitionId("war.activity.search"))))

        val replay = assertIs<PublicReplayResult.Verified>(session.exportVerifiedPublicReplay()).document

        assertEquals(session.currentRunId, replay.runId)
        assertTrue(replay.events.any { it.eventType == "worldloom.event.check.resolved" && it.randomRecord != null })
        assertTrue(replay.events.any { it.causationId.orEmpty().startsWith("behavior.") })
        val publicText = replay.events.joinToString { it.summary }
        assertTrue("北侧道路" !in publicText)
        assertTrue("旧水塔检修盒" !in publicText)
        assertTrue("PRIVATE:" !in publicText)
        assertTrue("api_key" !in publicText.lowercase())
    }

    @Test
    fun longTimelineIsWindowedPagedAndTamperingFailsOfflineVerification() = runTest {
        val store = TamperingEventStore()
        val session = session(store, "long-replay")
        assertIs<LoadResult.Success>(session.load(DefinitionId("contract.war-survival")))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        repeat(205) {
            assertIs<ActionResult.Success>(
                session.perform(GameSessionAction.ResolvePresentedCheck(DefinitionId("war.presentation.survival-check"))),
            )
        }
        val ready = assertIs<GameSessionUiState.Ready>(session.state.value).presentation
        assertEquals(200, ready.timeline.size)
        assertTrue(ready.timelineTruncated)
        val earlier = assertIs<TimelinePageResult.Success>(
            session.timelinePage(ready.timeline.first().sequence, 50),
        ).page
        assertTrue(earlier.events.isNotEmpty())
        assertTrue(earlier.events.last().sequence < ready.timeline.first().sequence)

        store.tamper = true
        assertIs<PublicReplayResult.Failure>(session.exportVerifiedPublicReplay())
    }

    private fun kotlinx.coroutines.test.TestScope.session(store: EventStore, prefix: String): DefaultGameSession {
        val source = WorldPackageSource(
            manifestJson = resource("war-survival/manifest.json"),
            files = mapOf(
                "world.json" to resource("war-survival/world.json"),
                "playable-world.json" to resource("war-survival/playable-world.json"),
                "character-profile.json" to resource("war-survival/character-profile.json"),
                "behaviors/activity-starts-quest.json" to resource("war-survival/behaviors/activity-starts-quest.json"),
                "behaviors/quest-raises-threat.json" to resource("war-survival/behaviors/quest-raises-threat.json"),
                "behaviors/timed-supply.json" to resource("war-survival/behaviors/timed-supply.json"),
            ),
        )
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(listOf(source)),
        ).catalog
        return DefaultGameSession(
            catalog = catalog,
            eventStore = store,
            idSource = SequentialSessionIdSource(prefix),
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()

    private class TamperingEventStore : EventStore {
        private val delegate = InMemoryEventStore()
        var tamper: Boolean = false

        override suspend fun append(
            runId: RunId,
            expectedSequence: Long,
            events: List<EventEnvelope>,
        ): EventAppendResult = delegate.append(runId, expectedSequence, events)

        override suspend fun read(runId: RunId, afterSequence: Long): List<EventEnvelope> {
            val events = delegate.read(runId, afterSequence)
            return if (tamper && events.size > 1) {
                events.toMutableList().also { it[1] = it[1].copy(sequence = it[1].sequence + 1) }
            } else {
                events
            }
        }
    }
}
