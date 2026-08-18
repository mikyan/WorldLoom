package io.worldloom.agent.runtime

import io.worldloom.application.ActionResult
import io.worldloom.application.DefaultGameSession
import io.worldloom.application.GameSessionAction
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.LoadResult
import io.worldloom.application.SequentialSessionIdSource
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.WorldPackageSource
import io.worldloom.definition.DefinitionId
import io.worldloom.provider.api.LanguageModelProvider
import io.worldloom.provider.api.ProviderCapabilities
import io.worldloom.provider.api.ProviderMessageRole
import io.worldloom.provider.api.ProviderRequest
import io.worldloom.provider.api.ProviderResult
import io.worldloom.provider.api.ProviderToolCall
import io.worldloom.provider.api.ProviderTurn
import io.worldloom.provider.api.ProviderUsage
import io.worldloom.world.InMemoryEventStore
import io.worldloom.world.RunId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NpcSceneOrchestratorContractTest {
    @Test
    fun stationNpcPublishesOnlyToolSpeechAndDoesNotRepeatAfterRecoveryScan() = runTest {
        val session = session("station-ai", "npc-station")
        assertIs<LoadResult.Success>(session.load(id("contract.station-ai")))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        assertEquals(listOf("operator-lyra"), ready(session).presentation.scene?.participantIds?.map { it.value })
        val before = ready(session).presentation.lastSequence
        assertIs<ActionResult.Success>(session.perform(GameSessionAction.PerformActivity(id("station.activity.wait-cycle"))))
        val committedThrough = ready(session).presentation.lastSequence
        val provider = SpeakingNpcProvider()
        val workStore = InMemoryNpcWorkStore()
        val memoryStore = InMemoryAgentMemoryStore()
        val orchestrator = NpcSceneOrchestrator(
            runtime = AgentRuntime(provider, DefaultAgentToolGateway(session), InMemoryAgentSessionStore()),
            gameSession = session,
            workStore = workStore,
            memoryStoreFactory = { memoryStore },
        )
        val request = GameTurnFollowUpRequest(RunId("npc-station.run.1"), before, committedThrough)

        val result = assertIs<GameTurnFollowUpResult.Completed>(orchestrator.dispatch(request))

        assertEquals(listOf(PublicFollowUp("莱拉", "莱拉确认控制台出现了新的诊断结果。")), result.publicResults)
        assertTrue(provider.systemPrompts.single().contains("未写入公开日志的中继认证失败"))
        val presentation = ready(session).presentation
        assertTrue(presentation.timeline.any { it.summary.contains("莱拉确认控制台") })
        assertFalse(presentation.timeline.any { it.summary.contains("PRIVATE") })
        val work = workStore.list(RunId("npc-station.run.1"))
        assertEquals(1, work.size)
        assertEquals(NpcWorkStatus.COMPLETED, work.single().status)
        assertEquals(1, work.single().publicEventIds.size)
        val privateTurns = memoryStore.turns(AgentId("worldloom.agent.npc.station.npc.lyra"))
        assertEquals("PRIVATE: keep investigating the authentication failure", privateTurns.single().output)
        val eventCount = presentation.lastSequence
        val providerCalls = provider.calls

        assertEquals(emptyList(), assertIs<GameTurnFollowUpResult.Completed>(orchestrator.dispatch(request)).publicResults)
        assertEquals(providerCalls, provider.calls)
        assertEquals(eventCount, ready(session).presentation.lastSequence)
        assertEquals(work, workStore.list(RunId("npc-station.run.1")))
        assertIs<io.worldloom.application.SessionReplayResult.Success>(session.replay())
        assertEquals(eventCount, ready(session).presentation.lastSequence)
    }

    @Test
    fun warSceneWakesTwoStableIsolatedNpcsInDeterministicOrder() = runTest {
        val session = session("war-survival", "npc-war")
        assertIs<LoadResult.Success>(session.load(id("contract.war-survival")))
        assertIs<ActionResult.Success>(session.confirmCharacter())
        val before = ready(session).presentation.lastSequence
        assertIs<ActionResult.Success>(session.perform(GameSessionAction.PerformActivity(id("war.activity.search"))))
        val provider = SpeakingNpcProvider()
        val workStore = InMemoryNpcWorkStore()
        val memoryStore = InMemoryAgentMemoryStore()
        val orchestrator = NpcSceneOrchestrator(
            runtime = AgentRuntime(provider, DefaultAgentToolGateway(session), InMemoryAgentSessionStore()),
            gameSession = session,
            workStore = workStore,
            memoryStoreFactory = { memoryStore },
            policy = NpcDispatchPolicy(maxConcurrentPerScene = 1, maxWakesPerEvent = 2),
        )

        val result = assertIs<GameTurnFollowUpResult.Completed>(
            orchestrator.dispatch(
                GameTurnFollowUpRequest(
                    RunId("npc-war.run.1"),
                    before,
                    ready(session).presentation.lastSequence,
                ),
            ),
        )

        assertEquals(listOf("玛拉", "托马斯", "玛拉", "托马斯"), result.publicResults.map { it.source })
        val work = workStore.list(RunId("npc-war.run.1"))
        assertEquals(
            listOf("war.npc.mara", "war.npc.tomas", "war.npc.mara", "war.npc.tomas"),
            work.map { it.npcId.value },
        )
        assertTrue(work.all { it.status == NpcWorkStatus.COMPLETED })
        val mara = memoryStore.turns(AgentId("worldloom.agent.npc.war.npc.mara"))
        val tomas = memoryStore.turns(AgentId("worldloom.agent.npc.war.npc.tomas"))
        assertEquals(2, mara.size)
        assertEquals(2, tomas.size)
        assertTrue(provider.systemPrompts[0].contains("北侧道路"))
        assertFalse(provider.systemPrompts[0].contains("旧水塔"))
        assertTrue(provider.systemPrompts[1].contains("旧水塔"))
        assertFalse(provider.systemPrompts[1].contains("北侧道路"))
        assertTrue(provider.toolResults[0].contains("身体状况"))
        assertFalse(provider.toolResults[1].contains("身体状况"))
    }

    @Test
    fun whitelistedNpcActionBecomesPublicEventWhileInterruptedWorkIsNotReexecuted() = runTest {
        val actionSession = session("station-ai", "npc-action")
        assertIs<LoadResult.Success>(actionSession.load(id("contract.station-ai")))
        assertIs<ActionResult.Success>(actionSession.confirmCharacter())
        val before = ready(actionSession).presentation.lastSequence
        assertIs<ActionResult.Success>(actionSession.perform(GameSessionAction.PerformActivity(id("station.activity.wait-cycle"))))
        val actionProvider = SpeakingNpcProvider(usePublicAction = true)
        val actionStore = InMemoryNpcWorkStore()
        val actionOrchestrator = NpcSceneOrchestrator(
            AgentRuntime(actionProvider, DefaultAgentToolGateway(actionSession)),
            actionSession,
            actionStore,
        )

        val result = assertIs<GameTurnFollowUpResult.Completed>(
            actionOrchestrator.dispatch(
                GameTurnFollowUpRequest(RunId("npc-action.run.1"), before, ready(actionSession).presentation.lastSequence),
            ),
        )

        assertEquals("莱拉检查了控制台的认证记录。", result.publicResults.single().summary)
        assertTrue(ready(actionSession).presentation.timeline.last().summary.contains("检查了控制台"))

        val interruptedSession = session("station-ai", "npc-interrupted")
        assertIs<LoadResult.Success>(interruptedSession.load(id("contract.station-ai")))
        assertIs<ActionResult.Success>(interruptedSession.confirmCharacter())
        assertIs<ActionResult.Success>(interruptedSession.perform(GameSessionAction.PerformActivity(id("station.activity.wait-cycle"))))
        val source = interruptedSession.committedEvents(0).single {
            it.eventType == id("worldloom.event.activity-completed")
        }
        val interruptedStore = InMemoryNpcWorkStore()
        assertIs<NpcWorkCreateResult.Created>(
            interruptedStore.create(
                NpcWorkItem(
                    id = NpcWorkId("npc:${source.eventId}:station.npc.lyra"),
                    runId = RunId("npc-interrupted.run.1"),
                    npcId = id("station.npc.lyra"),
                    sourceEventId = source.eventId,
                    sourceSequence = source.sequence,
                    eventType = source.eventType,
                    sceneId = assertNotNull(source.sceneId),
                    status = NpcWorkStatus.RUNNING,
                    acceptedSequence = ready(interruptedSession).presentation.lastSequence,
                ),
            ),
        )
        val interruptedProvider = SpeakingNpcProvider()

        assertEquals(
            emptyList(),
            assertIs<GameTurnFollowUpResult.Completed>(
                NpcSceneOrchestrator(
                    AgentRuntime(interruptedProvider, DefaultAgentToolGateway(interruptedSession)),
                    interruptedSession,
                    interruptedStore,
                ).dispatch(
                    GameTurnFollowUpRequest(
                        RunId("npc-interrupted.run.1"),
                        0,
                        ready(interruptedSession).presentation.lastSequence,
                    ),
                ),
            ).publicResults,
        )
        assertEquals(0, interruptedProvider.calls)
        assertEquals(NpcWorkStatus.INTERRUPTED, interruptedStore.list(RunId("npc-interrupted.run.1")).single().status)
    }

    @Test
    fun rejectedNpcToolAndTokenBudgetFailureStayPrivateAndDoNotUndoPrimaryFacts() = runTest {
        val cases = listOf(
            RejectingNpcProvider() to AgentRunPolicy(),
            SpeakingNpcProvider() to AgentRunPolicy(maxInputTokens = 1),
        )
        cases.forEachIndexed { index, (provider, runPolicy) ->
            val session = session("station-ai", "npc-failure-$index")
            assertIs<LoadResult.Success>(session.load(id("contract.station-ai")))
            assertIs<ActionResult.Success>(session.confirmCharacter())
            val before = ready(session).presentation.lastSequence
            assertIs<ActionResult.Success>(session.perform(GameSessionAction.PerformActivity(id("station.activity.wait-cycle"))))
            val authoritative = ready(session).presentation.lastSequence
            val store = InMemoryNpcWorkStore()
            val orchestrator = NpcSceneOrchestrator(
                AgentRuntime(provider, DefaultAgentToolGateway(session), policy = runPolicy),
                session,
                store,
            )

            val result = assertIs<GameTurnFollowUpResult.Completed>(
                orchestrator.dispatch(
                    GameTurnFollowUpRequest(RunId("npc-failure-$index.run.1"), before, authoritative),
                ),
            )

            assertEquals(emptyList(), result.publicResults)
            assertEquals(NpcWorkStatus.FAILED, store.list(RunId("npc-failure-$index.run.1")).single().status)
            assertEquals(authoritative, ready(session).presentation.lastSequence)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.session(directory: String, prefix: String): DefaultGameSession {
        val source = WorldPackageSource(
            manifestJson = resource("$directory/manifest.json"),
            files = mapOf(
                "world.json" to resource("$directory/world.json"),
                "playable-world.json" to resource("$directory/playable-world.json"),
                "character-profile.json" to resource("$directory/character-profile.json"),
                "behaviors/activity-starts-quest.json" to resource("$directory/behaviors/activity-starts-quest.json"),
                "behaviors/quest-raises-threat.json" to resource("$directory/behaviors/quest-raises-threat.json"),
                "behaviors/timed-supply.json" to resource("$directory/behaviors/timed-supply.json"),
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

    private fun ready(session: DefaultGameSession) = assertIs<GameSessionUiState.Ready>(session.state.value)

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()

    private companion object {
        fun id(value: String) = DefinitionId(value)
    }
}

private class SpeakingNpcProvider(
    private val usePublicAction: Boolean = false,
) : LanguageModelProvider {
    override val capabilities = ProviderCapabilities(toolCalling = true, streaming = false, structuredOutput = false)
    var calls: Int = 0
    val systemPrompts = mutableListOf<String>()
    val toolResults = mutableListOf<String>()

    override suspend fun complete(request: ProviderRequest): ProviderResult {
        calls += 1
        val hasToolResult = request.messages.any { it.role == ProviderMessageRole.TOOL }
        if (!hasToolResult) {
            val system = request.messages.first().content.orEmpty()
            systemPrompts += system
            val content = when {
                "玛拉" in system -> "玛拉提醒大家压低声音。"
                "托马斯" in system -> "托马斯指向一处可供掩护的断墙。"
                else -> "莱拉确认控制台出现了新的诊断结果。"
            }
            return ProviderResult.Success(
                ProviderTurn(
                    toolCalls = listOf(
                        if (usePublicAction) {
                            ProviderToolCall(
                                "npc-call-$calls",
                                NPC_ACT_TOOL_ID.value,
                                buildJsonObject {
                                    put("actionId", "station.npc-action.inspect-console")
                                    put("content", "莱拉检查了控制台的认证记录。")
                                },
                            )
                        } else {
                            ProviderToolCall(
                                "npc-call-$calls",
                                NPC_SPEAK_TOOL_ID.value,
                                buildJsonObject { put("content", content) },
                            )
                        },
                    ),
                    usage = ProviderUsage(10, 5),
                ),
            )
        }
        toolResults += request.messages.last { it.role == ProviderMessageRole.TOOL }.content.orEmpty()
        return ProviderResult.Success(
            ProviderTurn(
                text = "PRIVATE: keep investigating the authentication failure",
                usage = ProviderUsage(10, 5),
            ),
        )
    }
}

private class RejectingNpcProvider : LanguageModelProvider {
    override val capabilities = ProviderCapabilities(toolCalling = true, streaming = false, structuredOutput = false)

    override suspend fun complete(request: ProviderRequest): ProviderResult = ProviderResult.Success(
        ProviderTurn(
            toolCalls = listOf(
                ProviderToolCall(
                    "rejected-npc-call",
                    NPC_ACT_TOOL_ID.value,
                    buildJsonObject {
                        put("actionId", "station.npc-action.not-allowed")
                        put("content", "尝试未获准的动作")
                    },
                ),
            ),
            usage = ProviderUsage(10, 5),
        ),
    )
}
