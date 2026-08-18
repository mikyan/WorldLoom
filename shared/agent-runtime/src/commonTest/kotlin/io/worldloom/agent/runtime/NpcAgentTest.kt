package io.worldloom.agent.runtime

import io.worldloom.provider.api.LanguageModelProvider
import io.worldloom.provider.api.ProviderCapabilities
import io.worldloom.provider.api.ProviderRequest
import io.worldloom.provider.api.ProviderResult
import io.worldloom.provider.api.ProviderTurn
import io.worldloom.provider.api.ProviderUsage
import io.worldloom.world.ActorId
import io.worldloom.world.RunId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NpcAgentTest {
    @Test
    fun eachNpcReceivesOnlyItsOwnMemoryAndKeepsStableSession() = runTest {
        val provider = CapturingProvider()
        val runtime = AgentRuntime(provider, EmptyToolGateway(), InMemoryAgentSessionStore())
        val memoryStore = InMemoryAgentMemoryStore()
        val alpha = npc("alpha", runtime, memoryStore, NpcWakePolicy(alwaysWake = true))
        val beta = npc("beta", runtime, memoryStore, NpcWakePolicy(alwaysWake = true))
        memoryStore.upsertMemory(memory(alpha.profile.agentId, "alpha.secret", "Alpha knows the red code"))
        memoryStore.upsertMemory(memory(beta.profile.agentId, "beta.secret", "Beta knows the blue code"))
        val scheduler = NpcAgentScheduler(listOf(beta, alpha))

        val results = scheduler.dispatch(trigger("scene"), projector())

        assertEquals(listOf(AgentId("npc.alpha"), AgentId("npc.beta")), results.map { it.agentId() })
        assertTrue(provider.systemPrompts[0].contains("red code"))
        assertFalse(provider.systemPrompts[0].contains("blue code"))
        assertTrue(provider.systemPrompts[1].contains("blue code"))
        assertFalse(provider.systemPrompts[1].contains("red code"))
        assertEquals(1, memoryStore.turns(alpha.profile.agentId).size)
        assertEquals(1, memoryStore.turns(beta.profile.agentId).size)
    }

    @Test
    fun schedulerWakesOnlyRelevantNpc() = runTest {
        val provider = CapturingProvider()
        val runtime = AgentRuntime(provider, EmptyToolGateway())
        val store = InMemoryAgentMemoryStore()
        val alarmNpc = npc("alarm", runtime, store, NpcWakePolicy(triggerKinds = setOf("alarm")))
        val tradeNpc = npc("trade", runtime, store, NpcWakePolicy(tags = setOf("trade")))
        val scheduler = NpcAgentScheduler(listOf(tradeNpc, alarmNpc))

        val results = scheduler.dispatch(trigger("alarm"), projector())

        val response = assertIs<NpcAgentResult.Responded>(results.single())
        assertEquals(AgentId("npc.alarm"), response.agentId)
        assertEquals(1, provider.systemPrompts.size)
    }

    private fun npc(
        suffix: String,
        runtime: AgentRuntime,
        store: AgentMemoryStore,
        wakePolicy: NpcWakePolicy,
    ) = NpcAgent(
        profile = NpcAgentProfile(
            agentId = AgentId("npc.$suffix"),
            actorId = ActorId("actor.$suffix"),
            sessionId = AgentSessionId("session.$suffix"),
            identityPrompt = "You are $suffix",
            permissions = emptySet(),
            wakePolicy = wakePolicy,
            runId = RunId("run.$suffix"),
        ),
        runtime = runtime,
        memoryStore = store,
    )

    private fun memory(agentId: AgentId, id: String, content: String) = AgentMemoryRecord(
        id = AgentMemoryId(id),
        agentId = agentId,
        kind = AgentMemoryKind.BELIEF,
        content = content,
        salience = 1.0,
        confidence = 1.0,
        protected = true,
        createdSequence = 0,
    )

    private fun trigger(kind: String) = NpcTrigger(
        id = "trigger.$kind",
        kind = kind,
        input = "React to the event",
        sourceSequence = 1,
        sourceEventIds = setOf("event.$kind"),
    )

    private fun projector() = NpcPerceptionProjector { profile, _ ->
        NpcPerception(
            currentObservation = "${profile.agentId.value} sees the room",
            estimatedContextTokens = 100,
        )
    }
}

private class CapturingProvider : LanguageModelProvider {
    override val capabilities = ProviderCapabilities(
        toolCalling = false,
        streaming = false,
        structuredOutput = false,
    )
    val systemPrompts = mutableListOf<String>()

    override suspend fun complete(request: ProviderRequest): ProviderResult {
        systemPrompts += request.messages.first().content.orEmpty()
        return ProviderResult.Success(
            ProviderTurn(
                text = "NPC response ${systemPrompts.size}",
                usage = ProviderUsage(inputTokens = 20, outputTokens = 10),
            ),
        )
    }
}

private class EmptyToolGateway : AgentToolGateway {
    override suspend fun availableTools(identity: AgentIdentity) = emptyList<io.worldloom.provider.api.ProviderToolDefinition>()

    override suspend fun validate(
        identity: AgentIdentity,
        call: io.worldloom.provider.api.ProviderToolCall,
    ): ToolValidationResult = ToolValidationResult.Valid

    override suspend fun invoke(
        identity: AgentIdentity,
        call: io.worldloom.provider.api.ProviderToolCall,
    ): ToolInvocationResult = ToolInvocationResult.Success("ok", worldChanged = false)
}

private fun NpcAgentResult.agentId(): AgentId = when (this) {
    is NpcAgentResult.Responded -> agentId
    is NpcAgentResult.Failed -> agentId
}
