package io.worldloom.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.agent.runtime.AgentId
import io.worldloom.agent.runtime.AgentMemoryId
import io.worldloom.agent.runtime.AgentMemoryKind
import io.worldloom.agent.runtime.AgentMemoryRecord
import io.worldloom.agent.runtime.AgentSessionId
import io.worldloom.agent.runtime.AgentTurnRecord
import io.worldloom.agent.runtime.CompactionPublication
import io.worldloom.agent.runtime.ContextCheckpoint
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.RunId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlDelightAgentMemoryStoreTest {
    @Test
    fun publishesCheckpointAndTypedPrivateMemoriesAtomicallyAcrossRecreation() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        database.worldloomQueries.insertRun("run.memory", "contract.memory", 1)
        val agent = AgentId("npc.memory")
        val store = SqlDelightAgentMemoryStore(database, RunId("run.memory"))
        repeat(4) { index -> assertTrue(store.appendTurn(turn(agent, index + 1L))) }
        val memories = AgentMemoryKind.entries.mapIndexed { index, kind -> memory(agent, kind, index) }
        val checkpoint = ContextCheckpoint(
            idempotencyKey = "npc.memory:1:4:1",
            agentId = agent,
            fromSequence = 1,
            toSequence = 4,
            promptVersion = 1,
            modelId = "compact-fast",
            summary = "Four private turns",
            sourceEventIds = setOf("event.1", "event.4"),
            publishedAtEpochMillis = 100,
        )

        assertTrue(store.publish(CompactionPublication(checkpoint, memories)))
        assertTrue(store.publish(CompactionPublication(checkpoint, memories)))

        val recreated = SqlDelightAgentMemoryStore(WorldloomDatabase(driver), RunId("run.memory"))
        assertEquals(4, recreated.turns(agent).size)
        assertEquals(AgentMemoryKind.entries.toSet(), recreated.memories(agent).map { it.kind }.toSet())
        assertEquals(setOf("event.memory.0"), recreated.memories(agent).first { it.id.value.endsWith("0") }.sourceEventIds)
        assertEquals(checkpoint, assertNotNull(recreated.latestCheckpoint(agent)))
        driver.close()
    }

    private fun turn(agent: AgentId, sequence: Long) = AgentTurnRecord(
        agentId = agent,
        sessionId = AgentSessionId("session.memory"),
        sequence = sequence,
        input = "input $sequence",
        output = "output $sequence",
        tokenCount = 500,
        sourceEventIds = setOf("event.$sequence"),
    )

    private fun memory(agent: AgentId, kind: AgentMemoryKind, index: Int) = AgentMemoryRecord(
        id = AgentMemoryId("memory.$index"),
        agentId = agent,
        kind = kind,
        content = "memory $index",
        salience = 0.8,
        confidence = 0.9,
        tags = setOf("tag.$index"),
        relatedEntityIds = setOf("entity.$index"),
        sourceEventIds = setOf("event.memory.$index"),
        protected = kind != AgentMemoryKind.EPISODIC,
        createdSequence = 4,
    )
}
