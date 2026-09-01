package io.worldloom.agent.runtime

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.application.ActionResult
import io.worldloom.application.DefaultGameSession
import io.worldloom.application.GameSessionAction
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.LoadResult
import io.worldloom.application.PublicReplayResult
import io.worldloom.application.SequentialSessionIdSource
import io.worldloom.application.SessionReplayResult
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.WorldPackageSource
import io.worldloom.definition.DefinitionId
import io.worldloom.persistence.SqlDelightAgentSessionStore
import io.worldloom.persistence.SqlDelightBehaviorWorkStore
import io.worldloom.persistence.SqlDelightCharacterCreationDraftStore
import io.worldloom.persistence.SqlDelightEventStore
import io.worldloom.persistence.SqlDelightGameTurnStore
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.provider.api.LanguageModelProvider
import io.worldloom.provider.api.ProviderCapabilities
import io.worldloom.provider.api.ProviderMessageRole
import io.worldloom.provider.api.ProviderRequest
import io.worldloom.provider.api.ProviderResult
import io.worldloom.provider.api.ProviderToolDefinition
import io.worldloom.provider.api.ProviderToolCall
import io.worldloom.provider.api.ProviderTurn
import io.worldloom.provider.api.ProviderUsage
import io.worldloom.rules.DiceRandomRequest
import io.worldloom.rules.RANDOM_ALGORITHM_VERSION
import io.worldloom.rules.RandomRecord
import io.worldloom.rules.RandomRecordId
import io.worldloom.rules.RandomRequest
import io.worldloom.rules.RandomRestoreResult
import io.worldloom.rules.RandomServiceError
import io.worldloom.rules.RandomServiceErrorCode
import io.worldloom.rules.RandomServiceResult
import io.worldloom.rules.RestorableRandomService
import io.worldloom.world.ActorId
import io.worldloom.world.CommandPermission
import io.worldloom.world.RunId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Release-authoritative journey: every objective fact still enters through the typed world pipeline. */
class AlphaJourneySystemTest {
    @Test
    fun tenTurnNovicePharmacyJourneySurvivesRestartAndReplayWithoutInternalCodes() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val catalog = catalog()
        val worldId = DefinitionId("contract.war-survival")
        val dice = List(10) { 6 }
        val provider = GuidedOpeningFakeProvider()
        val firstDatabase = WorldloomDatabase(driver)
        val first = DefaultGameSession(
            catalog = catalog,
            eventStore = SqlDelightEventStore(firstDatabase),
            idSource = SequentialSessionIdSource("guided-before"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
            randomServiceFactory = { RestorableScriptedRandomService(dice) },
            snapshotInterval = 1,
            characterDraftStore = SqlDelightCharacterCreationDraftStore(firstDatabase),
            behaviorWorkStore = SqlDelightBehaviorWorkStore(firstDatabase),
        )
        assertIs<LoadResult.Success>(first.load(worldId))
        assertIs<ActionResult.Success>(first.confirmCharacter())
        val runId = assertNotNull(first.currentRunId)
        val firstTurns = SqlDelightGameTurnStore(firstDatabase)
        val firstGateway = DefaultAgentToolGateway(first)
        val firstGm = GameTurnOrchestrator(
            AgentRuntime(
                provider,
                firstGateway,
                SqlDelightAgentSessionStore(firstDatabase, runId),
            ),
            first,
            firstTurns,
        )
        val inputs = listOf(
            "先观察街道",
            "询问玛拉伤势",
            "冒险进入药房",
            "检查服务门",
            "请玛拉辨认药品",
            "取出急救箱并撤离",
            "比较排水渠两条路线",
            "询问玛拉路线",
            "询问托马斯路线",
            "选择避难所方向",
        )

        inputs.take(7).forEach { input ->
            val turnId = firstTurns.nextTurnId(runId)
            val result = assertIs<GmTurnResult.Completed>(completeTurn(firstGm, firstGateway, turnId, input))
            assertTrue(result.turn.output.orEmpty().let { "war." !in it && "worldloom." !in it })
        }
        val beforeRestart = ready(first).presentation
        assertEquals(DefinitionId("war.scene.drainage"), beforeRestart.scene?.id)
        assertTrue(beforeRestart.exploration.connections.any { it.id == DefinitionId("war.path.pharmacy-drainage") })

        val resumedDatabase = WorldloomDatabase(driver)
        val resumed = DefaultGameSession(
            catalog = catalog,
            eventStore = SqlDelightEventStore(resumedDatabase),
            idSource = SequentialSessionIdSource("guided-after"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
            randomServiceFactory = { RestorableScriptedRandomService(dice) },
            snapshotInterval = 1,
            characterDraftStore = SqlDelightCharacterCreationDraftStore(resumedDatabase),
            behaviorWorkStore = SqlDelightBehaviorWorkStore(resumedDatabase),
        )
        assertIs<LoadResult.Success>(resumed.resume(worldId, runId))
        assertEquals(beforeRestart, ready(resumed).presentation)
        val resumedTurns = SqlDelightGameTurnStore(resumedDatabase)
        val resumedGateway = DefaultAgentToolGateway(resumed)
        val resumedGm = GameTurnOrchestrator(
            AgentRuntime(
                provider,
                resumedGateway,
                SqlDelightAgentSessionStore(resumedDatabase, runId),
            ),
            resumed,
            resumedTurns,
        )
        inputs.drop(7).forEach { input ->
            val turnId = resumedTurns.nextTurnId(runId)
            val result = assertIs<GmTurnResult.Completed>(completeTurn(resumedGm, resumedGateway, turnId, input))
            assertTrue(result.turn.output.orEmpty().let { "war." !in it && "worldloom." !in it })
        }

        val completedOpening = ready(resumed).presentation
        assertEquals(DefinitionId("war.scene.shelter"), completedOpening.scene?.id)
        assertEquals(10, resumedTurns.history(runId, limit = 20).let {
            assertIs<GameTurnHistoryResult.Success>(it).page.entries.size
        })
        assertIs<SessionReplayResult.Success>(resumed.replay())
        assertEquals(completedOpening, ready(resumed).presentation)
        driver.close()
    }

    @Test
    fun fakeGmHostsBuiltInJourneyAcrossNpcBehaviorRestartEndingAndPublicReplay() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val startupMark = TimeSource.Monotonic.markNow()
        val catalog = catalog()
        val worldId = DefinitionId("contract.war-survival")
        val provider = AlphaFakeProvider()
        val first = session(catalog, database, "alpha-before")

        assertIs<LoadResult.Success>(first.load(worldId))
        assertIs<ActionResult.Success>(first.confirmCharacter())
        assertTrue(startupMark.elapsedNow() < 10.seconds, "Built-in Run startup exceeded the Alpha budget")
        val runId = assertNotNull(first.currentRunId)
        assertIs<ActionResult.Success>(first.perform(GameSessionAction.AdvanceWorldTime(15)))
        val beforeActivity = ready(first).presentation.lastSequence
        assertIs<ActionResult.Success>(
            first.perform(GameSessionAction.PerformActivity(DefinitionId("war.activity.search"))),
        )

        val npcErrors = mutableListOf<String>()
        val firstNpc = npcOrchestrator(provider, first, npcErrors)
        val npcResult = assertIs<GameTurnFollowUpResult.Completed>(
            firstNpc.dispatch(
                GameTurnFollowUpRequest(runId, beforeActivity, ready(first).presentation.lastSequence),
            ),
        )
        assertTrue(
            npcResult.publicResults.isNotEmpty(),
            "NPC follow-up produced no public result; providerCalls=${provider.npcToolCalls}, errors=$npcErrors",
        )
        assertTrue(ready(first).presentation.adventureState?.quests?.any { it.status.name == "ACTIVE" } == true)

        assertIs<ActionResult.Success>(
            first.perform(GameSessionAction.Travel(DefinitionId("war.travel.ruins-to-shelter"))),
        )
        if (ready(first).presentation.scene?.id?.value == "war.scene.shelter") {
            assertIs<ActionResult.Success>(
                first.perform(GameSessionAction.Travel(DefinitionId("war.travel.shelter-to-ruins"))),
            )
        }

        val firstTurns = SqlDelightGameTurnStore(database)
        val firstGm = gmOrchestrator(provider, first, database, firstTurns, firstNpc)
        repeat(2) {
            val turnId = firstTurns.nextTurnId(runId)
            val turnMark = TimeSource.Monotonic.markNow()
            assertIs<GmTurnResult.Completed>(
                completeTurn(firstGm.orchestrator, firstGm.gateway, turnId, "主持人，请根据当前局势推进可用行动"),
            )
            assertTrue(turnMark.elapsedNow() < 10.seconds, "Fake GM foreground turn exceeded the Alpha budget")
        }
        val sequenceBeforeRestart = ready(first).presentation.lastSequence
        val sceneBeforeRestart = ready(first).presentation.scene?.id

        val resumed = session(catalog, WorldloomDatabase(driver), "alpha-after")
        assertIs<LoadResult.Success>(resumed.resume(worldId, runId))
        assertEquals(sequenceBeforeRestart, ready(resumed).presentation.lastSequence)
        assertEquals(sceneBeforeRestart, ready(resumed).presentation.scene?.id)

        val resumedDatabase = WorldloomDatabase(driver)
        val resumedNpc = npcOrchestrator(provider, resumed, npcErrors)
        val resumedTurns = SqlDelightGameTurnStore(resumedDatabase)
        val resumedGm = gmOrchestrator(provider, resumed, resumedDatabase, resumedTurns, resumedNpc)
        var remainingTurns = 0
        while (resumed.state.value is GameSessionUiState.Ready && remainingTurns < 10) {
            val turnId = resumedTurns.nextTurnId(runId)
            assertIs<GmTurnResult.Completed>(
                completeTurn(resumedGm.orchestrator, resumedGm.gateway, turnId, "继续主持，执行当前可用行动"),
            )
            remainingTurns += 1
        }

        val ending = assertIs<GameSessionUiState.Ended>(resumed.state.value).presentation
        assertNotNull(ending.endingId)
        assertTrue(!ending.endingSummary.isNullOrBlank())
        assertIs<SessionReplayResult.Success>(resumed.replay())
        val publicReplay = assertIs<PublicReplayResult.Verified>(resumed.exportVerifiedPublicReplay()).document
        assertEquals(ending.lastSequence, publicReplay.lastSequence)
        assertTrue(publicReplay.events.any { it.eventType == "worldloom.event.npc.public-action" })
        assertTrue(publicReplay.events.any { it.causationId.orEmpty().startsWith("behavior.") })
        assertTrue(provider.gmToolCalls >= 3)
        assertTrue(provider.npcToolCalls >= 1)
        driver.close()
    }

