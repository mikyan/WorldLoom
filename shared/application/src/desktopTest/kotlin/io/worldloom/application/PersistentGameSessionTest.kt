package io.worldloom.application

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.IntegerValue
import io.worldloom.content.schema.CharacterValueAssignment
import io.worldloom.persistence.SqlDelightEventStore
import io.worldloom.persistence.SqlDelightCharacterCreationDraftStore
import io.worldloom.persistence.SqlDelightBehaviorWorkStore
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.rules.CheckResolvedEvent
import io.worldloom.rules.DiceRandomRequest
import io.worldloom.rules.RandomRecordId
import io.worldloom.rules.RandomServiceResult
import io.worldloom.rules.SeededRandomService
import io.worldloom.rules.QuestStatus
import io.worldloom.behavior.runtime.BehaviorWorkStatus
import io.worldloom.world.RunId
import io.worldloom.world.ActorId
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandPermission
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PersistentGameSessionTest {
    @Test
    fun behaviorQueueAndDerivedFactsResumeWithoutDuplicateExecution() = runTest {
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(
                listOf(WorldPackageSource(resource("station-ai/manifest.json"), contractFiles("station-ai"))),
            ),
        ).catalog
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val eventStore = SqlDelightEventStore(database)
        val workStore = SqlDelightBehaviorWorkStore(database)
        val first = DefaultGameSession(
            catalog = catalog,
            eventStore = eventStore,
            idSource = SequentialSessionIdSource("behavior-resume"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
            snapshotInterval = 1,
            characterDraftStore = SqlDelightCharacterCreationDraftStore(database),
            behaviorWorkStore = workStore,
        )
        assertIs<LoadResult.Success>(first.load(DefinitionId("contract.station-ai")))
        assertIs<ActionResult.Success>(first.confirmCharacter())
        assertIs<ActionResult.Success>(first.perform(GameSessionAction.PerformActivity(DefinitionId("station.activity.wait-cycle"))))
        val runId = RunId("behavior-resume.run.1")
        val eventCount = eventStore.read(runId).size
        val work = workStore.list(runId)
        assertEquals(2, work.count { it.status == BehaviorWorkStatus.COMPLETED })

        val resumedStore = SqlDelightEventStore(WorldloomDatabase(driver))
        val resumedWork = SqlDelightBehaviorWorkStore(WorldloomDatabase(driver))
        val resumed = DefaultGameSession(
            catalog = catalog,
            eventStore = resumedStore,
            idSource = SequentialSessionIdSource("behavior-resume"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
            snapshotInterval = 1,
            characterDraftStore = SqlDelightCharacterCreationDraftStore(WorldloomDatabase(driver)),
            behaviorWorkStore = resumedWork,
        )
        assertIs<LoadResult.Success>(resumed.resume(DefinitionId("contract.station-ai"), runId))
        val presentation = assertIs<GameSessionUiState.Ready>(resumed.state.value).presentation
        val adventure = assertNotNull(presentation.adventureState)
        assertEquals(QuestStatus.ACTIVE, adventure.quests.single().status)
        assertEquals(1, adventure.clocks.single().value)
        assertEquals(eventCount, resumedStore.read(runId).size)
        assertEquals(work, resumedWork.list(runId))
        driver.close()
    }

    @Test
    fun resumesAnInProgressDraftAndConfirmsItExactlyOnce() = runTest {
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(
                listOf(
                    WorldPackageSource(
                        resource("station-ai/manifest.json"),
                        contractFiles("station-ai"),
                    ),
                ),
            ),
        ).catalog
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val drafts = SqlDelightCharacterCreationDraftStore(database)
        val first = DefaultGameSession(
            catalog,
            SqlDelightEventStore(database),
            SequentialSessionIdSource("draft"),
            StandardTestDispatcher(testScheduler),
            characterDraftStore = drafts,
        )
        val worldId = DefinitionId("contract.station-ai")
        assertIs<LoadResult.Success>(first.load(worldId))
        val creating = assertIs<GameSessionUiState.CharacterCreation>(first.state.value).presentation
        assertIs<ActionResult.Success>(
            first.updateCharacter(
                creating.request(
                    values = listOf(
                        CharacterValueAssignment(
                            creating.fields.single().componentId,
                            creating.fields.single().fieldId,
                            IntegerValue(75),
                        ),
                    ),
                ),
            ),
        )

        val resumedStore = SqlDelightEventStore(WorldloomDatabase(driver))
        val resumed = DefaultGameSession(
            catalog,
            resumedStore,
            SequentialSessionIdSource("draft"),
            StandardTestDispatcher(testScheduler),
            characterDraftStore = SqlDelightCharacterCreationDraftStore(WorldloomDatabase(driver)),
        )
        val runId = RunId("draft.run.1")
        assertIs<LoadResult.Success>(resumed.resume(worldId, runId))
        val restored = assertIs<GameSessionUiState.CharacterCreation>(resumed.state.value).presentation
        assertEquals(IntegerValue(75), restored.fields.single().value)
        assertIs<ActionResult.Success>(resumed.confirmCharacter())
        assertEquals(5, assertIs<GameSessionUiState.Ready>(resumed.state.value).presentation.lastSequence)
        assertEquals(5, resumedStore.read(runId).size)
        assertIs<ActionResult.Success>(resumed.confirmCharacter())
        assertEquals(5, resumedStore.read(runId).size)
        assertEquals(null, drafts.load(runId))

        val afterCommitRestart = DefaultGameSession(
            catalog,
            SqlDelightEventStore(WorldloomDatabase(driver)),
            SequentialSessionIdSource("draft"),
            StandardTestDispatcher(testScheduler),
            characterDraftStore = SqlDelightCharacterCreationDraftStore(WorldloomDatabase(driver)),
        )
        assertIs<LoadResult.Success>(afterCommitRestart.resume(worldId, runId))
        assertIs<ActionResult.Success>(afterCommitRestart.confirmCharacter())
        assertEquals(5, resumedStore.read(runId).size)
        driver.close()
    }

    @Test
    fun bothContractWorldsPersistAndResumeThroughTheSameSessionPath() = runTest {
        val sources = listOf("war-survival", "station-ai").map { directory ->
            WorldPackageSource(
                manifestJson = resource("$directory/manifest.json"),
                files = contractFiles(directory),
            )
        }
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(sources),
        ).catalog
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val store = SqlDelightEventStore(database)
        val drafts = SqlDelightCharacterCreationDraftStore(database)

        catalog.entries.forEachIndexed { index, entry ->
            val prefix = "contract-save-$index"
            val session = DefaultGameSession(
                catalog,
                eventStore = store,
                idSource = SequentialSessionIdSource(prefix),
                workerDispatcher = StandardTestDispatcher(testScheduler),
                snapshotInterval = 1,
                characterDraftStore = drafts,
            )
            assertIs<LoadResult.Success>(session.load(entry.id))
            assertIs<GameSessionUiState.CharacterCreation>(session.state.value)
            assertIs<ActionResult.Success>(session.confirmCharacter())
            val check = assertIs<GameSessionUiState.Ready>(session.state.value).presentation.checks.single()
            assertIs<ActionResult.Success>(session.perform(GameSessionAction.ResolvePresentedCheck(check.presentationId)))
            val clockId = if (entry.id.value == "contract.war-survival") {
                DefinitionId("war.clock.patrol-threat")
            } else {
                DefinitionId("station.clock.cascade")
            }
            assertIs<ActionResult.Success>(
                session.execute(
                    GameSessionCommand.AdvanceProgressClock(clockId, 1),
                    CommandAuthorization(
                        ActorId("gm.persistence"),
                        setOf(CommandPermission.ADVANCE_PROGRESS_CLOCK),
                    ),
                ),
            )
            val expectedClock = assertNotNull(
                assertIs<GameSessionUiState.Ready>(session.state.value)
                    .presentation.adventureState,
            ).clocks.single { it.id == clockId }.value

            val resumed = DefaultGameSession(
                catalog,
                eventStore = SqlDelightEventStore(WorldloomDatabase(driver)),
                idSource = SequentialSessionIdSource(prefix),
                workerDispatcher = StandardTestDispatcher(testScheduler),
                snapshotInterval = 1,
                characterDraftStore = SqlDelightCharacterCreationDraftStore(WorldloomDatabase(driver)),
            )
            assertIs<LoadResult.Success>(resumed.resume(entry.id, RunId("$prefix.run.1")))
            val presentation = assertIs<GameSessionUiState.Ready>(resumed.state.value).presentation
            assertEquals(7, presentation.lastSequence)
            assertEquals(expectedClock, assertNotNull(presentation.adventureState).clocks.single { it.id == clockId }.value)
        }
        driver.close()
    }

    @Test
    fun closesResumesAndContinuesAnAuditedRandomRun() = runTest {
        val catalog = catalog()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val runId = RunId("persist.run.1")
        val firstStore = SqlDelightEventStore(WorldloomDatabase(driver))
        val firstSession = DefaultGameSession(
            catalog = catalog,
            eventStore = firstStore,
            idSource = SequentialSessionIdSource("persist"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
            randomServiceFactory = { SeededRandomService(123) },
            snapshotInterval = 1,
            characterDraftStore = SqlDelightCharacterCreationDraftStore(WorldloomDatabase(driver)),
        )
        val worldId = DefinitionId("contract.war-survival")
        assertIs<LoadResult.Success>(firstSession.load(worldId))
        assertIs<ActionResult.Success>(firstSession.confirmCharacter())
        val firstCheck = assertIs<GameSessionUiState.Ready>(firstSession.state.value).presentation.checks.single()
        assertIs<ActionResult.Success>(
            firstSession.perform(GameSessionAction.ResolvePresentedCheck(firstCheck.presentationId)),
        )

        val recreatedStore = SqlDelightEventStore(WorldloomDatabase(driver))
        val resumedSession = DefaultGameSession(
            catalog = catalog,
            eventStore = recreatedStore,
            idSource = SequentialSessionIdSource("persist"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
            randomServiceFactory = { SeededRandomService(123) },
            snapshotInterval = 1,
            characterDraftStore = SqlDelightCharacterCreationDraftStore(WorldloomDatabase(driver)),
        )
        assertIs<LoadResult.Success>(resumedSession.resume(worldId, runId))
        val resumed = assertIs<GameSessionUiState.Ready>(resumedSession.state.value)
        assertEquals(6, resumed.presentation.lastSequence)
        assertIs<ActionResult.Success>(
            resumedSession.perform(
                GameSessionAction.ResolvePresentedCheck(resumed.presentation.checks.single().presentationId),
            ),
        )

        val records = recreatedStore.read(runId).mapNotNull { event ->
            (event.payload as? CheckResolvedEvent)?.record?.randomRecord
        }.map { assertNotNull(it) }
        val expectedService = SeededRandomService(123)
        val expected = listOf("first", "second").map { suffix ->
            assertIs<RandomServiceResult.Success>(
                expectedService.resolve(DiceRandomRequest(2, 6), RandomRecordId("expected.$suffix")),
            ).record.results
        }

        assertEquals(2, records.map { it.id }.toSet().size)
        assertEquals(expected, records.map { it.results })
        assertEquals(7, assertIs<GameSessionUiState.Ready>(resumedSession.state.value).presentation.lastSequence)
        driver.close()
    }

    private fun catalog(): StaticWorldCatalog {
        val source = WorldPackageSource(
            manifestJson = resource("war-survival/manifest.json"),
            files = contractFiles("war-survival"),
        )
        return assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(listOf(source)),
        ).catalog
    }

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()

    private fun contractFiles(directory: String): Map<String, String> = mapOf(
        "world.json" to resource("$directory/world.json"),
        "playable-world.json" to resource("$directory/playable-world.json"),
        "character-profile.json" to resource("$directory/character-profile.json"),
        "behaviors/activity-starts-quest.json" to resource("$directory/behaviors/activity-starts-quest.json"),
        "behaviors/quest-raises-threat.json" to resource("$directory/behaviors/quest-raises-threat.json"),
        "behaviors/timed-supply.json" to resource("$directory/behaviors/timed-supply.json"),
    )
}
