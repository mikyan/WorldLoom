package io.worldloom.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.agent.runtime.GameTurn
import io.worldloom.agent.runtime.GameTurnOutputKind
import io.worldloom.agent.runtime.GameTurnStatus
import io.worldloom.agent.runtime.GameTurnStoreResult
import io.worldloom.agent.runtime.TurnId
import io.worldloom.application.RunSaveStatus
import io.worldloom.definition.DefinitionId
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.InitialGameStateFactory
import io.worldloom.world.RunId
import io.worldloom.world.CommandId
import io.worldloom.world.CURRENT_EVENT_SCHEMA_VERSION
import io.worldloom.world.EventAppendResult
import io.worldloom.world.EventEnvelope
import io.worldloom.world.EventId
import io.worldloom.world.RunLifecycle
import io.worldloom.world.RunLifecycleChangedEvent
import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.WorldDefinitionValidator
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.CURRENT_WORLD_DEFINITION_SCHEMA_VERSION
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun projectsPersistedEventAndTerminalTurnEvidence() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val definition = definition()
        val runId = RunId("run.evidence.1")
        val eventStore = SqlDelightEventStore(database)
        assertIs<io.worldloom.world.DurableStoreWriteResult.Success>(
            eventStore.initialize(InitialGameStateFactory.create(definition, runId)),
        )
        assertIs<EventAppendResult.Success>(eventStore.append(runId, 0, listOf(lifecycleEvent(runId))))
        val directory = SqlDelightRunDirectoryStore(database)
        val afterEvent = directory.list().single()
        assertEquals(RunSaveStatus.SAVED, afterEvent.saveStatus)
        assertEquals(1, afterEvent.lastPersistedEventSequence)

        val turnStore = SqlDelightGameTurnStore(database)
        val turnId = TurnId("run.evidence.1.turn.1")
        val accepted = GameTurn(runId = runId, turnId = turnId, input = "继续", status = GameTurnStatus.ACCEPTED, revision = 0, acceptedSequence = 1)
        assertIs<GameTurnStoreResult.Success>(turnStore.save(accepted, null))
        assertEquals(RunSaveStatus.FACTS_SAVED_DIRECTORY_PENDING, directory.list().single().saveStatus)
        assertFalse(directory.repairPersistenceEvidence(runId))
        val running = accepted.copy(status = GameTurnStatus.RUNNING, revision = 1)
        assertIs<GameTurnStoreResult.Success>(turnStore.save(running, 0))
        val completed = running.copy(
            status = GameTurnStatus.COMPLETED,
            revision = 2,
            deliveredSequence = 1,
            output = "继续前进。",
            outputKind = GameTurnOutputKind.NARRATION,
        )
        assertIs<GameTurnStoreResult.Success>(turnStore.save(completed, 1))

        val saved = directory.list().single()
        assertEquals(RunSaveStatus.SAVED, saved.saveStatus)
        assertEquals(turnId.value, saved.lastPersistedTurnId)
        assertTrue(saved.savedAtEpochMillis > 0)
        driver.close()
    }

    @Test
    fun eventFactsRemainValidWhenDirectoryEvidenceWriteFailsAndCanBeRepaired() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val runId = RunId("run.evidence.failure")
        val eventStore = SqlDelightEventStore(database)
        assertIs<io.worldloom.world.DurableStoreWriteResult.Success>(
            eventStore.initialize(InitialGameStateFactory.create(definition(), runId)),
        )
        driver.execute(
            identifier = null,
            sql = """CREATE TRIGGER fail_directory_evidence BEFORE UPDATE OF last_persisted_event_sequence ON save_run BEGIN SELECT RAISE(FAIL, 'injected directory failure'); END""",
            parameters = 0,
        ).value

        assertIs<EventAppendResult.Success>(eventStore.append(runId, 0, listOf(lifecycleEvent(runId))))
        assertEquals(1, eventStore.read(runId).single().sequence)
        val directory = SqlDelightRunDirectoryStore(database)
        assertEquals(RunSaveStatus.FACTS_SAVED_DIRECTORY_PENDING, directory.list().single().saveStatus)

        driver.execute(null, "DROP TRIGGER fail_directory_evidence", 0).value
        assertTrue(directory.repairPersistenceEvidence(runId))
        val repaired = directory.list().single()
        assertEquals(RunSaveStatus.SAVED, repaired.saveStatus)
        assertEquals(1, repaired.lastPersistedEventSequence)
        driver.close()
    }

    private fun lifecycleEvent(runId: RunId) = EventEnvelope(
        schemaVersion = CURRENT_EVENT_SCHEMA_VERSION,
        eventId = EventId("${runId.value}.event.1"),
        runId = runId,
        sequence = 1,
        causationId = CommandId("${runId.value}.command.1"),
        correlationId = "${runId.value}.correlation.1",
        payload = RunLifecycleChangedEvent(
            previousLifecycle = RunLifecycle.CREATED,
            lifecycle = RunLifecycle.ACTIVE,
        ),
    )

    private fun definition() = assertIs<DefinitionValidationResult.Valid>(
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
}