    private fun TestScope.session(
        catalog: StaticWorldCatalog,
        database: WorldloomDatabase,
        prefix: String,
    ) = DefaultGameSession(
        catalog = catalog,
        eventStore = SqlDelightEventStore(database),
        idSource = SequentialSessionIdSource(prefix),
        workerDispatcher = StandardTestDispatcher(testScheduler),
        snapshotInterval = 1,
        characterDraftStore = SqlDelightCharacterCreationDraftStore(database),
        behaviorWorkStore = SqlDelightBehaviorWorkStore(database),
    )

    private fun npcOrchestrator(
        provider: LanguageModelProvider,
        session: DefaultGameSession,
        errors: MutableList<String>,
    ) = NpcSceneOrchestrator(
        runtime = AgentRuntime(
            provider,
            RecordingToolGateway(DefaultAgentToolGateway(session), errors),
            InMemoryAgentSessionStore(),
        ),
        gameSession = session,
        workStore = InMemoryNpcWorkStore(),
        memoryStoreFactory = { InMemoryAgentMemoryStore() },
    )

    private data class TestGmHost(
        val orchestrator: GameTurnOrchestrator,
        val gateway: DefaultAgentToolGateway,
    )

    private fun gmOrchestrator(
        provider: LanguageModelProvider,
        session: DefaultGameSession,
        database: WorldloomDatabase,
        turns: GameTurnStore,
        followUps: GameTurnFollowUpDispatcher,
    ): TestGmHost {
        val gateway = DefaultAgentToolGateway(session, followUps)
        return TestGmHost(
            GameTurnOrchestrator(
                runtime = AgentRuntime(
                    provider,
                    gateway,
                    SqlDelightAgentSessionStore(database, session.currentRunId),
                ),
                gameSession = session,
                turnStore = turns,
            ),
            gateway,
        )
    }

