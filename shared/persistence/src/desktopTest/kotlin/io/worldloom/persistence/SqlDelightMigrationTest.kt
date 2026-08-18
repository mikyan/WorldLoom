package io.worldloom.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.agent.runtime.CURRENT_GM_TURN_SCHEMA_VERSION
import io.worldloom.agent.runtime.GameTurnHistoryResult
import io.worldloom.agent.runtime.GameTurnOutputKind
import io.worldloom.agent.runtime.TurnId
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.RunId
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs

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

            WorldloomDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 11).value
            val database = WorldloomDatabase(driver)
            val run = assertNotNull(database.worldloomQueries.selectRun("run.legacy").executeAsOneOrNull())

            assertEquals("contract.legacy", run.world_definition_id)
            assertEquals(1, run.data_schema_version)
            assertEquals(1, run.world_content_version)
            assertEquals("", run.display_name)
            assertEquals(0, run.archived)
            assertEquals(0, run.last_persisted_event_sequence)
            assertNull(run.last_persisted_turn_id)
            assertEquals("SAVED", run.save_status)
            assertEquals(0, run.saved_at_epoch_millis)
            assertNull(database.worldloomQueries.selectSnapshot("run.legacy").executeAsOneOrNull())
            assertNull(database.worldloomQueries.selectAgentSession("run.legacy", "session.legacy").executeAsOneOrNull())
            assertNull(database.worldloomQueries.selectCharacterCreationDraft("run.legacy").executeAsOneOrNull())
            assertNull(database.worldloomQueries.selectGmTurn("run.legacy", "turn.legacy").executeAsOneOrNull())
            assertEquals(
                emptyList(),
                database.worldloomQueries.selectGmTurnPage("run.legacy", Long.MAX_VALUE, 10).executeAsList(),
            )
            assertEquals(emptyList(), database.worldloomQueries.selectBehaviorWorks("run.legacy").executeAsList())
            assertEquals(emptyList(), database.worldloomQueries.selectNpcWorks("run.legacy").executeAsList())
            assertEquals(emptyList(), database.worldloomQueries.selectRecognitionJobs().executeAsList())
        } finally {
            driver.close()
            Files.deleteIfExists(migratedFile)
        }
    }

    @Test
    fun migrationEightAssignsStableOrdinalsAndReadsLegacyGmTurns() = runTest {
        val baseline = assertNotNull(javaClass.classLoader.getResource("1.db"))
        val migratedFile = Files.createTempFile("worldloom-gm-history-migration-", ".db")
        Files.copy(baseline.openStream(), migratedFile, StandardCopyOption.REPLACE_EXISTING)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${migratedFile.absolutePathString()}")
        try {
            WorldloomDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 8).value
            driver.execute(
                identifier = null,
                sql = "INSERT INTO save_run(run_id, world_definition_id) VALUES ('run.gm.legacy', 'contract.legacy')",
                parameters = 0,
            ).value
            val legacyOne = """{"schemaVersion":1,"runId":"run.gm.legacy","turnId":"run.gm.legacy.turn.1","input":"first","status":"COMPLETED","revision":0,"acceptedSequence":0,"deliveredSequence":1,"output":"one","worldChanged":true}"""
            val legacyTwo = """{"schemaVersion":1,"runId":"run.gm.legacy","turnId":"run.gm.legacy.turn.2","input":"second","status":"COMPLETED","revision":0,"acceptedSequence":1,"deliveredSequence":2,"output":"two","worldChanged":true}"""
            listOf(legacyOne, legacyTwo).forEachIndexed { index, json ->
                driver.execute(
                    identifier = null,
                    sql = "INSERT INTO gm_turn(run_id, turn_id, revision, turn_schema_version, turn_json) VALUES (?, ?, 0, 1, ?)",
                    parameters = 3,
                ) {
                    bindString(0, "run.gm.legacy")
                    bindString(1, "run.gm.legacy.turn.${index + 1}")
                    bindString(2, json)
                }.value
            }

            WorldloomDatabase.Schema.migrate(driver, oldVersion = 8, newVersion = 9).value
            val store = SqlDelightGameTurnStore(WorldloomDatabase(driver))
            val page = assertIs<GameTurnHistoryResult.Success>(
                store.history(RunId("run.gm.legacy")),
            ).page

            assertEquals(listOf(1L, 2L), page.entries.map { it.ordinal })
            assertEquals(CURRENT_GM_TURN_SCHEMA_VERSION, page.entries.last().turn?.schemaVersion)
            assertEquals(GameTurnOutputKind.NARRATION, page.entries.last().turn?.outputKind)
            assertEquals("second", store.load(RunId("run.gm.legacy"), TurnId("run.gm.legacy.turn.2"))?.input)
        } finally {
            driver.close()
            Files.deleteIfExists(migratedFile)
        }
    }

    @Test
    fun migrationNineBackfillsRunPersistenceEvidenceFromDurableRows() {
        val baseline = assertNotNull(javaClass.classLoader.getResource("1.db"))
        val migratedFile = Files.createTempFile("worldloom-run-evidence-migration-", ".db")
        Files.copy(baseline.openStream(), migratedFile, StandardCopyOption.REPLACE_EXISTING)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${migratedFile.absolutePathString()}")
        try {
            WorldloomDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 9).value
            driver.execute(
                null,
                "INSERT INTO save_run(run_id, world_definition_id) VALUES ('run.evidence.legacy', 'contract.legacy')",
                0,
            ).value
            driver.execute(
                null,
                "INSERT INTO event_log(run_id, sequence, event_id, event_json) VALUES ('run.evidence.legacy', 3, 'event.legacy.3', '{}')",
                0,
            ).value
            driver.execute(
                null,
                "INSERT INTO gm_turn(run_id, turn_id, revision, turn_schema_version, turn_json, turn_ordinal) VALUES ('run.evidence.legacy', 'turn.legacy.2', 0, 1, '{}', 2)",
                0,
            ).value

            WorldloomDatabase.Schema.migrate(driver, oldVersion = 9, newVersion = 10).value
            val run = assertNotNull(
                WorldloomDatabase(driver).worldloomQueries.selectRun("run.evidence.legacy").executeAsOneOrNull(),
            )
            assertEquals(3, run.last_persisted_event_sequence)
            assertEquals("turn.legacy.2", run.last_persisted_turn_id)
            assertEquals("SAVED", run.save_status)
        } finally {
            driver.close()
            Files.deleteIfExists(migratedFile)
        }
    }
}
