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
            assertIs<GmTurnResult.Completed>(firstGm.submit(turnId, "主持人，请根据当前局势推进可用行动"))
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
            assertIs<GmTurnResult.Completed>(resumedGm.submit(turnId, "继续主持，执行当前可用行动"))
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

    private fun gmOrchestrator(
        provider: LanguageModelProvider,
        session: DefaultGameSession,
        database: WorldloomDatabase,
        turns: GameTurnStore,
        followUps: GameTurnFollowUpDispatcher,
    ) = GameTurnOrchestrator(
        runtime = AgentRuntime(
            provider,
            DefaultAgentToolGateway(session, followUps),
            SqlDelightAgentSessionStore(database, session.currentRunId),
        ),
        gameSession = session,
        turnStore = turns,
    )

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
                val actionId = tool.parameters.single { it.name == "actionId" }.allowedValues.first()
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