    private suspend fun completeTurn(
        orchestrator: GameTurnOrchestrator,
        gateway: DefaultAgentToolGateway,
        turnId: TurnId,
        input: String,
    ): GmTurnResult {
        val submitted = orchestrator.submit(turnId, input)
        if (submitted !is GmTurnResult.AwaitingPlayer || submitted.turn.pendingCheck == null) return submitted
        return orchestrator.resolvePendingCheck(
            turnId,
            { check ->
                gateway.confirmPlayerCheck(
                    AgentIdentity(
                        GM_AGENT_ID,
                        ActorId("system.player"),
                        setOf(CommandPermission.APPLY_ACTION_OUTCOME, CommandPermission.RESOLVE_CHECK),
                    ),
                    check,
                )
            },
        )
    }

    private fun ready(session: DefaultGameSession): GameSessionUiState.Ready = assertIs(session.state.value)

    private fun catalog(): StaticWorldCatalog = assertIs<StaticWorldCatalogResult.Success>(
        StaticWorldCatalog.fromPackageSources(
            listOf(
                WorldPackageSource(
                    manifestJson = resource("war-survival/manifest.json"),
                    files = mapOf(
                        "world.json" to resource("war-survival/world.json"),
                        "playable-world.json" to resource("war-survival/playable-world.json"),
                        "character-profile.json" to resource("war-survival/character-profile.json"),
                        "behaviors/activity-starts-quest.json" to resource("war-survival/behaviors/activity-starts-quest.json"),
                        "behaviors/quest-raises-threat.json" to resource("war-survival/behaviors/quest-raises-threat.json"),
                        "behaviors/timed-supply.json" to resource("war-survival/behaviors/timed-supply.json"),
                    ),
                ),
            ),
        ),
    ).catalog

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()

