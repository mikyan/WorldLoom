package io.worldloom.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.definition.DefinitionId
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.InitialGameStateFactory
import io.worldloom.world.RunId
import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.WorldDefinitionValidator
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.CURRENT_WORLD_DEFINITION_SCHEMA_VERSION
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SqlDelightRunDirectoryStoreTest {
    @Test
    fun listsRenamesVersionsAndArchivesMultipleIsolatedRuns() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val eventStore = SqlDelightEventStore(database)
        val definition = assertIs<DefinitionValidationResult.Valid>(
            WorldDefinitionValidator.validate(
                WorldDefinition(
                    schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
                    id = DefinitionId("contract.directory"),
                    title = "Directory",
                    components = emptyList(),
                    initialEntities = emptyList(),
                    presentation = emptyList(),
                ),
            ),
        ).definition
        val first = RunId("run.directory.1")
        val second = RunId("run.directory.2")
        assertIs<io.worldloom.world.DurableStoreWriteResult.Success>(
            eventStore.initialize(InitialGameStateFactory.create(definition, first)),
        )
        assertIs<io.worldloom.world.DurableStoreWriteResult.Success>(
            eventStore.initialize(InitialGameStateFactory.create(definition, second)),
        )
        val store = SqlDelightRunDirectoryStore(database)

        assertTrue(store.rename(first, "第一局"))
        assertTrue(store.setWorldContentVersion(first, 3))
        assertTrue(store.setArchived(second, true))
        val runs = store.list()

        assertEquals(setOf(first, second), runs.map { it.runId }.toSet())
        assertEquals("第一局", runs.single { it.runId == first }.displayName)
        assertEquals(3, runs.single { it.runId == first }.worldContentVersion)
        assertTrue(runs.single { it.runId == second }.archived)
        driver.close()
    }
}
