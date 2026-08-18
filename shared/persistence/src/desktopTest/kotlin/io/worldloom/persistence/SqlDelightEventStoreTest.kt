package io.worldloom.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.definition.ComponentDefinition
import io.worldloom.definition.ComponentSeed
import io.worldloom.definition.CURRENT_WORLD_DEFINITION_SCHEMA_VERSION
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.EntitySeed
import io.worldloom.definition.FieldDefinition
import io.worldloom.definition.FieldSeed
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.ValueType
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.WorldDefinitionValidator
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.ActorId
import io.worldloom.world.AdjustNumericComponentCommand
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandEnvelope
import io.worldloom.world.CommandId
import io.worldloom.world.CommandPermission
import io.worldloom.world.CommandValidationResult
import io.worldloom.world.CommandValidator
import io.worldloom.world.CURRENT_COMMAND_SCHEMA_VERSION
import io.worldloom.world.DurableStoreErrorCode
import io.worldloom.world.DurableStoreLoadResult
import io.worldloom.world.DurableStoreWriteResult
import io.worldloom.world.EntityId
import io.worldloom.world.EventAppendResult
import io.worldloom.world.EventId
import io.worldloom.world.EventReplayer
import io.worldloom.world.InitialGameStateFactory
import io.worldloom.world.ReplayResult
import io.worldloom.world.RunId
import io.worldloom.world.StateReducer
import io.worldloom.world.StateReductionResult
import io.worldloom.world.WorldEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SqlDelightEventStoreTest {
    @Test
    fun snapshotAndTailEventsRecoverTheSameStateAfterStoreRecreation() = runTest {
        val fixture = Fixture()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val firstStore = SqlDelightEventStore(database)
        assertIs<DurableStoreWriteResult.Success>(firstStore.initialize(fixture.initialState))

        val event1 = fixture.event(fixture.initialState, 1)
        assertIs<EventAppendResult.Success>(firstStore.append(fixture.runId, 0, listOf(event1)))
        val state1 = assertIs<StateReductionResult.Success>(
            StateReducer.reduce(fixture.initialState, fixture.definition, event1),
        ).state
        assertIs<DurableStoreWriteResult.Success>(firstStore.writeSnapshot(state1))

        val event2 = fixture.event(state1, 2)
        assertIs<EventAppendResult.Success>(firstStore.append(fixture.runId, 1, listOf(event2)))
        val expected = assertIs<StateReductionResult.Success>(
            StateReducer.reduce(state1, fixture.definition, event2),
        ).state

        val recreatedStore = SqlDelightEventStore(WorldloomDatabase(driver))
        val persisted = assertIs<DurableStoreLoadResult.Success>(recreatedStore.loadRun(fixture.runId)).run
        val recovered = assertIs<ReplayResult.Success>(
            EventReplayer.replay(
                assertIs<io.worldloom.world.GameState>(persisted.snapshot),
                fixture.definition,
                persisted.eventsAfterSnapshot,
            ),
        ).state

        assertEquals(1, persisted.snapshot?.lastSequence)
        assertEquals(listOf(2L), persisted.eventsAfterSnapshot.map { it.sequence })
        assertEquals(expected, recovered)
        driver.close()
    }

    @Test
    fun invalidBatchIsRejectedWithoutPartialRows() = runTest {
        val fixture = Fixture()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val store = SqlDelightEventStore(WorldloomDatabase(driver))
        assertIs<DurableStoreWriteResult.Success>(store.initialize(fixture.initialState))
        val event1 = fixture.event(fixture.initialState, 1)
        val invalid = event1.copy(eventId = EventId("event.invalid"), sequence = 3)

        assertIs<EventAppendResult.Failure>(store.append(fixture.runId, 0, listOf(event1, invalid)))
        assertTrue(store.read(fixture.runId).isEmpty())
        driver.close()
    }

    @Test
    fun corruptStoredEventIsReportedWithoutSilentDefaults() = runTest {
        val fixture = Fixture()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val store = SqlDelightEventStore(database)
        assertIs<DurableStoreWriteResult.Success>(store.initialize(fixture.initialState))
        database.worldloomQueries.insertEvent(
            fixture.runId.value,
            1,
            "event.corrupt",
            1,
            "{invalid",
        )

        val failure = assertIs<DurableStoreLoadResult.Failure>(store.loadRun(fixture.runId))

        assertEquals(DurableStoreErrorCode.CORRUPT_DATA, failure.error.code)
        driver.close()
    }

    private class Fixture {
        val runId = RunId("run.persistence")
        private val componentId = DefinitionId("test.status")
        private val fieldId = DefinitionId("test.energy")
        val definition = assertIs<DefinitionValidationResult.Valid>(
            WorldDefinitionValidator.validate(
                WorldDefinition(
                    schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
                    id = DefinitionId("contract.persistence"),
                    title = "Persistence",
                    components = listOf(
                        ComponentDefinition(
                            componentId,
                            listOf(FieldDefinition(fieldId, ValueType.INTEGER, minInteger = 0, maxInteger = 10)),
                        ),
                    ),
                    initialEntities = listOf(
                        EntitySeed(
                            "player",
                            listOf(ComponentSeed(componentId, listOf(FieldSeed(fieldId, IntegerValue(5))))),
                        ),
                    ),
                    presentation = emptyList(),
                ),
            ),
        ).definition
        val initialState = InitialGameStateFactory.create(definition, runId)

        fun event(
            state: io.worldloom.world.GameState,
            index: Int,
        ): io.worldloom.world.EventEnvelope {
            val actor = ActorId("actor.persistence")
            val command = CommandEnvelope(
                schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
                commandId = CommandId("command.persistence.$index"),
                runId = runId,
                actorId = actor,
                expectedSequence = state.lastSequence,
                payload = AdjustNumericComponentCommand(EntityId("player"), componentId, fieldId, -1),
            )
            val validated = assertIs<CommandValidationResult.Valid>(
                CommandValidator.validate(
                    state,
                    definition,
                    CommandAuthorization(actor, setOf(CommandPermission.ADJUST_NUMERIC_COMPONENT)),
                    command,
                ),
            ).command
            return WorldEngine.handle(validated, EventId("event.persistence.$index")).single()
        }
    }
}
