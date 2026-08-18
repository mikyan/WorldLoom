package io.worldloom.application

import io.worldloom.definition.DefinitionId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.worldloom.content.schema.CharacterCreationMode
import io.worldloom.world.EventAppendResult
import io.worldloom.world.EventEnvelope
import io.worldloom.world.EventStore
import io.worldloom.world.EventStoreError
import io.worldloom.world.EventStoreErrorCode
import io.worldloom.world.InMemoryEventStore
import io.worldloom.world.RunId

class ContractGameSessionTest {
    @Test
    fun playableActionCommitsCheckAndSceneProgressionAsOneReplayableBatch() = runTest {
        val source = WorldPackageSource(
            resource("war-survival/manifest.json"),
            mapOf(
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
        val store = InMemoryEventStore()
        val session = DefaultGameSession(
            catalog,
            store,
            SequentialSessionIdSource("playable-action"),
            StandardTestDispatcher(testScheduler),
        )

        assertIs<LoadResult.Success>(session.load(catalog.entries.single().id))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        val before = assertIs<GameSessionUiState.Ready>(session.state.value)
        assertEquals("war.scene.ruins", before.presentation.scene?.id?.value)

        assertIs<ActionResult.Success>(
            session.perform(GameSessionAction.PerformAvailableAction(DefinitionId("war.action.search-supplies"))),
        )

        val progressed = assertIs<GameSessionUiState.Ready>(session.state.value)
        assertEquals(9, progressed.presentation.lastSequence)
        assertTrue(progressed.presentation.scene?.id?.value in setOf("war.scene.pharmacy", "war.scene.under-fire"))
        assertTrue(progressed.presentation.completedObjectiveIds.isNotEmpty())
        assertTrue(progressed.presentation.timeline.any { it.summary.startsWith("行动已结算") })
        assertTrue(progressed.presentation.timeline.any { it.summary.startsWith("进入场景") })
        val authoritativeEvents = store.read(RunId("playable-action.run.1"))
        assertEquals((1L..9L).toList(), authoritativeEvents.map(EventEnvelope::sequence))

        assertIs<SessionReplayResult.Success>(session.replay())
        assertEquals(progressed.presentation, assertIs<GameSessionUiState.Ready>(session.state.value).presentation)
        val rejected = assertIs<ActionResult.Failure>(
            session.perform(GameSessionAction.PerformAvailableAction(DefinitionId("war.action.search-supplies"))),
        )
        assertEquals(SessionErrorCode.COMMAND_REJECTED, rejected.error.code)
        assertEquals(9, store.read(RunId("playable-action.run.1")).size)
    }

    @Test
    fun rejectedConfirmationKeepsTheDraftAndPregameFacts() = runTest {
        val source = WorldPackageSource(
            resource("war-survival/manifest.json"),
            mapOf(
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
        val store = RejectSecondAppendStore()
        val session = DefaultGameSession(
            catalog,
            store,
            SequentialSessionIdSource("reject-character"),
            StandardTestDispatcher(testScheduler),
        )

        assertIs<LoadResult.Success>(session.load(catalog.entries.single().id))
        val failure = assertIs<ActionResult.Failure>(session.confirmCharacter())

        assertEquals(SessionErrorCode.EVENT_STORE_REJECTED, failure.error.code)
        assertIs<GameSessionUiState.CharacterCreation>(session.state.value)
        assertEquals(1, store.read(RunId("reject-character.run.1")).size)
    }

    @Test
    fun bothContractWorldsExposeAndReplayTheirConfiguredCheck() = runTest {
        val sources = listOf("war-survival", "station-ai").map { directory ->
            WorldPackageSource(
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
        }
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(sources),
        ).catalog
        assertEquals("contract.war-survival", catalog.entries.first().id.value)
        assertEquals(100, catalog.entries.first().priority)

        catalog.entries.forEach { entry ->
            val session = DefaultGameSession(
                catalog = catalog,
                idSource = SequentialSessionIdSource(entry.id.value),
                workerDispatcher = StandardTestDispatcher(testScheduler),
            )
            assertIs<LoadResult.Success>(session.load(entry.id))
            val creation = assertIs<GameSessionUiState.CharacterCreation>(session.state.value)
            val expectedMode = if (entry.id.value.contains("war-survival")) {
                CharacterCreationMode.FIXED
            } else {
                CharacterCreationMode.POINT_BUY
            }
            assertEquals(expectedMode, creation.presentation.selectedMode)
            assertIs<ActionResult.Success>(session.confirmCharacter())
            val loaded = assertIs<GameSessionUiState.Ready>(session.state.value)
            val check = loaded.presentation.checks.single()

            assertIs<ActionResult.Success>(
                session.perform(GameSessionAction.ResolvePresentedCheck(check.presentationId)),
            )
            val resolved = assertIs<GameSessionUiState.Ready>(session.state.value)

            assertEquals(6, resolved.presentation.lastSequence)
            assertTrue(resolved.presentation.timeline.any { it.summary.contains("检定") })
            assertIs<SessionReplayResult.Success>(session.replay())
            assertEquals(resolved.presentation, assertIs<GameSessionUiState.Ready>(session.state.value).presentation)
        }
    }

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()

    private class RejectSecondAppendStore : EventStore {
        private val delegate = InMemoryEventStore()
        private var appendCount = 0

        override suspend fun append(
            runId: RunId,
            expectedSequence: Long,
            events: List<EventEnvelope>,
        ): EventAppendResult {
            appendCount += 1
            return if (appendCount == 2) {
                EventAppendResult.Failure(
                    EventStoreError(EventStoreErrorCode.SEQUENCE_CONFLICT, "Injected confirmation failure"),
                )
            } else {
                delegate.append(runId, expectedSequence, events)
            }
        }

        override suspend fun read(runId: RunId, afterSequence: Long): List<EventEnvelope> =
            delegate.read(runId, afterSequence)
    }
}
