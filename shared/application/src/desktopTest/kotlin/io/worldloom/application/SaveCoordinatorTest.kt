package io.worldloom.application

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.definition.DefinitionId
import io.worldloom.persistence.SqlDelightCharacterCreationDraftStore
import io.worldloom.persistence.SqlDelightEventStore
import io.worldloom.persistence.SqlDelightRunDirectoryStore
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.RunId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SaveCoordinatorTest {
    @Test
    fun createsListsRenamesArchivesAndContinuesMultipleRuns() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val catalog = catalog()
        val session = DefaultGameSession(
            catalog = catalog,
            eventStore = SqlDelightEventStore(database),
            idSource = SequentialSessionIdSource("save-library"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
            characterDraftStore = SqlDelightCharacterCreationDraftStore(database),
        )
        val store = SqlDelightRunDirectoryStore(database)
        val coordinator = SaveCoordinator(session, store)
        val worldId = DefinitionId("contract.war-survival")

        val first = assertNotNull(assertIs<SaveOperationResult.Success>(coordinator.create(worldId, "第一次撤离")).runId)
        assertIs<ActionResult.Success>(session.confirmCharacter())
        val second = assertNotNull(assertIs<SaveOperationResult.Success>(coordinator.create(worldId, "第二次撤离")).runId)
        assertTrue(first != second)
        assertIs<SaveOperationResult.Success>(coordinator.rename(first, "玛拉路线"))
        assertIs<SaveOperationResult.Success>(coordinator.archive(second))
        val library = assertIs<SaveLibraryState.Ready>(coordinator.state.value)
        assertEquals(2, library.runs.size)
        assertEquals("玛拉路线", library.runs.single { it.runId == first }.displayName)
        assertTrue(library.runs.single { it.runId == second }.archived)
        assertEquals(first, library.quickContinueRunId)

        assertIs<SaveOperationResult.Success>(coordinator.quickContinue())
        assertEquals(first, session.currentRunId)
        assertIs<GameSessionUiState.Ready>(session.state.value)

        assertTrue(store.setWorldContentVersion(first, 2))
        val mismatch = assertIs<SaveOperationResult.Failure>(coordinator.continueRun(first))
        assertEquals(SaveOperationErrorCode.CONTENT_VERSION_MISMATCH, mismatch.error.code)
        driver.close()
    }

    @Test
    fun quickContinueRejectsTheMostRecentCorruptRunWithoutFallingBack() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val session = DefaultGameSession(
            catalog = catalog(),
            eventStore = SqlDelightEventStore(database),
            idSource = SequentialSessionIdSource("quick-continue"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
            characterDraftStore = SqlDelightCharacterCreationDraftStore(database),
        )
        val coordinator = SaveCoordinator(session, SqlDelightRunDirectoryStore(database))
        val worldId = DefinitionId("contract.war-survival")
        val older = assertNotNull(assertIs<SaveOperationResult.Success>(coordinator.create(worldId, "较早存档")).runId)
        val latest = assertNotNull(assertIs<SaveOperationResult.Success>(coordinator.create(worldId, "最近存档")).runId)
        driver.execute(
            identifier = null,
            sql = "UPDATE save_run SET saved_at_epoch_millis = CASE run_id WHEN ? THEN 1000 ELSE 2000 END",
            parameters = 1,
        ) { bindString(0, older.value) }.value
        driver.execute(
            identifier = null,
            sql = "UPDATE event_log SET event_json = '{broken' WHERE run_id = ?",
            parameters = 1,
        ) { bindString(0, latest.value) }.value
        coordinator.refresh()
        assertEquals(latest, assertIs<SaveLibraryState.Ready>(coordinator.state.value).quickContinueRunId)

        val rejected = assertIs<SaveOperationResult.Failure>(coordinator.quickContinue())
        assertEquals(SaveOperationErrorCode.CORRUPT_RUN, rejected.error.code)
        val library = assertIs<SaveLibraryState.Ready>(coordinator.state.value)
        assertEquals(SaveOperationErrorCode.CORRUPT_RUN, library.operationError?.code)
        assertEquals(latest, library.quickContinueRunId)
        driver.close()
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

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()
}
