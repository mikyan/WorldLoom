package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.world.ActorId
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandPermission
import io.worldloom.world.InMemoryEventStore
import io.worldloom.world.NpcDialogueAudience
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NpcCommunicationContractTest {
    @Test
    fun stationRemoteWhisperIsPlayerVisibleButExcludedFromPublicReplay() = runTest {
        val session = stationSession("station-private")
        assertIs<LoadResult.Success>(session.load(DefinitionId("contract.station-ai")))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        val initial = ready(session)
        assertTrue(initial.presentation.characters.single { it.id.value == "station.npc.lyra" }.nearby)
        val soren = initial.presentation.characters.single { it.id.value == "station.npc.soren" }
        assertFalse(soren.nearby)
        assertEquals(listOf("空间站内部通讯网"), soren.remoteCommunicationMethods.map { it.label })

        val secret = "只在内部频道确认隔离器状态"
        assertIs<ActionResult.Success>(
            session.execute(
                GameSessionCommand.AddressNpc(
                    npcId = DefinitionId("station.npc.soren"),
                    content = secret,
                    idempotencyKey = "station.private.1",
                    audience = NpcDialogueAudience.PRIVATE,
                    communicationMethodId = DefinitionId("station.communication.internal-comms"),
                ),
                CommandAuthorization(ActorId("system.player"), setOf(CommandPermission.ADDRESS_NPC)),
            ),
        )

        val chat = ready(session).presentation.timeline.last().chatMessage
        assertEquals(NpcDialogueAudience.PRIVATE, chat?.audience)
        assertEquals("索伦", chat?.targetName)
        assertEquals("空间站内部通讯网", chat?.communicationLabel)
        val replay = assertIs<PublicReplayResult.Verified>(session.exportVerifiedPublicReplay()).document
        assertTrue(replay.events.none { secret in it.summary || it.chatMessage?.content == secret })
        val committed = session.committedEvents(0).last()
        assertEquals(setOf(DefinitionId("station.npc.soren")), committed.visibleNpcIds)
    }

    @Test
    fun gmCanMoveNpcInAndOutOfNearbyProjectionAndReplayIt() = runTest {
        val session = stationSession("station-presence")
        assertIs<LoadResult.Success>(session.load(DefinitionId("contract.station-ai")))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        val authorization = CommandAuthorization(
            ActorId("worldloom.actor.gm"),
            setOf(CommandPermission.MANAGE_NPC_PRESENCE),
        )

        assertIs<ActionResult.Success>(
            session.execute(GameSessionCommand.SetNpcPresence(DefinitionId("station.npc.lyra"), false), authorization),
        )
        assertFalse(ready(session).presentation.characters.single { it.id.value == "station.npc.lyra" }.nearby)
        assertIs<ActionResult.Success>(
            session.execute(GameSessionCommand.SetNpcPresence(DefinitionId("station.npc.lyra"), true), authorization),
        )
        val beforeReplay = ready(session).presentation
        assertTrue(beforeReplay.characters.single { it.id.value == "station.npc.lyra" }.nearby)

        assertIs<SessionReplayResult.Success>(session.replay())
        assertEquals(beforeReplay, ready(session).presentation)
    }

    private fun kotlinx.coroutines.test.TestScope.stationSession(prefix: String): DefaultGameSession {
        val source = WorldPackageSource(
            manifestJson = resource("station-ai/manifest.json"),
            files = mapOf(
                "world.json" to resource("station-ai/world.json"),
                "playable-world.json" to resource("station-ai/playable-world.json"),
                "character-profile.json" to resource("station-ai/character-profile.json"),
                "behaviors/activity-starts-quest.json" to resource("station-ai/behaviors/activity-starts-quest.json"),
                "behaviors/quest-raises-threat.json" to resource("station-ai/behaviors/quest-raises-threat.json"),
                "behaviors/timed-supply.json" to resource("station-ai/behaviors/timed-supply.json"),
            ),
        )
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(listOf(source)),
        ).catalog
        return DefaultGameSession(
            catalog = catalog,
            eventStore = InMemoryEventStore(),
            idSource = SequentialSessionIdSource(prefix),
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    private fun ready(session: DefaultGameSession): GameSessionUiState.Ready =
        assertIs<GameSessionUiState.Ready>(session.state.value)

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()
}