    private class AlphaFakeProvider : LanguageModelProvider {
        override val capabilities = ProviderCapabilities(toolCalling = true, streaming = false, structuredOutput = false)
        var gmToolCalls: Int = 0
            private set
        var npcToolCalls: Int = 0
            private set
        private var callOrdinal: Int = 0

        override suspend fun complete(request: ProviderRequest): ProviderResult {
            callOrdinal += 1
            if (request.messages.last().role == ProviderMessageRole.TOOL) {
                return ProviderResult.Success(
                    ProviderTurn("PRIVATE: authoritative tool result accepted", usage = ProviderUsage(8, 4)),
                )
            }
            request.tools.firstOrNull { it.name == PERFORM_ACTION_TOOL_ID.value }?.let { tool ->
                val actionId = tool.parameters.single { it.name == "actionId" }.allowedValues.last()
                gmToolCalls += 1
                return ProviderResult.Success(
                    ProviderTurn(
                        toolCalls = listOf(
                            ProviderToolCall(
                                "alpha-gm-$callOrdinal",
                                tool.name,
                                buildJsonObject { put("actionId", actionId) },
                            ),
                        ),
                        usage = ProviderUsage(12, 4),
                    ),
                )
            }
            request.tools.firstOrNull { it.name == NPC_SPEAK_TOOL_ID.value }?.let { tool ->
                npcToolCalls += 1
                return ProviderResult.Success(
                    ProviderTurn(
                        toolCalls = listOf(
                            ProviderToolCall(
                                "alpha-npc-$callOrdinal",
                                tool.name,
                                buildJsonObject { put("content", "同伴确认了眼前的变化，并等待你的决定。") },
                            ),
                        ),
                        usage = ProviderUsage(10, 4),
                    ),
                )
            }
            return ProviderResult.Success(ProviderTurn("局势暂时稳定。", usage = ProviderUsage(4, 2)))
        }
    }

    private class GuidedOpeningFakeProvider : LanguageModelProvider {
        override val capabilities = ProviderCapabilities(toolCalling = true, streaming = false, structuredOutput = false)
        private var callOrdinal = 0

