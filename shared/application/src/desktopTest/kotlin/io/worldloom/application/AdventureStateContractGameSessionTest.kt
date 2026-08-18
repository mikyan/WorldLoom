package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.rules.InventoryOperation
import io.worldloom.rules.QuestStatus
import io.worldloom.world.ActorId
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandPermission
import io.worldloom.world.InMemoryEventStore
import io.worldloom.world.RunId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdventureStateContractGameSessionTest {
    @Test
    fun warWorldInventoryConditionRelationshipQuestAndClockReplayAsFacts() = runTest {
        val store = InMemoryEventStore()
        val session = session("war-survival", store, "war-adventure")
        assertIs<LoadResult.Success>(session.load(DefinitionId("contract.war-survival")))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        val authorization = authorization()
        val initial = ready(session).presentation.adventureState
        assertNotNull(initial)
        assertEquals(2, initial.inventory.single { it.id.value == "war.item.bread" }.quantity)
        assertEquals(0, initial.relationships.single().value)

        assertSuccess(session, GameSessionCommand.ChangeInventory(id("war.item.bread"), 2, InventoryOperation.ACQUIRE), authorization)
        assertSuccess(session, GameSessionCommand.ChangeInventory(id("war.item.bandage"), 1, InventoryOperation.USE), authorization)
        assertSuccess(session, GameSessionCommand.UpdateCondition(id("war.condition.exhausted"), stackDelta = 2), authorization)
        assertSuccess(session, GameSessionCommand.AdjustRelationship(id("war.relationship.mara-trust"), 1), authorization)
        assertSuccess(
            session,
            GameSessionCommand.AdvanceQuest(id("war.quest.survive"), id("war.quest-stage.find-supplies"), QuestStatus.ACTIVE),
            authorization,
        )
        assertSuccess(
            session,
            GameSessionCommand.AdvanceQuest(id("war.quest.survive"), id("war.quest-stage.reach-convoy"), QuestStatus.ACTIVE),
            authorization,
        )
        assertSuccess(
            session,
            GameSessionCommand.AdvanceQuest(id("war.quest.survive"), id("war.quest-stage.reach-convoy"), QuestStatus.COMPLETED),
            authorization,
        )
        assertSuccess(session, GameSessionCommand.AdvanceProgressClock(id("war.clock.patrol-threat"), 2), authorization)
        val completed = ready(session)
        val adventure = assertNotNull(completed.presentation.adventureState)
        assertEquals(4, adventure.inventory.single { it.id.value == "war.item.bread" }.quantity)
        assertTrue(adventure.inventory.none { it.id.value == "war.item.bandage" })
        assertEquals(2, adventure.conditions.single().stacks)
        assertEquals(480, adventure.conditions.single().remainingMinutes)
        assertEquals(1, adventure.relationships.single().value)
        assertEquals(QuestStatus.COMPLETED, adventure.quests.single().status)
        assertEquals(3, adventure.clocks.single().value)
        assertEquals("war.ending.hopeful", completed.presentation.endingId?.value)

        val beforeReplay = completed.presentation
        assertIs<SessionReplayResult.Success>(session.replay())
        assertEquals(beforeReplay, ready(session).presentation)
        assertTrue(store.read(RunId("war-adventure.run.1")).any { it.payload::class.simpleName == "AdventureEndingReachedEvent" })
    }

    @Test
    fun stationWorldUsesSameModulesAndPrivateConditionDoesNotProject() = runTest {
        val store = InMemoryEventStore()
        val session = session("station-ai", store, "station-adventure")
        assertIs<LoadResult.Success>(session.load(id("contract.station-ai")))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        val authorization = authorization()

        assertSuccess(session, GameSessionCommand.UpdateCondition(id("station.condition.corruption"), stackDelta = 1), authorization)
        assertSuccess(session, GameSessionCommand.AdvanceProgressClock(id("station.clock.cascade"), 4), authorization)

        val presentation = ready(session).presentation
        assertTrue(assertNotNull(presentation.adventureState).conditions.none { it.id.value == "station.condition.corruption" })
        assertEquals("station.ending.degraded", presentation.endingId?.value)
        assertIs<SessionReplayResult.Success>(session.replay())
        assertEquals(presentation, ready(session).presentation)
    }

    @Test
    fun capacityAndQuestTransitionFailuresDoNotAppend() = runTest {
        val store = InMemoryEventStore()
        val session = session("station-ai", store, "adventure-reject")
        assertIs<LoadResult.Success>(session.load(id("contract.station-ai")))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        val authorization = authorization()
        val before = store.read(RunId("adventure-reject.run.1")).size

        val capacity = assertIs<ActionResult.Failure>(
            session.execute(
                GameSessionCommand.ChangeInventory(id("station.item.coolant-cell"), 100, InventoryOperation.ACQUIRE),
                authorization,
            ),
        )
        assertEquals(SessionErrorCode.COMMAND_REJECTED, capacity.error.code)
        val quest = assertIs<ActionResult.Failure>(
            session.execute(
                GameSessionCommand.AdvanceQuest(
                    id("station.quest.restore-grid"),
                    id("station.quest-stage.reroute"),
                    QuestStatus.COMPLETED,
                ),
                authorization,
            ),
        )
        assertEquals(SessionErrorCode.COMMAND_REJECTED, quest.error.code)
        assertEquals(before, store.read(RunId("adventure-reject.run.1")).size)
        assertNull(ready(session).presentation.endingId)
    }

    private suspend fun assertSuccess(
        session: DefaultGameSession,
        command: GameSessionCommand,
        authorization: CommandAuthorization,
    ) {
        assertIs<ActionResult.Success>(session.execute(command, authorization))
    }

    private fun authorization() = CommandAuthorization(
        ActorId("gm.adventure-test"),
        setOf(
            CommandPermission.MANAGE_INVENTORY,
            CommandPermission.UPDATE_CONDITION,
            CommandPermission.UPDATE_RELATIONSHIP,
            CommandPermission.UPDATE_QUEST,
            CommandPermission.ADVANCE_PROGRESS_CLOCK,
        ),
    )

    private fun kotlinx.coroutines.test.TestScope.session(
        directory: String,
        store: InMemoryEventStore,
        prefix: String,
    ): DefaultGameSession {
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(listOf(loadPackage(directory))),
        ).catalog
        return DefaultGameSession(
            catalog = catalog,
            eventStore = store,
            idSource = SequentialSessionIdSource(prefix),
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    private fun ready(session: DefaultGameSession) = assertIs<GameSessionUiState.Ready>(session.state.value)

    private fun loadPackage(directory: String) = WorldPackageSource(
        manifestJson = resource("$directory/manifest.json"),
        files = mapOf(
            "world.json" to resource("$directory/world.json"),
            "playable-world.json" to resource("$directory/playable-world.json"),
            "character-profile.json" to resource("$directory/character-profile.json"),
        ),
    )

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()

    private companion object {
        fun id(value: String) = DefinitionId(value)
    }
}
