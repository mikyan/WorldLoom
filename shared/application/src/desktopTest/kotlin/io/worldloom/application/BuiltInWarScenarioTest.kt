package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.rules.DiceRandomRequest
import io.worldloom.rules.InventoryOperation
import io.worldloom.rules.RANDOM_ALGORITHM_VERSION
import io.worldloom.rules.RandomRecord
import io.worldloom.rules.RandomRecordId
import io.worldloom.rules.RandomRequest
import io.worldloom.rules.RandomService
import io.worldloom.rules.RandomServiceError
import io.worldloom.rules.RandomServiceErrorCode
import io.worldloom.rules.RandomServiceResult
import io.worldloom.world.ActorId
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandPermission
import io.worldloom.world.InMemoryEventStore
import io.worldloom.world.RunId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuiltInWarScenarioTest {
    @Test
    fun inspectingThePharmacyExitCommitsAndReplaysTheDrainageRoute() = runTest {
        val catalog = catalog()
        val session = DefaultGameSession(
            catalog = catalog,
            idSource = SequentialSessionIdSource("war-pharmacy-map"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
            randomServiceFactory = { ScriptedRandomService(listOf(6, 6, 6, 6)) },
        )
        assertIs<LoadResult.Success>(session.load(catalog.entries.single().id))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        assertIs<ActionResult.Success>(
            session.perform(GameSessionAction.PerformAvailableAction(DefinitionId("war.action.search-supplies"))),
        )
        val pharmacy = presentation(session)
        assertEquals(DefinitionId("war.scene.pharmacy"), pharmacy.scene?.id)
        assertTrue(pharmacy.exploration.nodes.none { it.id == DefinitionId("war.place.drainage") })

        assertIs<ActionResult.Success>(
            session.perform(GameSessionAction.PerformAvailableAction(DefinitionId("war.action.inspect-service-door"))),
        )
        val inspected = presentation(session)
        assertTrue(inspected.exploration.nodes.any { it.id == DefinitionId("war.place.drainage") })
        assertTrue(inspected.exploration.connections.any { it.id == DefinitionId("war.path.pharmacy-drainage") })
        assertIs<SessionReplayResult.Success>(session.replay())
        assertEquals(inspected.exploration, presentation(session).exploration)
    }

    @Test
    fun failedStreetCrossingUsesSpoilerSafeUnderFireGuidanceAndReachesDrainage() = runTest {
        val catalog = catalog()
        val session = DefaultGameSession(
            catalog = catalog,
            idSource = SequentialSessionIdSource("war-under-fire"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
            randomServiceFactory = { ScriptedRandomService(listOf(1, 1, 6, 6)) },
        )
        assertIs<LoadResult.Success>(session.load(catalog.entries.single().id))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        assertIs<ActionResult.Success>(
            session.perform(GameSessionAction.PerformAvailableAction(DefinitionId("war.action.search-supplies"))),
        )

        val underFire = presentation(session)
        assertEquals(DefinitionId("war.scene.under-fire"), underFire.scene?.id)
        assertTrue(underFire.guidance.suggestions.size >= 3)
        val publicCopy = buildList {
            underFire.exploration.situation?.let { addAll(it.sensoryDetails + it.objective + it.pressure + it.question) }
            addAll(underFire.exploration.nodes.flatMap { listOf(it.label, it.description) })
            addAll(underFire.exploration.affordances.flatMap { listOf(it.label, it.description) })
            addAll(underFire.guidance.suggestions.flatMap { listOf(it.label, it.inputDraft, it.rationale.orEmpty(), it.tradeoff.orEmpty()) })
        }.joinToString("\n")
        assertTrue(!publicCopy.contains("急救箱"))
        assertTrue(!publicCopy.contains("涵洞"))
        assertTrue(!publicCopy.contains("检查站"))
        assertTrue(!publicCopy.contains("ending"))
        assertTrue(!publicCopy.contains("war."))

        assertIs<ActionResult.Success>(
            session.perform(GameSessionAction.PerformAvailableAction(DefinitionId("war.action.escape-patrol"))),
        )
        assertEquals(DefinitionId("war.scene.drainage"), presentation(session).scene?.id)
    }

    @Test
    fun allAuthoredRoutesReachTheirEndingAndReplayExactly() = runTest {
        val catalog = catalog()
        val entry = catalog.entries.single()
        val loaded = assertNotNull(catalog.load(entry.id))
        val contract = assertNotNull(loaded.playableContract)

        contract.source.goldenRoutes.forEachIndexed { index, route ->
            val eventStore = InMemoryEventStore()
            val randomValues = route.steps.flatMap { it.randomValues }
            val session = DefaultGameSession(
                catalog = catalog,
                eventStore = eventStore,
                idSource = SequentialSessionIdSource("war-route-$index"),
                workerDispatcher = StandardTestDispatcher(testScheduler),
                randomServiceFactory = { ScriptedRandomService(randomValues) },
            )
            assertIs<LoadResult.Success>(session.load(entry.id))
            assertIs<ActionResult.Success>(session.confirmCharacter())
            val opening = presentation(session)
            assertEquals("war.scene.ruins", opening.scene?.id?.value)
            assertTrue(!opening.scene?.description.isNullOrBlank())
            assertEquals("第一幕 · 炮火后的清晨", opening.opening?.firstActLabel)
            assertEquals("worldloom.background.war-ruins", opening.opening?.backgroundAssetId)
            assertEquals(listOf("玛拉", "托马斯"), opening.opening?.npcs?.map(PresentedNpc::displayName))
            assertEquals(
                listOf("worldloom.avatar.war-mara", "worldloom.avatar.war-tomas"),
                opening.opening?.npcs?.map(PresentedNpc::avatarAssetId),
            )

            route.steps.forEach { step ->
                assertIs<ActionResult.Success>(
                    session.perform(GameSessionAction.PerformAvailableAction(step.actionId)),
                    "Route ${route.id} stopped at ${step.actionId}",
                )
            }

            val completed = presentation(session)
            assertEquals(route.expectedEndingId, completed.endingId)
            assertTrue(!completed.endingSummary.isNullOrBlank())
            assertTrue(completed.timeline.any { it.summary == "游戏阶段：COMPLETED" })
            val beforeReplay = completed
            assertIs<SessionReplayResult.Success>(session.replay())
            assertEquals(beforeReplay, presentation(session))

            val runId = RunId("war-route-$index.run.1")
            val eventCount = eventStore.read(runId).size
            val rejected = assertIs<ActionResult.Failure>(
                session.perform(GameSessionAction.PerformAvailableAction(DefinitionId("war.action.search-supplies"))),
            )
            assertEquals(SessionErrorCode.COMMAND_REJECTED, rejected.error.code)
            assertEquals(eventCount, eventStore.read(runId).size)
        }
    }

    @Test
    fun builtInInventoryRejectsUsingMoreSuppliesThanExistWithoutAppendingFacts() = runTest {
        val catalog = catalog()
        val store = InMemoryEventStore()
        val session = DefaultGameSession(
            catalog = catalog,
            eventStore = store,
            idSource = SequentialSessionIdSource("war-resource"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
        assertIs<LoadResult.Success>(session.load(catalog.entries.single().id))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        val runId = RunId("war-resource.run.1")
        val before = store.read(runId).size

        val result = assertIs<ActionResult.Failure>(
            session.execute(
                GameSessionCommand.ChangeInventory(
                    DefinitionId("war.item.bread"),
                    quantity = 99,
                    operation = InventoryOperation.USE,
                ),
                CommandAuthorization(
                    ActorId("gm.war-test"),
                    setOf(CommandPermission.MANAGE_INVENTORY),
                ),
            ),
        )

        assertEquals(SessionErrorCode.COMMAND_REJECTED, result.error.code)
        assertEquals(before, store.read(runId).size)
        assertEquals(
            2,
            assertNotNull(presentation(session).adventureState)
                .inventory.single { it.id.value == "war.item.bread" }.quantity,
        )
    }

    private fun catalog(): StaticWorldCatalog {
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
        return assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(listOf(source)),
        ).catalog
    }

    private fun presentation(session: DefaultGameSession): GamePresentation = when (val state = session.state.value) {
        is GameSessionUiState.Ready -> state.presentation
        is GameSessionUiState.Ended -> state.presentation
        else -> error("Expected a presented game state, got $state")
    }

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()

    private class ScriptedRandomService(values: List<Int>) : RandomService {
        private val remaining = ArrayDeque(values)

        override fun resolve(request: RandomRequest, recordId: RandomRecordId): RandomServiceResult {
            val dice = request as? DiceRandomRequest ?: return RandomServiceResult.Failure(
                RandomServiceError(RandomServiceErrorCode.INVALID_REQUEST, "Only dice requests are supported"),
            )
            if (remaining.size < dice.count) return RandomServiceResult.Failure(
                RandomServiceError(RandomServiceErrorCode.INVALID_REQUEST, "Scripted route ran out of random values"),
            )
            val results = List(dice.count) { remaining.removeFirst() }
            if (results.any { it !in 1..dice.sides }) return RandomServiceResult.Failure(
                RandomServiceError(RandomServiceErrorCode.INVALID_REQUEST, "Scripted die is outside request bounds"),
            )
            return RandomServiceResult.Success(
                RandomRecord(recordId, RANDOM_ALGORITHM_VERSION, request, results),
            )
        }
    }
}
