package io.worldloom.agent.runtime

import io.worldloom.application.DefaultGameSession
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.LoadResult
import io.worldloom.application.SequentialSessionIdSource
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.WorldPackageSource
import io.worldloom.definition.BooleanValue
import io.worldloom.definition.CheckOutcomeDefinition
import io.worldloom.definition.CheckProfileDefinition
import io.worldloom.definition.CheckResolutionMode
import io.worldloom.definition.ComponentDefinition
import io.worldloom.definition.ComponentSeed
import io.worldloom.definition.CURRENT_WORLD_DEFINITION_SCHEMA_VERSION
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.EntitySeed
import io.worldloom.definition.FieldDefinition
import io.worldloom.definition.FieldSeed
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.PresentationCheckDefinition
import io.worldloom.definition.PresentationFieldDefinition
import io.worldloom.definition.ValueType
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.WorldDefinitionCodec
import io.worldloom.provider.api.LanguageModelProvider
import io.worldloom.provider.api.ProviderCapabilities
import io.worldloom.provider.api.ProviderFailureCode
import io.worldloom.provider.api.ProviderMessage
import io.worldloom.provider.api.ProviderMessageRole
import io.worldloom.provider.api.ProviderRequest
import io.worldloom.provider.api.ProviderResult
import io.worldloom.provider.api.ProviderToolCall
import io.worldloom.provider.api.ProviderTurn
import io.worldloom.provider.api.ProviderUsage
import io.worldloom.rules.module.api.CURRENT_RULE_MODULE_API_VERSION
import io.worldloom.rules.module.api.CURRENT_WORLD_MANIFEST_SCHEMA_VERSION
import io.worldloom.rules.module.api.ModuleVersion
import io.worldloom.rules.module.api.WorldManifest
import io.worldloom.rules.module.api.WorldManifestCodec
import io.worldloom.rules.module.api.WorldModuleSelection
import io.worldloom.world.ActorId
import io.worldloom.world.CommandPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentRuntimeTest {
    @Test
    fun fakeAgentToolCallTravelsThroughCommandEventAndPresentation() = runTest {
        val fixture = fixture()
        val provider = ScriptedProvider(
            success(toolCall(NUMERIC_ADJUST_TOOL_ID.value, validAdjustmentArguments(), "call-1")),
            success("能源已调整。"),
        )
        val runtime = runtime(fixture, provider)

        val result = runtime.run(request(identity(CommandPermission.ADJUST_NUMERIC_COMPONENT)))

        val completed = assertIs<AgentRunResult.Completed>(result)
        assertEquals("能源已调整。", completed.text)
        assertEquals(1, completed.toolCalls)
        assertTrue(provider.requests[1].messages.any { it.role == ProviderMessageRole.TOOL })
        val ready = fixture.ready()
        assertEquals(70, ready.presentation.fields.single().value)
        assertEquals(1, ready.presentation.lastSequence)
        assertEquals(1, ready.presentation.timeline.size)
    }

    @Test
    fun configuredCheckToolUsesTheSameAuthoritativePipeline() = runTest {
        val fixture = fixture()
        val provider = ScriptedProvider(
            success(
                toolCall(
                    RESOLVE_CHECK_TOOL_ID.value,
                    JsonObject(mapOf("profileId" to JsonPrimitive("test.check.integrity"))),
                    "check-1",
                ),
            ),
            success("检定完成。"),
        )
        val runtime = runtime(fixture, provider)

        assertIs<AgentRunResult.Completed>(runtime.run(request(identity(CommandPermission.RESOLVE_CHECK))))

        val ready = fixture.ready()
        assertEquals(1, ready.presentation.lastSequence)
        assertTrue(ready.presentation.timeline.single().summary.contains("系统稳定"))
    }

    @Test
    fun invalidArgumentsAreRejectedBeforeAnyEventIsAppended() = runTest {
        val fixture = fixture()
        val provider = ScriptedProvider(
            success(
                toolCall(
                    NUMERIC_ADJUST_TOOL_ID.value,
                    JsonObject(validAdjustmentArguments().filterKeys { it != "delta" }),
                    "bad-args",
                ),
            ),
        )

        val failure = assertIs<AgentRunResult.Failure>(
            runtime(fixture, provider).run(request(identity(CommandPermission.ADJUST_NUMERIC_COMPONENT))),
        )

        assertEquals(AgentRunErrorCode.TOOL_REJECTED, failure.error.code)
        assertFalse(failure.error.worldChanged)
        assertEquals(0, fixture.ready().presentation.lastSequence)
    }

    @Test
    fun permissionAndManifestBothConstrainToolCalls() = runTest {
        val unauthorized = fixture()
        val unauthorizedProvider = ScriptedProvider(
            success(toolCall(NUMERIC_ADJUST_TOOL_ID.value, validAdjustmentArguments(), "unauthorized")),
        )
        val permissionFailure = assertIs<AgentRunResult.Failure>(
            runtime(unauthorized, unauthorizedProvider).run(request(identity())),
        )
        assertEquals(AgentRunErrorCode.TOOL_REJECTED, permissionFailure.error.code)
        assertTrue(unauthorizedProvider.requests.single().tools.none { it.name == NUMERIC_ADJUST_TOOL_ID.value })
        assertEquals(0, unauthorized.ready().presentation.lastSequence)

        val disabled = fixture(directAdjustment = false)
        val disabledProvider = ScriptedProvider(
            success(toolCall(NUMERIC_ADJUST_TOOL_ID.value, validAdjustmentArguments(), "disabled")),
        )
        val manifestFailure = assertIs<AgentRunResult.Failure>(
            runtime(disabled, disabledProvider).run(request(identity(CommandPermission.ADJUST_NUMERIC_COMPONENT))),
        )
        assertEquals(AgentRunErrorCode.TOOL_REJECTED, manifestFailure.error.code)
        assertTrue(disabledProvider.requests.single().tools.none { it.name == NUMERIC_ADJUST_TOOL_ID.value })
        assertEquals(0, disabled.ready().presentation.lastSequence)
    }

    @Test
    fun repeatedToolSignatureIsStoppedBeforeTheSecondMutation() = runTest {
        val fixture = fixture()
        val arguments = validAdjustmentArguments()
        val provider = ScriptedProvider(
            success(toolCall(NUMERIC_ADJUST_TOOL_ID.value, arguments, "loop-1")),
            success(toolCall(NUMERIC_ADJUST_TOOL_ID.value, arguments, "loop-2")),
        )

        val failure = assertIs<AgentRunResult.Failure>(
            runtime(fixture, provider).run(request(identity(CommandPermission.ADJUST_NUMERIC_COMPONENT))),
        )

        assertEquals(AgentRunErrorCode.TOOL_LOOP_DETECTED, failure.error.code)
        assertTrue(failure.error.worldChanged)
        assertEquals(1, fixture.ready().presentation.lastSequence)
        assertEquals(70, fixture.ready().presentation.fields.single().value)
    }

    @Test
    fun stepAndCostBudgetsStopTheLoopAtDeterministicBoundaries() = runTest {
        val stepFixture = fixture()
        val stepProvider = ScriptedProvider(
            success(toolCall(NUMERIC_ADJUST_TOOL_ID.value, validAdjustmentArguments(), "step-1")),
        )
        val stepFailure = assertIs<AgentRunResult.Failure>(
            runtime(stepFixture, stepProvider, AgentRunPolicy(maxSteps = 1))
                .run(request(identity(CommandPermission.ADJUST_NUMERIC_COMPONENT))),
        )
        assertEquals(AgentRunErrorCode.STEP_LIMIT_EXCEEDED, stepFailure.error.code)
        assertTrue(stepFailure.error.worldChanged)
        assertEquals(1, stepFixture.ready().presentation.lastSequence)

        val costFixture = fixture()
        val costProvider = ScriptedProvider(
            ProviderResult.Success(
                ProviderTurn(
                    toolCalls = listOf(
                        toolCall(NUMERIC_ADJUST_TOOL_ID.value, validAdjustmentArguments(), "cost-1"),
                    ),
                    usage = ProviderUsage(1, 1, 11),
                ),
            ),
        )
        val costFailure = assertIs<AgentRunResult.Failure>(
            runtime(costFixture, costProvider, AgentRunPolicy(maxCostMicrounits = 10))
                .run(request(identity(CommandPermission.ADJUST_NUMERIC_COMPONENT))),
        )
        assertEquals(AgentRunErrorCode.COST_BUDGET_EXCEEDED, costFailure.error.code)
        assertFalse(costFailure.error.worldChanged)
        assertEquals(0, costFixture.ready().presentation.lastSequence)
    }

    @Test
    fun privateSessionHistoryIsBoundToOneAgentIdentity() = runTest {
        val fixture = fixture()
        val provider = ScriptedProvider(success("记住了。"), success("新的会话。"))
        val store = InMemoryAgentSessionStore()
        val runtime = runtime(fixture, provider, store = store)
        val ownerA = identity(agentId = "agent.a", actorId = "npc.a")
        val ownerB = identity(agentId = "agent.b", actorId = "npc.b")

        assertIs<AgentRunResult.Completed>(
            runtime.run(request(ownerA, sessionId = "session.a", input = "private-memory-a")),
        )
        assertIs<AgentRunResult.Completed>(
            runtime.run(request(ownerB, sessionId = "session.b", input = "public-input-b")),
        )
        val secondPrompt = provider.requests[1].messages.mapNotNull(ProviderMessage::content).joinToString("\n")
        assertFalse("private-memory-a" in secondPrompt)

        val mismatch = assertIs<AgentRunResult.Failure>(
            runtime.run(request(ownerB, sessionId = "session.a", input = "steal-memory")),
        )
        assertEquals(AgentRunErrorCode.SESSION_OWNERSHIP_MISMATCH, mismatch.error.code)
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun providerOutageRateLimitAndTimeoutBeforeToolsLeaveTheWorldUntouched() = runTest {
        listOf(ProviderFailureCode.UNAVAILABLE, ProviderFailureCode.RATE_LIMITED).forEach { failureCode ->
            val failedFixture = fixture()
            val failedProvider = ScriptedProvider(
                ProviderResult.Failure(failureCode, "provider unavailable", retryable = true),
            )
            val providerFailure = assertIs<AgentRunResult.Failure>(
                runtime(failedFixture, failedProvider).run(request(identity(CommandPermission.ADJUST_NUMERIC_COMPONENT))),
            )
            assertEquals(AgentRunErrorCode.PROVIDER_FAILURE, providerFailure.error.code)
            assertFalse(providerFailure.error.worldChanged)
            assertEquals(0, failedFixture.ready().presentation.lastSequence)
        }

        val timeoutFixture = fixture()
        val timeoutRuntime = AgentRuntime(
            provider = DelayedProvider,
            toolGateway = DefaultAgentToolGateway(timeoutFixture.session),
            policy = AgentRunPolicy(timeoutMillis = 100),
        )
        val timeout = assertIs<AgentRunResult.Failure>(
            timeoutRuntime.run(request(identity(CommandPermission.ADJUST_NUMERIC_COMPONENT))),
        )
        assertEquals(AgentRunErrorCode.TIMEOUT, timeout.error.code)
        assertEquals(0, timeoutFixture.ready().presentation.lastSequence)
    }

    private suspend fun TestScope.fixture(directAdjustment: Boolean = true): SessionFixture {
        val catalog = testCatalog(directAdjustment)
        val session = DefaultGameSession(
            catalog = catalog,
            idSource = SequentialSessionIdSource("agent-test"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
        assertIs<LoadResult.Success>(session.load(catalog.entries.single().id))
        return SessionFixture(session)
    }

    private fun runtime(
        fixture: SessionFixture,
        provider: LanguageModelProvider,
        policy: AgentRunPolicy = AgentRunPolicy(),
        store: AgentSessionStore = InMemoryAgentSessionStore(),
    ): AgentRuntime = AgentRuntime(provider, DefaultAgentToolGateway(fixture.session), store, policy)

    private fun request(
        identity: AgentIdentity,
        sessionId: String = "session.test",
        input: String = "推进世界",
    ): AgentRunRequest = AgentRunRequest(
        sessionId = AgentSessionId(sessionId),
        identity = identity,
        input = input,
        systemPrompt = "你是一个受工具权限约束的测试 Agent。",
    )

    private fun identity(
        vararg permissions: CommandPermission,
        agentId: String = "agent.test",
        actorId: String = "npc.test",
    ): AgentIdentity = AgentIdentity(AgentId(agentId), ActorId(actorId), permissions.toSet())

    private fun validAdjustmentArguments(): JsonObject = JsonObject(
        mapOf(
            "entityId" to JsonPrimitive("player-ai"),
            "componentId" to JsonPrimitive("test.capacity"),
            "fieldId" to JsonPrimitive("test.energy"),
            "delta" to JsonPrimitive(-10),
        ),
    )

    private fun toolCall(
        name: String,
        arguments: JsonObject,
        id: String,
    ): ProviderToolCall = ProviderToolCall(id, name, arguments)

    private fun success(text: String): ProviderResult = ProviderResult.Success(
        ProviderTurn(text = text, usage = ProviderUsage(4, 2)),
    )

    private fun success(call: ProviderToolCall): ProviderResult = ProviderResult.Success(
        ProviderTurn(toolCalls = listOf(call), usage = ProviderUsage(4, 2)),
    )

    private fun testCatalog(directAdjustment: Boolean): StaticWorldCatalog {
        val definition = WorldDefinition(
            schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
            id = DefinitionId("contract.agent-test"),
            title = "Agent Test World",
            components = listOf(
                ComponentDefinition(
                    DefinitionId("test.capacity"),
                    listOf(
                        FieldDefinition(
                            DefinitionId("test.energy"),
                            ValueType.INTEGER,
                            minInteger = 0,
                            maxInteger = 100,
                        ),
                    ),
                ),
            ),
            initialEntities = listOf(
                EntitySeed(
                    "player-ai",
                    listOf(
                        ComponentSeed(
                            DefinitionId("test.capacity"),
                            listOf(FieldSeed(DefinitionId("test.energy"), IntegerValue(80))),
                        ),
                    ),
                ),
            ),
            checkProfiles = listOf(
                CheckProfileDefinition(
                    id = DefinitionId("test.check.integrity"),
                    label = "完整性检定",
                    mode = CheckResolutionMode.DETERMINISTIC,
                    baseValue = 8,
                    outcomes = listOf(
                        CheckOutcomeDefinition(DefinitionId("test.outcome.stable"), "系统稳定", 8),
                        CheckOutcomeDefinition(DefinitionId("test.outcome.degraded"), "系统降级", -1000),
                    ),
                ),
            ),
            presentation = listOf(
                PresentationFieldDefinition(
                    id = DefinitionId("test.presentation.energy"),
                    entityId = "player-ai",
                    componentId = DefinitionId("test.capacity"),
                    fieldId = DefinitionId("test.energy"),
                    label = "能源储备",
                    adjustmentStep = -10,
                ),
            ),
            presentationChecks = listOf(
                PresentationCheckDefinition(
                    id = DefinitionId("test.presentation.check"),
                    checkProfileId = DefinitionId("test.check.integrity"),
                    label = "运行检定",
                ),
            ),
        )
        val manifest = WorldManifest(
            schemaVersion = CURRENT_WORLD_MANIFEST_SCHEMA_VERSION,
            runtimeApiVersion = CURRENT_RULE_MODULE_API_VERSION,
            worldId = definition.id,
            worldDefinitionPath = "world.json",
            modules = listOf(
                WorldModuleSelection(
                    id = DefinitionId("worldloom.core.numeric-state"),
                    version = ModuleVersion(1, 0, 0),
                    parameters = mapOf(
                        DefinitionId("worldloom.parameter.direct-adjustment") to BooleanValue(directAdjustment),
                    ),
                ),
                WorldModuleSelection(
                    id = DefinitionId("worldloom.rules.deterministic-check"),
                    version = ModuleVersion(1, 0, 0),
                ),
            ),
        )
        return assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(
                listOf(
                    WorldPackageSource(
                        manifestJson = WorldManifestCodec.encode(manifest),
                        files = mapOf("world.json" to WorldDefinitionCodec.encode(definition)),
                    ),
                ),
            ),
        ).catalog
    }

    private data class SessionFixture(val session: DefaultGameSession) {
        fun ready(): GameSessionUiState.Ready = assertIs(session.state.value)
    }

    private class ScriptedProvider(
        vararg scripted: ProviderResult,
    ) : LanguageModelProvider {
        override val capabilities: ProviderCapabilities = ProviderCapabilities(
            toolCalling = true,
            streaming = false,
            structuredOutput = false,
        )
        val requests = mutableListOf<ProviderRequest>()
        private val responses = scripted.toList()

        override suspend fun complete(request: ProviderRequest): ProviderResult {
            requests += request
            return responses.getOrElse(requests.lastIndex) {
                ProviderResult.Failure(ProviderFailureCode.INVALID_RESPONSE, "Script exhausted", retryable = false)
            }
        }
    }

    private data object DelayedProvider : LanguageModelProvider {
        override val capabilities: ProviderCapabilities = ProviderCapabilities(
            toolCalling = true,
            streaming = false,
            structuredOutput = false,
        )

        override suspend fun complete(request: ProviderRequest): ProviderResult {
            delay(1_000)
            return ProviderResult.Success(ProviderTurn("late", usage = ProviderUsage(1, 1)))
        }
    }
}
