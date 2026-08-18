package io.worldloom.agent.runtime

import io.worldloom.provider.api.ProviderMessageRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentMemoryTest {
    @Test
    fun contextUsesOnlyAgentsPrivateMemoryAndRanksRelevantRecords() = runTest {
        val store = InMemoryAgentMemoryStore()
        val agent = AgentId("npc.alpha")
        val other = AgentId("npc.beta")
        store.upsertMemory(memory(agent, "alpha.station", "The reactor is unstable", 0.8, setOf("reactor")))
        store.upsertMemory(memory(agent, "alpha.routine", "The corridor was quiet", 0.9, setOf("corridor")))
        store.upsertMemory(memory(other, "beta.secret", "The captain hid the key", 1.0, setOf("reactor")))
        repeat(3) { index -> store.appendTurn(turn(agent, index + 1L)) }

        val context = AgentContextBuilder(store).build(
            AgentContextRequest(
                agentId = agent,
                identity = "Station engineer",
                currentPerception = "Alarm lights around the reactor",
                relatedEntityIds = setOf("reactor"),
                tags = setOf("reactor"),
                estimatedContextTokens = 1_000,
                contextBudgetTokens = 10_000,
            ),
        )

        assertEquals("alpha.station", context.recalledMemories.first().id.value)
        assertTrue(context.systemPrompt.contains("reactor is unstable"))
        assertFalse(context.systemPrompt.contains("captain hid the key"))
        assertEquals(6, context.recentMessages.size)
        assertEquals(ProviderMessageRole.USER, context.recentMessages.first().role)
    }

    @Test
    fun hardWatermarkReducesRecallAndClipsLongRawTurns() = runTest {
        val store = InMemoryAgentMemoryStore()
        val agent = AgentId("npc.pressure")
        repeat(8) { index ->
            store.upsertMemory(memory(agent, "memory.$index", "memory $index", 1.0 - index * 0.05))
        }
        repeat(8) { index ->
            store.appendTurn(turn(agent, index + 1L, input = "x".repeat(1_000)))
        }

        val context = AgentContextBuilder(store).build(
            AgentContextRequest(
                agentId = agent,
                identity = "Identity",
                currentPerception = "Perception",
                estimatedContextTokens = 7_500,
                contextBudgetTokens = 10_000,
            ),
        )

        assertTrue(context.hardWatermarkApplied)
        assertEquals(4, context.recalledMemories.size)
        assertEquals(12, context.recentMessages.size)
        assertTrue(context.recentMessages.first().content.orEmpty().length <= 512)
    }

    @Test
    fun compactionFreezesRangeMergesOverlapAndPublishesAtomically() = runTest {
        val store = InMemoryAgentMemoryStore()
        val agent = AgentId("npc.compact")
        repeat(12) { index -> store.appendTurn(turn(agent, index + 1L)) }
        val gate = CompletableDeferred<Unit>()
        val model = RecordingCompactionModel(gate)
        val coordinator = AgentCompactionCoordinator(backgroundScope, store, model)

        val scheduled = coordinator.schedule(agent, 5_000, 10_000, false, 1, "compact-fast", 100)
        assertIs<CompactionScheduleResult.Scheduled>(scheduled)
        repeat(2) { index -> store.appendTurn(turn(agent, index + 13L)) }
        val merged = coordinator.schedule(agent, 5_000, 10_000, false, 1, "compact-fast", 101)
        assertIs<CompactionScheduleResult.Merged>(merged)
        assertNull(store.latestCheckpoint(agent))

        gate.complete(Unit)
        coordinator.awaitIdle(agent)

        val checkpoint = assertIs<ContextCheckpoint>(store.latestCheckpoint(agent))
        assertEquals(8, checkpoint.toSequence)
        assertEquals(listOf(1L..6L, 7L..8L), model.ranges)
        assertEquals(2, store.memories(agent).size)
    }

    @Test
    fun invalidCandidateNeverReplacesLastValidCheckpoint() = runTest {
        val store = InMemoryAgentMemoryStore()
        val agent = AgentId("npc.invalid")
        repeat(4) { index -> store.appendTurn(turn(agent, index + 1L)) }
        val model = object : ContextCompactionModel {
            override suspend fun compact(
                agentId: AgentId,
                turns: List<AgentTurnRecord>,
                promptVersion: Int,
            ) = CompactionModelOutput("invalid", emptyList(), 99, 100, emptySet())
        }
        val coordinator = AgentCompactionCoordinator(backgroundScope, store, model)

        coordinator.schedule(agent, 1_000, 10_000, true, 1, "compact-fast", 100)
        coordinator.awaitIdle(agent)

        assertNull(store.latestCheckpoint(agent))
    }

    @Test
    fun pressureUsesDocumentedWatermarksAndTurnThreshold() {
        val coordinator = AgentCompactionCoordinator(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            store = InMemoryAgentMemoryStore(),
            model = RecordingCompactionModel(CompletableDeferred(Unit)),
        )
        assertEquals(CompactionUrgency.NOT_NEEDED, coordinator.urgency(4_999, 10_000, 11, 1_999, false))
        assertEquals(CompactionUrgency.BACKGROUND, coordinator.urgency(5_000, 10_000, 1, 1, false))
        assertEquals(CompactionUrgency.BACKGROUND, coordinator.urgency(1, 10_000, 12, 1, false))
        assertEquals(CompactionUrgency.BACKGROUND, coordinator.urgency(1, 10_000, 4, 1, true))
        assertEquals(CompactionUrgency.HARD_WATERMARK, coordinator.urgency(7_500, 10_000, 1, 1, false))
    }

    private fun memory(
        agentId: AgentId,
        id: String,
        content: String,
        salience: Double,
        entities: Set<String> = emptySet(),
    ) = AgentMemoryRecord(
        id = AgentMemoryId(id),
        agentId = agentId,
        kind = AgentMemoryKind.EPISODIC,
        content = content,
        salience = salience,
        confidence = 1.0,
        relatedEntityIds = entities,
        createdSequence = 0,
    )

    private fun turn(
        agentId: AgentId,
        sequence: Long,
        input: String = "input $sequence",
    ) = AgentTurnRecord(
        agentId = agentId,
        sessionId = AgentSessionId("session.${agentId.value}"),
        sequence = sequence,
        input = input,
        output = "output $sequence",
        tokenCount = 200,
        sourceEventIds = setOf("event.$sequence"),
    )
}

private class RecordingCompactionModel(
    private val gate: CompletableDeferred<Unit>,
) : ContextCompactionModel {
    val ranges = mutableListOf<LongRange>()

    override suspend fun compact(
        agentId: AgentId,
        turns: List<AgentTurnRecord>,
        promptVersion: Int,
    ): CompactionModelOutput {
        gate.await()
        val range = turns.first().sequence..turns.last().sequence
        ranges += range
        return CompactionModelOutput(
            checkpointSummary = "Summary ${range.first}-${range.last}",
            memories = listOf(
                AgentMemoryRecord(
                    id = AgentMemoryId("memory.${agentId.value}.${range.first}"),
                    agentId = agentId,
                    kind = AgentMemoryKind.EPISODIC,
                    content = "Remembered ${range.first}-${range.last}",
                    salience = 0.8,
                    confidence = 0.9,
                    sourceEventIds = turns.flatMap(AgentTurnRecord::sourceEventIds).toSet(),
                    createdSequence = range.last,
                ),
            ),
            coveredFromSequence = range.first,
            coveredToSequence = range.last,
            sourceEventIds = turns.flatMap(AgentTurnRecord::sourceEventIds).toSet(),
        )
    }
}
