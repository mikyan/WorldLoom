package io.worldloom.agent.runtime

import io.worldloom.world.RunId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostedTurnHistoryTest {
    @Test
    fun inMemoryHistoryIsPagedInDisplayOrderAndIsolatedByRun() = runTest {
        val store = InMemoryGameTurnStore()
        val run = RunId("run.history")
        val otherRun = RunId("run.other")
        repeat(3) { index ->
            assertIs<GameTurnStoreResult.Success>(
                store.save(completedTurn(run, index + 1), expectedRevision = null),
            )
        }
        assertIs<GameTurnStoreResult.Success>(store.save(completedTurn(otherRun, 1), expectedRevision = null))

        val newest = assertIs<GameTurnHistoryResult.Success>(store.history(run, limit = 2)).page
        assertEquals(listOf(2L, 3L), newest.entries.map(GameTurnHistoryEntry::ordinal))
        assertTrue(newest.hasEarlier)
        assertEquals("input-3", assertIs<GameTurn>(store.latest(run)).input)

        val earlier = assertIs<GameTurnHistoryResult.Success>(
            store.history(run, beforeOrdinalExclusive = 2, limit = 2),
        ).page
        assertEquals(listOf(1L), earlier.entries.map(GameTurnHistoryEntry::ordinal))
        assertFalse(earlier.hasEarlier)
        assertIs<GameTurnHistoryResult.Failure>(store.history(run, limit = 0))
    }

    @Test
    fun projectorDropsFutureEvidenceAndNeverPublishesRawFailureText() {
        val run = RunId("run.projection")
        val safe = completedTurn(run, 1)
        val future = completedTurn(run, 2).copy(
            acceptedSequence = 10,
            deliveredSequence = 12,
            evidenceFromSequenceExclusive = 10,
            evidenceThroughSequenceInclusive = 12,
        )
        val failed = GameTurn(
            runId = run,
            turnId = TurnId("run.projection.turn.3"),
            input = "retry",
            status = GameTurnStatus.FAILED,
            revision = 0,
            acceptedSequence = 4,
            error = "raw provider body must remain private",
            outputKind = GameTurnOutputKind.FAILURE,
            errorCode = GameTurnErrorCode.PROVIDER_FAILURE,
        )
        val source = GameTurnHistoryResult.Success(
            GameTurnHistoryPage(
                listOf(
                    GameTurnHistoryEntry(1, turn = safe),
                    GameTurnHistoryEntry(2, turn = future),
                    GameTurnHistoryEntry(3, turn = failed),
                ),
                hasEarlier = false,
            ),
        )

        val page = assertIs<HostedTurnHistoryResult.Success>(
            HostedTurnHistoryProjector.project(source, currentEventSequence = 5),
        ).page

        assertEquals(listOf(safe.turnId, failed.turnId), page.items.map(HostedTurnHistoryItem::turnId))
        assertEquals(1, page.issues.size)
        assertEquals(GameTurnHistoryProblemCode.FUTURE_EVIDENCE, page.issues.single().code)
        assertEquals("主持服务暂时不可用。", page.items.last().safeFailureMessage)
        assertNull(page.items.last().publicOutput)
        assertFalse(page.items.last().safeFailureMessage.orEmpty().contains("raw provider"))
    }

    @Test
    fun legacyTurnMigratesToCurrentPublicMetadata() {
        val legacy = GameTurn(
            schemaVersion = LEGACY_GM_TURN_SCHEMA_VERSION,
            runId = RunId("run.legacy"),
            turnId = TurnId("run.legacy.turn.1"),
            input = "look",
            status = GameTurnStatus.COMPLETED,
            revision = 1,
            acceptedSequence = 2,
            deliveredSequence = 4,
            output = "visible narration",
            worldChanged = true,
        )

        val current = legacy.toCurrentSchema()

        assertEquals(CURRENT_GM_TURN_SCHEMA_VERSION, current.schemaVersion)
        assertEquals(GameTurnOutputKind.NARRATION, current.outputKind)
        assertEquals(2, current.evidenceFromSequenceExclusive)
        assertEquals(4, current.evidenceThroughSequenceInclusive)
    }

    private fun completedTurn(runId: RunId, ordinal: Int) = GameTurn(
        runId = runId,
        turnId = TurnId("${runId.value}.turn.$ordinal"),
        input = "input-$ordinal",
        status = GameTurnStatus.COMPLETED,
        revision = 0,
        acceptedSequence = ordinal.toLong(),
        deliveredSequence = ordinal.toLong(),
        output = "narration-$ordinal",
        outputKind = GameTurnOutputKind.NARRATION,
    )
}
