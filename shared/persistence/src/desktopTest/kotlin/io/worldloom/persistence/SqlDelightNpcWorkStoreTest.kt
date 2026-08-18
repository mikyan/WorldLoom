package io.worldloom.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.agent.runtime.NpcWorkCreateResult
import io.worldloom.agent.runtime.NpcWorkId
import io.worldloom.agent.runtime.NpcWorkItem
import io.worldloom.agent.runtime.NpcWorkStatus
import io.worldloom.agent.runtime.NpcWorkUpdateResult
import io.worldloom.definition.DefinitionId
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.RunId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SqlDelightNpcWorkStoreTest {
    @Test
    fun persistsStableWorkAndRejectsStaleRevision() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        database.worldloomQueries.insertRun("run.npc", "contract.npc", 1)
        val store = SqlDelightNpcWorkStore(database)
        val pending = NpcWorkItem(
            id = NpcWorkId("npc:event.1:test.npc"),
            runId = RunId("run.npc"),
            npcId = DefinitionId("test.npc"),
            sourceEventId = "event.1",
            sourceSequence = 1,
            eventType = DefinitionId("worldloom.event.activity-completed"),
            sceneId = DefinitionId("test.scene"),
        )

        assertIs<NpcWorkCreateResult.Created>(store.create(pending))
        assertIs<NpcWorkCreateResult.Exists>(store.create(pending))
        val completed = pending.copy(
            status = NpcWorkStatus.COMPLETED,
            revision = 1,
            acceptedSequence = 1,
            deliveredSequence = 2,
            publicEventIds = listOf("event.2"),
        )
        assertIs<NpcWorkUpdateResult.Updated>(store.update(0, completed))
        assertIs<NpcWorkUpdateResult.RevisionConflict>(store.update(0, completed))

        assertEquals(listOf(completed), SqlDelightNpcWorkStore(WorldloomDatabase(driver)).list(RunId("run.npc")))
        driver.close()
    }
}
