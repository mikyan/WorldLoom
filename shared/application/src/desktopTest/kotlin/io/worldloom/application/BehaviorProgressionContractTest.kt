package io.worldloom.application

import io.worldloom.behavior.runtime.BehaviorWorkStatus
import io.worldloom.behavior.runtime.InMemoryBehaviorWorkStore
import io.worldloom.behavior.runtime.BehaviorWorkUpdateResult
import io.worldloom.definition.DefinitionId
import io.worldloom.rules.QuestStatus
import io.worldloom.world.ActorId
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandPermission
import io.worldloom.world.CommandId
import io.worldloom.world.InMemoryEventStore
import io.worldloom.world.RunId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BehaviorProgressionContractTest {
    @Test
    fun replayRejectsTamperedBehaviorCommandAuditWithoutRunningBehaviorAgain() = runTest {
        val eventStore = InMemoryEventStore()
        val workStore = InMemoryBehaviorWorkStore()
        val session = session(eventStore, workStore, "behavior-audit")
        assertIs<LoadResult.Success>(session.load(id("contract.station-ai")))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        assertIs<ActionResult.Success>(session.perform(GameSessionAction.PerformActivity(id("station.activity.wait-cycle"))))
        val runId = RunId("behavior-audit.run.1")
        val completed = workStore.list(runId).first { it.derivedCommandCount > 0 }
        assertIs<BehaviorWorkUpdateResult.Updated>(
            workStore.update(
                completed.revision,
                completed.copy(derivedCommandIds = listOf(CommandId("behavior.command.missing"))),
            ),
        )
        val before = eventStore.read(runId)

        val replay = assertIs<SessionReplayResult.Failure>(session.replay())

        assertEquals(SessionErrorCode.REPLAY_REJECTED, replay.error.code)
        assertEquals(before, eventStore.read(runId))
    }

    @Test
    fun committedActivityCascadesThroughQuestAndClockWithoutReexecutionOnReplay() = runTest {
        val eventStore = InMemoryEventStore()
        val workStore = InMemoryBehaviorWorkStore()
        val session = session(eventStore, workStore, "behavior-chain")
        assertIs<LoadResult.Success>(session.load(id("contract.station-ai")))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        assertTrue(ready(session).presentation.scene?.actions.orEmpty().none { it.id.value == "station.action.open-relay" })

        assertIs<ActionResult.Success>(session.perform(GameSessionAction.PerformActivity(id("station.activity.wait-cycle"))))
        val advanced = ready(session).presentation
        val adventure = assertNotNull(advanced.adventureState)
        assertEquals(QuestStatus.ACTIVE, adventure.quests.single().status)
        assertEquals(1, adventure.clocks.single().value)
        assertTrue(advanced.scene?.actions.orEmpty().any { it.id.value == "station.action.open-relay" })
        val firstWork = workStore.list(RunId("behavior-chain.run.1"))
        assertEquals(2, firstWork.size)
        assertTrue(firstWork.all { it.status == BehaviorWorkStatus.COMPLETED })
        assertEquals(listOf(0, 1), firstWork.map { it.causalDepth }.sorted())
        assertEquals(1, firstWork.map { it.rootEventId }.toSet().size)
        assertTrue(
            eventStore.read(RunId("behavior-chain.run.1")).any {
                it.causationId.value.startsWith("behavior.")
            },
        )

        val beforeReplay = advanced
        assertIs<SessionReplayResult.Success>(session.replay())
        assertEquals(beforeReplay, ready(session).presentation)
        assertEquals(firstWork, workStore.list(RunId("behavior-chain.run.1")))

        assertIs<ActionResult.Success>(
            session.execute(
                GameSessionCommand.PerformAvailableAction(
                    id("station.action.open-relay"),
                    id("station.outcome.stable"),
                ),
                CommandAuthorization(ActorId("gm.behavior"), setOf(CommandPermission.APPLY_ACTION_OUTCOME)),
            ),
        )
        assertEquals("station.scene.relay", ready(session).presentation.scene?.id?.value)

        assertIs<ActionResult.Success>(
            session.execute(
                GameSessionCommand.AdvanceWorldTime(30),
                CommandAuthorization(ActorId("gm.behavior"), setOf(CommandPermission.ADVANCE_WORLD_TIME)),
            ),
        )
        val timed = assertNotNull(ready(session).presentation.adventureState)
        assertEquals(3, timed.inventory.single { it.id.value == "station.item.coolant-cell" }.quantity)
        assertEquals(3, workStore.list(RunId("behavior-chain.run.1")).count { it.status == BehaviorWorkStatus.COMPLETED })

        val questAuthorization = CommandAuthorization(
            ActorId("gm.behavior"),
            setOf(CommandPermission.UPDATE_QUEST),
        )
        assertIs<ActionResult.Success>(
            session.execute(
                GameSessionCommand.AdvanceQuest(
                    id("station.quest.restore-grid"),
                    id("station.quest-stage.diagnose"),
                    QuestStatus.ACTIVE,
                ),
                questAuthorization,
            ),
        )
        assertIs<ActionResult.Success>(
            session.execute(
                GameSessionCommand.AdvanceQuest(
                    id("station.quest.restore-grid"),
                    id("station.quest-stage.reroute"),
                    QuestStatus.ACTIVE,
                ),
                questAuthorization,
            ),
        )
        assertIs<ActionResult.Success>(
            session.execute(
                GameSessionCommand.AdvanceQuest(
                    id("station.quest.restore-grid"),
                    id("station.quest-stage.reroute"),
                    QuestStatus.COMPLETED,
                ),
                questAuthorization,
            ),
        )
        val ending = ready(session).presentation
        assertEquals(4, assertNotNull(ending.adventureState).clocks.single().value)
        assertEquals("station.ending.degraded", ending.endingId?.value)
        assertEquals(6, workStore.list(RunId("behavior-chain.run.1")).count { it.status == BehaviorWorkStatus.COMPLETED })
    }

    @Test
    fun recursiveBehaviorPausesItsChainAtTheRepeatedSignatureLimit() = runTest {
        val recursive = resource("station-ai/behaviors/quest-raises-threat.json")
            .replace("worldloom.event.quest.advanced", "worldloom.event.progress-clock.advanced")
        val eventStore = InMemoryEventStore()
        val workStore = InMemoryBehaviorWorkStore()
        val session = session(eventStore, workStore, "behavior-limit", recursive)
        assertIs<LoadResult.Success>(session.load(id("contract.station-ai")))
        assertIs<ActionResult.Success>(session.confirmCharacter())

        assertIs<ActionResult.Success>(
            session.execute(
                GameSessionCommand.AdvanceProgressClock(id("station.clock.cascade"), 1),
                CommandAuthorization(ActorId("gm.behavior"), setOf(CommandPermission.ADVANCE_PROGRESS_CLOCK)),
            ),
        )

        val ready = ready(session)
        assertEquals(3, assertNotNull(ready.presentation.adventureState).clocks.single().value)
        assertEquals(SessionErrorCode.BEHAVIOR_PAUSED, ready.notice?.code)
        val work = workStore.list(RunId("behavior-limit.run.1"))
        assertEquals(listOf(0, 1, 2), work.map { it.causalDepth }.sorted())
        assertEquals(2, work.count { it.status == BehaviorWorkStatus.COMPLETED })
        assertEquals(1, work.count { it.status == BehaviorWorkStatus.PAUSED })
    }

    private fun kotlinx.coroutines.test.TestScope.session(
        eventStore: InMemoryEventStore,
        workStore: InMemoryBehaviorWorkStore,
        prefix: String,
        questBehavior: String = resource("station-ai/behaviors/quest-raises-threat.json"),
    ): DefaultGameSession {
        val source = WorldPackageSource(
            manifestJson = resource("station-ai/manifest.json"),
            files = mapOf(
                "world.json" to resource("station-ai/world.json"),
                "playable-world.json" to resource("station-ai/playable-world.json"),
                "character-profile.json" to resource("station-ai/character-profile.json"),
                "behaviors/activity-starts-quest.json" to resource("station-ai/behaviors/activity-starts-quest.json"),
                "behaviors/quest-raises-threat.json" to questBehavior,
                "behaviors/timed-supply.json" to resource("station-ai/behaviors/timed-supply.json"),
            ),
        )
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(listOf(source)),
        ).catalog
        return DefaultGameSession(
            catalog = catalog,
            eventStore = eventStore,
            idSource = SequentialSessionIdSource(prefix),
            workerDispatcher = StandardTestDispatcher(testScheduler),
            behaviorWorkStore = workStore,
        )
    }

    private fun ready(session: DefaultGameSession) = assertIs<GameSessionUiState.Ready>(session.state.value)

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()

    private companion object {
        fun id(value: String) = DefinitionId(value)
    }
}
