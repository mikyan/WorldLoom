package io.worldloom.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.agent.runtime.GameTurn
import io.worldloom.agent.runtime.GameTurnHistoryProblemCode
import io.worldloom.agent.runtime.GameTurnHistoryResult
import io.worldloom.agent.runtime.GameTurnOutputKind
import io.worldloom.agent.runtime.GameTurnStatus
import io.worldloom.agent.runtime.GameTurnStoreResult
import io.worldloom.agent.runtime.CURRENT_GM_TURN_SCHEMA_VERSION
import io.worldloom.agent.runtime.PendingPlayerCheck
import io.worldloom.agent.runtime.TurnId
import io.worldloom.definition.DefinitionId
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.RunId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightGameTurnStoreTest {
    @Test
    fun pendingPlayerCheckSurvivesStoreRecreation() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val runId = RunId("run.pending-check")
        val turnId = TurnId("turn.pending-check.1")
        database.worldloomQueries.insertRun(runId.value, "contract.pending-check", 1)
        val pending = GameTurn(
            runId = runId,
            turnId = turnId,
            input = "检查服务门",
            status = GameTurnStatus.AWAITING_PLAYER,
            revision = 2,
            acceptedSequence = 9,
            deliveredSequence = 9,
            outputKind = GameTurnOutputKind.CHECK_REQUEST,
            pendingCheck = PendingPlayerCheck(
                actionId = DefinitionId("war.action.inspect-service-door"),
                actionLabel = "检查药房服务门",
                profileId = DefinitionId("war.check.survive"),
                profileLabel = "生存判定",
                diceCount = 2,
                diceSides = 6,
            ),
        )

        assertIs<GameTurnStoreResult.Success>(SqlDelightGameTurnStore(database).save(pending, null))
        assertEquals(
            pending,
            SqlDelightGameTurnStore(WorldloomDatabase(driver)).load(runId, turnId),
        )
        driver.close()
    }

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
            outputKind = GameTurnOutputKind.NARRATION,
            evidenceFromSequenceExclusive = 5,
            evidenceThroughSequenceInclusive = 9,
        )
        assertIs<GameTurnStoreResult.Success>(store.save(completed, 0))
        assertIs<GameTurnStoreResult.RevisionConflict>(store.save(accepted.copy(input = "stale"), 0))

        val reloaded = assertNotNull(SqlDelightGameTurnStore(WorldloomDatabase(driver)).load(runId, turnId))
        assertEquals(completed, reloaded)
        driver.close()
    }

    @Test
    fun gmHistoryPagesLegacyAndCorruptRowsWithoutCrossRunLeakage() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val run = RunId("run.history.sql")
        val otherRun = RunId("run.history.other")
        val legacyRun = RunId("run.history.legacy")
        listOf(run, otherRun, legacyRun).forEach {
            database.worldloomQueries.insertRun(it.value, "contract.history", 1)
        }
        val store = SqlDelightGameTurnStore(database)
        repeat(3) { index ->
            val ordinal = index + 1
            assertIs<GameTurnStoreResult.Success>(
                store.save(
                    GameTurn(
                        runId = run,
                        turnId = TurnId("${run.value}.turn.$ordinal"),
                        input = "input-$ordinal",
                        status = GameTurnStatus.COMPLETED,
                        revision = 0,
                        acceptedSequence = ordinal.toLong(),
                        deliveredSequence = ordinal.toLong(),
                        output = "narration-$ordinal",
                        outputKind = GameTurnOutputKind.NARRATION,
                    ),
                    expectedRevision = null,
                ),
            )
        }
        assertIs<GameTurnStoreResult.Success>(
            store.save(
                GameTurn(
                    runId = otherRun,
                    turnId = TurnId("${otherRun.value}.turn.1"),
                    input = "private other run",
                    status = GameTurnStatus.COMPLETED,
                    revision = 0,
                    acceptedSequence = 0,
                ),
                expectedRevision = null,
            ),
        )
        database.worldloomQueries.upsertGmTurn(
            run.value,
            "${run.value}.turn.4",
            4,
            0,
            CURRENT_GM_TURN_SCHEMA_VERSION.toLong(),
            "{not-json}",
        )

        val newest = assertIs<GameTurnHistoryResult.Success>(store.history(run, limit = 2)).page
        assertEquals(listOf(3L, 4L), newest.entries.map { it.ordinal })
        assertNotNull(newest.entries.first().turn)
        assertEquals(GameTurnHistoryProblemCode.INVALID_JSON, newest.entries.last().problem?.code)
        assertTrue(newest.hasEarlier)
        val earlier = assertIs<GameTurnHistoryResult.Success>(
            store.history(run, beforeOrdinalExclusive = 3, limit = 10),
        ).page
        assertEquals(listOf(1L, 2L), earlier.entries.map { it.ordinal })
        assertTrue(earlier.entries.none { it.turn?.input == "private other run" })

        val legacyJson = """{"schemaVersion":1,"runId":"${legacyRun.value}","turnId":"${legacyRun.value}.turn.1","input":"legacy","status":"COMPLETED","revision":0,"acceptedSequence":2,"deliveredSequence":4,"output":"legacy narration","worldChanged":true}"""
        database.worldloomQueries.upsertGmTurn(
            legacyRun.value,
            "${legacyRun.value}.turn.1",
            1,
            0,
            1,
            legacyJson,
        )
        val migrated = assertNotNull(store.load(legacyRun, TurnId("${legacyRun.value}.turn.1")))
        assertEquals(CURRENT_GM_TURN_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(GameTurnOutputKind.NARRATION, migrated.outputKind)
        assertEquals(2, migrated.evidenceFromSequenceExclusive)
        assertEquals(4, migrated.evidenceThroughSequenceInclusive)
        assertNull(migrated.errorCode)
        driver.close()
    }
}
