package io.worldloom.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.agent.runtime.AgentId
import io.worldloom.agent.runtime.AgentIdentity
import io.worldloom.agent.runtime.AgentSessionId
import io.worldloom.agent.runtime.AgentSessionLoadResult
import io.worldloom.agent.runtime.AgentSessionSaveResult
import io.worldloom.agent.runtime.AgentSessionSnapshot
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.provider.api.ProviderMessage
import io.worldloom.provider.api.ProviderMessageRole
import io.worldloom.world.ActorId
import io.worldloom.world.CommandPermission
import io.worldloom.world.RunId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SqlDelightAgentSessionStoreTest {
    @Test
    fun sessionSurvivesStoreRecreationAndEnforcesOwnership() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        database.worldloomQueries.insertRun("run.agent", "contract.agent", 1)
        val identity = identity("npc.one", "actor.one")
        val sessionId = AgentSessionId("session.one")
        val firstStore = SqlDelightAgentSessionStore(database, RunId("run.agent"))
        val empty = assertIs<AgentSessionLoadResult.Success>(firstStore.load(sessionId, identity)).snapshot

        val saved = firstStore.save(
            empty.copy(messages = listOf(ProviderMessage(ProviderMessageRole.USER, "private observation"))),
            expectedRevision = 0,
        )

        assertEquals(1, assertIs<AgentSessionSaveResult.Success>(saved).revision)
        val recreated = SqlDelightAgentSessionStore(WorldloomDatabase(driver), RunId("run.agent"))
        val loaded = assertIs<AgentSessionLoadResult.Success>(recreated.load(sessionId, identity)).snapshot
        assertEquals(1, loaded.revision)
        assertEquals("private observation", loaded.messages.single().content)
        assertIs<AgentSessionLoadResult.OwnershipMismatch>(
            recreated.load(sessionId, identity("npc.two", "actor.two")),
        )
        driver.close()
    }

    @Test
    fun rejectsStaleRevisionWithoutOverwritingPublishedMessages() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        database.worldloomQueries.insertRun("run.conflict", "contract.agent", 1)
        val store = SqlDelightAgentSessionStore(database, RunId("run.conflict"))
        val identity = identity("npc.one", "actor.one")
        val sessionId = AgentSessionId("session.conflict")
        val snapshot = assertIs<AgentSessionLoadResult.Success>(store.load(sessionId, identity)).snapshot
        val published = snapshot.copy(
            messages = listOf(ProviderMessage(ProviderMessageRole.ASSISTANT, "published")),
        )
        assertIs<AgentSessionSaveResult.Success>(store.save(published, 0))

        val conflict = store.save(
            snapshot.copy(messages = listOf(ProviderMessage(ProviderMessageRole.ASSISTANT, "stale"))),
            expectedRevision = 0,
        )

        assertIs<AgentSessionSaveResult.RevisionConflict>(conflict)
        val reloaded = assertIs<AgentSessionLoadResult.Success>(store.load(sessionId, identity)).snapshot
        assertEquals("published", reloaded.messages.single().content)
        driver.close()
    }

    private fun identity(agentId: String, actorId: String) = AgentIdentity(
        agentId = AgentId(agentId),
        actorId = ActorId(actorId),
        permissions = setOf(CommandPermission.ADJUST_NUMERIC_COMPONENT),
    )
}
