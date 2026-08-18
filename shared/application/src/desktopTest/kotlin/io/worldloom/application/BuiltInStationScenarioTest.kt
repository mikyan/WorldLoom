package io.worldloom.application

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.definition.DefinitionId
import io.worldloom.persistence.SqlDelightCharacterCreationDraftStore
import io.worldloom.persistence.SqlDelightEventStore
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.ActorId
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandPermission
import io.worldloom.world.RunId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuiltInStationScenarioTest {
    @Test
    fun allAuthoredRoutesReachTheirEndingPersistAndReplayPublicFacts() = runTest {
        val catalog = catalog()
        val entry = catalog.entries.single()
        val contract = assertNotNull(catalog.load(entry.id)?.playableContract)
        val authorization = CommandAuthorization(
            actorId = ActorId("gm.station-route"),
            permissions = setOf(CommandPermission.APPLY_ACTION_OUTCOME, CommandPermission.RESOLVE_CHECK),
        )

        contract.source.goldenRoutes.forEachIndexed { index, route ->
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                WorldloomDatabase.Schema.create(driver).value
                val prefix = "station-route-$index"
                val first = session(catalog, driver, prefix)
                assertIs<LoadResult.Success>(first.load(entry.id))
                assertIs<ActionResult.Success>(first.confirmCharacter())

                route.steps.forEach { step ->
                    assertIs<ActionResult.Success>(
                        first.execute(
                            GameSessionCommand.PerformAvailableAction(step.actionId, step.selectedOutcomeId),
                            authorization,
                        ),
                        "Route ${route.id} stopped at ${step.actionId}",
                    )
                }

                val completed = assertIs<GameSessionUiState.Ended>(first.state.value).presentation
                assertEquals(route.expectedEndingId, completed.endingId)
                assertTrue(!completed.endingSummary.isNullOrBlank())
                val replay = assertIs<PublicReplayResult.Verified>(first.exportVerifiedPublicReplay()).document
                assertEquals(completed.lastSequence, replay.events.last().sequence)
                assertTrue(
                    replay.events.any {
                        it.eventType == "worldloom.event.action.outcome-applied" &&
                            route.steps.last().actionId.value in it.summary
                    },
                    replay.events.joinToString { "${it.eventType}:${it.summary}" },
                )
                assertTrue(replay.events.any { it.summary == "游戏阶段：COMPLETED" })

                val resumed = session(catalog, driver, prefix)
                assertIs<LoadResult.Success>(resumed.resume(entry.id, RunId("$prefix.run.1")))
                val restored = assertIs<GameSessionUiState.Ended>(resumed.state.value).presentation
                assertEquals(completed, restored)
                assertIs<SessionReplayResult.Success>(resumed.replay())
                assertEquals(restored, assertIs<GameSessionUiState.Ended>(resumed.state.value).presentation)
            } finally {
                driver.close()
            }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.session(
        catalog: StaticWorldCatalog,
        driver: JdbcSqliteDriver,
        prefix: String,
    ) = DefaultGameSession(
        catalog = catalog,
        eventStore = SqlDelightEventStore(WorldloomDatabase(driver)),
        idSource = SequentialSessionIdSource(prefix),
        workerDispatcher = StandardTestDispatcher(testScheduler),
        snapshotInterval = 1,
        characterDraftStore = SqlDelightCharacterCreationDraftStore(WorldloomDatabase(driver)),
    )

    private fun catalog(): StaticWorldCatalog {
        val source = WorldPackageSource(
            manifestJson = resource("station-ai/manifest.json"),
            files = mapOf(
                "world.json" to resource("station-ai/world.json"),
                "playable-world.json" to resource("station-ai/playable-world.json"),
                "character-profile.json" to resource("station-ai/character-profile.json"),
                "behaviors/activity-starts-quest.json" to resource("station-ai/behaviors/activity-starts-quest.json"),
                "behaviors/quest-raises-threat.json" to resource("station-ai/behaviors/quest-raises-threat.json"),
                "behaviors/timed-supply.json" to resource("station-ai/behaviors/timed-supply.json"),
            ),
        )
        return assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(listOf(source)),
        ).catalog
    }

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()
}
