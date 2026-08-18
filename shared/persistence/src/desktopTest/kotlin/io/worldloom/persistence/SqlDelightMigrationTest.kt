package io.worldloom.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.persistence.db.WorldloomDatabase
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SqlDelightMigrationTest {
    @Test
    fun migratesVersionOneDatabaseWithoutLosingExistingRun() {
        val baseline = assertNotNull(javaClass.classLoader.getResource("1.db"))
        val migratedFile = Files.createTempFile("worldloom-migration-", ".db")
        Files.copy(baseline.openStream(), migratedFile, StandardCopyOption.REPLACE_EXISTING)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${migratedFile.absolutePathString()}")
        try {
            driver.execute(
                identifier = null,
                sql = "INSERT INTO save_run(run_id, world_definition_id) VALUES ('run.legacy', 'contract.legacy')",
                parameters = 0,
            ).value

            WorldloomDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 7).value
            val database = WorldloomDatabase(driver)
            val run = assertNotNull(database.worldloomQueries.selectRun("run.legacy").executeAsOneOrNull())

            assertEquals("contract.legacy", run.world_definition_id)
            assertEquals(1, run.data_schema_version)
            assertNull(database.worldloomQueries.selectSnapshot("run.legacy").executeAsOneOrNull())
            assertNull(database.worldloomQueries.selectAgentSession("run.legacy", "session.legacy").executeAsOneOrNull())
            assertNull(database.worldloomQueries.selectCharacterCreationDraft("run.legacy").executeAsOneOrNull())
            assertNull(database.worldloomQueries.selectGmTurn("run.legacy", "turn.legacy").executeAsOneOrNull())
            assertEquals(emptyList(), database.worldloomQueries.selectBehaviorWorks("run.legacy").executeAsList())
            assertEquals(emptyList(), database.worldloomQueries.selectNpcWorks("run.legacy").executeAsList())
        } finally {
            driver.close()
            Files.deleteIfExists(migratedFile)
        }
    }
}