        override suspend fun complete(request: ProviderRequest): ProviderResult {
            callOrdinal += 1
            if (request.messages.last().role == ProviderMessageRole.TOOL) {
                return ProviderResult.Success(
                    ProviderTurn("眼前的局势已经按你的选择发生变化。", usage = ProviderUsage(10, 6)),
                )
            }
            val input = request.messages.lastOrNull { it.role == ProviderMessageRole.USER }?.content.orEmpty()
            val call = when {
                "观察街道" in input -> action("war.action.observe-street")
                "询问玛拉伤势" in input -> address("war.npc.mara", "你的伤还能撑住吗？北街安全吗？")
                "进入药房" in input -> action("war.action.search-supplies")
                "检查服务门" in input -> action("war.action.inspect-service-door")
                "玛拉辨认药品" in input -> address("war.npc.mara", "请帮我辨认值得带走的药品。")
                "取出急救箱" in input -> action("war.action.secure-medicine")
                "询问玛拉路线" in input -> address("war.npc.mara", "为什么你想先去避难所？")
                "询问托马斯路线" in input -> address("war.npc.tomas", "为什么你认为水塔值得冒险？")
                "选择避难所" in input -> action("war.action.follow-mara")
                else -> null
            }
            return ProviderResult.Success(
                if (call == null) {
                    ProviderTurn("两条路线各有代价，你仍可以自由追问或选择。", usage = ProviderUsage(12, 8))
                } else {
                    ProviderTurn(toolCalls = listOf(call), usage = ProviderUsage(12, 4))
                },
            )
        }

        private fun action(actionId: String) = ProviderToolCall(
            id = "guided-action-$callOrdinal",
            name = PERFORM_ACTION_TOOL_ID.value,
            arguments = buildJsonObject { put("actionId", actionId) },
        )

        private fun address(npcId: String, content: String) = ProviderToolCall(
            id = "guided-address-$callOrdinal",
            name = NPC_ADDRESS_TOOL_ID.value,
            arguments = buildJsonObject {
                put("npcId", npcId)
                put("content", content)
                put("audience", "NEARBY_GROUP")
            },
        )
    }

    private class RestorableScriptedRandomService(values: List<Int>) : RestorableRandomService {
        private val scripted = values.toList()
        private var consumed = 0

        override fun resolve(request: RandomRequest, recordId: RandomRecordId): RandomServiceResult {
            val dice = request as? DiceRandomRequest ?: return RandomServiceResult.Failure(
                RandomServiceError(RandomServiceErrorCode.INVALID_REQUEST, "Only dice requests are supported"),
            )
            if (consumed + dice.count > scripted.size) return RandomServiceResult.Failure(
                RandomServiceError(RandomServiceErrorCode.INVALID_REQUEST, "Scripted journey ran out of dice"),
            )
            val results = scripted.subList(consumed, consumed + dice.count)
            consumed += dice.count
            return RandomServiceResult.Success(RandomRecord(recordId, RANDOM_ALGORITHM_VERSION, request, results))
        }

        override fun restore(records: List<RandomRecord>): RandomRestoreResult {
            val recorded = records.flatMap(RandomRecord::results)
            if (recorded != scripted.take(recorded.size)) {
                return RandomRestoreResult.Failure("Recorded dice do not match scripted journey")
            }
            consumed = recorded.size
            return RandomRestoreResult.Success
        }
    }

    private class RecordingToolGateway(
        private val delegate: AgentToolGateway,
        private val errors: MutableList<String>,
    ) : AgentToolGateway {
        override suspend fun availableTools(identity: AgentIdentity): List<ProviderToolDefinition> =
            delegate.availableTools(identity)

        override suspend fun validate(identity: AgentIdentity, call: ProviderToolCall): ToolValidationResult =
            delegate.validate(identity, call).also { result ->
                if (result is ToolValidationResult.Invalid) errors += "validate: ${result.error.message}"
            }

        override suspend fun invoke(identity: AgentIdentity, call: ProviderToolCall): ToolInvocationResult =
            delegate.invoke(identity, call).also { result ->
                if (result is ToolInvocationResult.Failure) errors += "invoke: ${result.error.message}"
            }
    }
}
