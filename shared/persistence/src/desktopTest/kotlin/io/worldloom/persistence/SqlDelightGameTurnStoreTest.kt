package io.worldloom.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.agent.runtime.GameTurn
import io.worldloom.agent.runtime.GameTurnStatus
import io.worldloom.agent.runtime.GameTurnStoreResult
import io.worldloom.agent.runtime.TurnId
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.RunId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SqlDelightGameTurnStoreTest {
    @Test
    fun gmTurnSurvivesRecreationAndRejectsStaleRevision() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val runId = RunId("run.gm")
        val turnId = TurnId("turn.gm.1")
        database.worldloomQueries.insertRun(runId.value, "contract.gm", 1)
        val store = SqlDelightGameTurnStore(database)
        assertEquals("run.gm.turn.1", store.nextTurnId(runId).value)
        val accepted = GameTurn(
            runId = runId,
            turnId = turnId,
            input = "observe",
            status = GameTurnStatus.ACCEPTED,
            revision = 0,
            acceptedSequence = 5,
        )

        assertIs<GameTurnStoreResult.Success>(store.save(accepted, null))
        assertEquals("run.gm.turn.2", store.nextTurnId(runId).value)
        val completed = accepted.copy(
            status = GameTurnStatus.COMPLETED,
            revision = 1,
            deliveredSequence = 9,
            output = "done",
            worldChanged = true,
        )
        assertIs<GameTurnStoreResult.Success>(store.save(completed, 0))
        assertIs<GameTurnStoreResult.RevisionConflict>(store.save(accepted.copy(input = "stale"), 0))

        val reloaded = assertNotNull(SqlDelightGameTurnStore(WorldloomDatabase(driver)).load(runId, turnId))
        assertEquals(completed, reloaded)
        driver.close()
    }
}
