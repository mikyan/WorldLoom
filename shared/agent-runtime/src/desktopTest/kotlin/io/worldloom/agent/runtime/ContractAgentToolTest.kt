package io.worldloom.agent.runtime

import io.worldloom.application.DefaultGameSession
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.LoadResult
import io.worldloom.application.SequentialSessionIdSource
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.WorldPackageSource
import io.worldloom.definition.DefinitionId
import io.worldloom.provider.api.LanguageModelProvider
import io.worldloom.provider.api.ProviderCapabilities
import io.worldloom.provider.api.ProviderFailureCode
import io.worldloom.provider.api.ProviderRequest
import io.worldloom.provider.api.ProviderResult
import io.worldloom.provider.api.ProviderToolCall
import io.worldloom.provider.api.ProviderTurn
import io.worldloom.provider.api.ProviderUsage
import io.worldloom.world.ActorId
import io.worldloom.world.CommandPermission
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContractAgentToolTest {
    @Test
    fun gmTemporalToolsExposeOnlyCurrentOptionsAndCommitWorldTime() = runTest {
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(listOf(loadPackage("station-ai"))),
        ).catalog
        val session = DefaultGameSession(
            catalog = catalog,
            idSource = SequentialSessionIdSource("temporal-tools"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
        assertIs<LoadResult.Success>(session.load(DefinitionId("contract.station-ai")))
        assertIs<io.worldloom.application.ActionResult.Success>(session.confirmCharacter())
        val identity = AgentIdentity(
            AgentId("agent.gm.temporal"),
            ActorId("gm.temporal"),
            setOf(
                CommandPermission.ADVANCE_WORLD_TIME,
                CommandPermission.PERFORM_ACTIVITY,
                CommandPermission.TRAVEL,
                CommandPermission.RESOLVE_CHECK,
            ),
        )
        val gateway = DefaultAgentToolGateway(session)
        val tools = gateway.availableTools(identity).associateBy { it.name }

        assertEquals(
            setOf(
                ADVANCE_TIME_TOOL_ID.value,
                PERFORM_ACTIVITY_TOOL_ID.value,
                TRAVEL_TOOL_ID.value,
                RESOLVE_CHECK_TOOL_ID.value,
            ),
            tools.keys,
        )
        assertEquals(
            listOf("station.travel.core-to-relay"),
            tools.getValue(TRAVEL_TOOL_ID.value).parameters
                .single { it.name == "routeId" }
                .allowedValues,
        )
        val advanced = assertIs<ToolInvocationResult.Success>(
            gateway.invoke(
                identity,
                ProviderToolCall(
                    "wait-call",
                    ADVANCE_TIME_TOOL_ID.value,
                    JsonObject(mapOf("deltaMinutes" to JsonPrimitive(30))),
                ),
            ),
        )
        assertTrue(advanced.output.contains("\"worldTimeMinutes\":30"))

        assertIs<ToolInvocationResult.Success>(
            gateway.invoke(
                identity,
                ProviderToolCall(
                    "travel-call",
                    TRAVEL_TOOL_ID.value,
                    JsonObject(mapOf("routeId" to JsonPrimitive("station.travel.core-to-relay"))),
                ),
            ),
        )
        val ready = assertIs<GameSessionUiState.Ready>(session.state.value)
        assertEquals(50, ready.presentation.worldTimeMinutes)
        assertEquals("station.scene.relay", ready.presentation.scene?.id?.value)
    }

    @Test
    fun bothContractWorldsUseTheSameAgentToolRuntime() = runTest {
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(
                listOf("war-survival", "station-ai").map(::loadPackage),
            ),
        ).catalog
        val cases = listOf(
            ContractCase("contract.war-survival", "war.check.survive"),
            ContractCase("contract.station-ai", "station.check.system-integrity"),
        )

        cases.forEach { case ->
            val session = DefaultGameSession(
                catalog = catalog,
                idSource = SequentialSessionIdSource(case.worldId),
                workerDispatcher = StandardTestDispatcher(testScheduler),
            )
            assertIs<LoadResult.Success>(session.load(DefinitionId(case.worldId)))
            assertIs<io.worldloom.application.ActionResult.Success>(session.confirmCharacter())
            val provider = ContractProvider(case.profileId)
            val result = AgentRuntime(provider, DefaultAgentToolGateway(session)).run(
                AgentRunRequest(
                    sessionId = AgentSessionId("session.${case.worldId}"),
                    identity = AgentIdentity(
                        AgentId("agent.${case.worldId}"),
                        ActorId("npc.${case.worldId}"),
                        setOf(CommandPermission.RESOLVE_CHECK),
                    ),
                    input = "执行世界配置的检定",
                    systemPrompt = "使用清单允许的工具。",
                ),
            )

            assertIs<AgentRunResult.Completed>(result)
            assertTrue(provider.requests.first().tools.any { it.name == RESOLVE_CHECK_TOOL_ID.value })
            val ready = assertIs<GameSessionUiState.Ready>(session.state.value)
            assertEquals(6, ready.presentation.lastSequence)
            assertTrue(ready.presentation.timeline.isNotEmpty())
        }
    }

    private fun loadPackage(directory: String): WorldPackageSource = WorldPackageSource(
        manifestJson = resourceText("$directory/manifest.json"),
        files = mapOf(
            "world.json" to resourceText("$directory/world.json"),
            "playable-world.json" to resourceText("$directory/playable-world.json"),
            "character-profile.json" to resourceText("$directory/character-profile.json"),
        ),
    )

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "Missing test resource: $path" }
            .bufferedReader()
            .use { it.readText() }

    private data class ContractCase(
        val worldId: String,
        val profileId: String,
    )

    private class ContractProvider(
        private val profileId: String,
    ) : LanguageModelProvider {
        override val capabilities: ProviderCapabilities = ProviderCapabilities(true, false, false)
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun complete(request: ProviderRequest): ProviderResult {
            requests += request
            return when (requests.size) {
                1 -> ProviderResult.Success(
                    ProviderTurn(
                        toolCalls = listOf(
                            ProviderToolCall(
                                id = "check-call",
                                name = RESOLVE_CHECK_TOOL_ID.value,
                                arguments = JsonObject(mapOf("profileId" to JsonPrimitive(profileId))),
                            ),
                        ),
                        usage = ProviderUsage(4, 2),
                    ),
                )

                2 -> ProviderResult.Success(ProviderTurn("完成。", usage = ProviderUsage(3, 1)))
                else -> ProviderResult.Failure(
                    ProviderFailureCode.INVALID_RESPONSE,
                    "Unexpected provider call",
                    retryable = false,
                )
            }
        }
    }
}
