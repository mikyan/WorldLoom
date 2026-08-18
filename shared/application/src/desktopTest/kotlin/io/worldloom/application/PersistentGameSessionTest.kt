package io.worldloom.application

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.definition.DefinitionId
import io.worldloom.persistence.SqlDelightEventStore
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.rules.CheckResolvedEvent
import io.worldloom.rules.DiceRandomRequest
import io.worldloom.rules.RandomRecordId
import io.worldloom.rules.RandomServiceResult
import io.worldloom.rules.SeededRandomService
import io.worldloom.world.RunId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PersistentGameSessionTest {
    @Test
    fun bothContractWorldsPersistAndResumeThroughTheSameSessionPath() = runTest {
        val sources = listOf("war-survival", "station-ai").map { directory ->
            WorldPackageSource(
                manifestJson = resource("$directory/manifest.json"),
                files = mapOf("world.json" to resource("$directory/world.json")),
            )
        }
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(sources),
        ).catalog
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val store = SqlDelightEventStore(WorldloomDatabase(driver))

        catalog.entries.forEachIndexed { index, entry ->
            val prefix = "contract-save-$index"
            val session = DefaultGameSession(
                catalog,
                eventStore = store,
                idSource = SequentialSessionIdSource(prefix),
                workerDispatcher = StandardTestDispatcher(testScheduler),
                snapshotInterval = 1,
            )
            assertIs<LoadResult.Success>(session.load(entry.id))
            val check = assertIs<GameSessionUiState.Ready>(session.state.value).presentation.checks.single()
            assertIs<ActionResult.Success>(session.perform(GameSessionAction.ResolvePresentedCheck(check.presentationId)))

            val resumed = DefaultGameSession(
                catalog,
                eventStore = SqlDelightEventStore(WorldloomDatabase(driver)),
                idSource = SequentialSessionIdSource(prefix),
                workerDispatcher = StandardTestDispatcher(testScheduler),
                snapshotInterval = 1,
            )
            assertIs<LoadResult.Success>(resumed.resume(entry.id, RunId("$prefix.run.1")))
            assertEquals(1, assertIs<GameSessionUiState.Ready>(resumed.state.value).presentation.lastSequence)
        }
        driver.close()
    }

    @Test
    fun closesResumesAndContinuesAnAuditedRandomRun() = runTest {
        val catalog = catalog()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val runId = RunId("persist.run.1")
        val firstStore = SqlDelightEventStore(WorldloomDatabase(driver))
        val firstSession = DefaultGameSession(
            catalog = catalog,
            eventStore = firstStore,
            idSource = SequentialSessionIdSource("persist"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
            randomServiceFactory = { SeededRandomService(123) },
            snapshotInterval = 1,
        )
        val worldId = DefinitionId("contract.war-survival")
        assertIs<LoadResult.Success>(firstSession.load(worldId))
        val firstCheck = assertIs<GameSessionUiState.Ready>(firstSession.state.value).presentation.checks.single()
        assertIs<ActionResult.Success>(
            firstSession.perform(GameSessionAction.ResolvePresentedCheck(firstCheck.presentationId)),
        )

        val recreatedStore = SqlDelightEventStore(WorldloomDatabase(driver))
        val resumedSession = DefaultGameSession(
            catalog = catalog,
            eventStore = recreatedStore,
            idSource = SequentialSessionIdSource("persist"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
            randomServiceFactory = { SeededRandomService(123) },
            snapshotInterval = 1,
        )
        assertIs<LoadResult.Success>(resumedSession.resume(worldId, runId))
        val resumed = assertIs<GameSessionUiState.Ready>(resumedSession.state.value)
        assertEquals(1, resumed.presentation.lastSequence)
        assertIs<ActionResult.Success>(
            resumedSession.perform(
                GameSessionAction.ResolvePresentedCheck(resumed.presentation.checks.single().presentationId),
            ),
        )

        val records = recreatedStore.read(runId).map { event ->
            assertIs<CheckResolvedEvent>(event.payload).record.randomRecord
        }.map { assertNotNull(it) }
        val expectedService = SeededRandomService(123)
        val expected = listOf("first", "second").map { suffix ->
            assertIs<RandomServiceResult.Success>(
                expectedService.resolve(DiceRandomRequest(2, 6), RandomRecordId("expected.$suffix")),
            ).record.results
        }

        assertEquals(2, records.map { it.id }.toSet().size)
        assertEquals(expected, records.map { it.results })
        assertEquals(2, assertIs<GameSessionUiState.Ready>(resumedSession.state.value).presentation.lastSequence)
        driver.close()
    }

    private fun catalog(): StaticWorldCatalog {
        val source = WorldPackageSource(
            manifestJson = resource("war-survival/manifest.json"),
            files = mapOf("world.json" to resource("war-survival/world.json")),
        )
        return assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(listOf(source)),
        ).catalog
    }

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()
}
