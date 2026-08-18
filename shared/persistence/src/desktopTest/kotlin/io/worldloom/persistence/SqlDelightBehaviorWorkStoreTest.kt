package io.worldloom.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.behavior.runtime.BehaviorWorkCreateResult
import io.worldloom.behavior.runtime.BehaviorWorkId
import io.worldloom.behavior.runtime.BehaviorWorkItem
import io.worldloom.behavior.runtime.BehaviorWorkStatus
import io.worldloom.behavior.runtime.BehaviorWorkUpdateResult
import io.worldloom.definition.DefinitionId
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.EventId
import io.worldloom.world.RunId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SqlDelightBehaviorWorkStoreTest {
    @Test
    fun persistsIdempotentWorkAndRejectsStaleRevision() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        database.worldloomQueries.insertRun("run.behavior", "contract.behavior", 1)
        val store = SqlDelightBehaviorWorkStore(database)
        val pending = BehaviorWorkItem(
            id = BehaviorWorkId("event.1:test.behavior:0"),
            runId = RunId("run.behavior"),
            rootEventId = EventId("event.1"),
            parentEventId = EventId("event.1"),
            parentSequence = 1,
            parentEventType = DefinitionId("worldloom.event.activity-completed"),
            behaviorId = DefinitionId("test.behavior"),
            priority = 10,
            causalDepth = 0,
            triggerOrdinal = 0,
            signature = "test.behavior|worldloom.event.activity-completed",
        )

        assertIs<BehaviorWorkCreateResult.Created>(store.create(pending))
        assertIs<BehaviorWorkCreateResult.Existing>(store.create(pending))
        val completed = assertIs<BehaviorWorkUpdateResult.Updated>(
            store.update(
                0,
                pending.copy(
                    status = BehaviorWorkStatus.COMPLETED,
                    derivedCommandCount = 1,
                    committedThroughSequence = 2,
                ),
            ),
        ).item
        assertEquals(1, completed.revision)
        assertIs<BehaviorWorkUpdateResult.Conflict>(store.update(0, pending))

        val recreated = SqlDelightBehaviorWorkStore(WorldloomDatabase(driver))
        assertEquals(listOf(completed), recreated.list(RunId("run.behavior")))
        driver.close()
    }
}
