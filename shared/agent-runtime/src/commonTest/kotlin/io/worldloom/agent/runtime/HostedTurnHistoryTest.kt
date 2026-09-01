package io.worldloom.agent.runtime

import io.worldloom.application.GamePresentation
import io.worldloom.application.GuidancePresentation
import io.worldloom.application.PresentedAction
import io.worldloom.application.PresentedScene
import io.worldloom.definition.DefinitionId
import io.worldloom.world.EntityId
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

    @Test
    fun previouslyStoredNarrationIsSanitizedWhenProjectedForChat() {
        val runId = RunId("run.old-narration")
        val page = HostedTurnHistoryPage(
            items = listOf(
                HostedTurnHistoryItem(
                    runId = runId,
                    turnId = TurnId("run.old-narration.turn.1"),
                    ordinal = 1,
                    acceptedSequence = 5,
                    status = GameTurnStatus.COMPLETED,
                    playerInput = "继续",
                    outputKind = GameTurnOutputKind.NARRATION,
                    publicOutput = "eventType: worldloom.event.action.outcome-applied\n执行 war.action.search-supplies。",
                    safeFailureMessage = null,
                    recoveryKind = GameTurnRecoveryKind.NONE,
                    evidence = null,
                ),
            ),
            issues = emptyList(),
            hasEarlier = false,
        )
        val presentation = GamePresentation(
            worldId = DefinitionId("contract.war-survival"),
            title = "灰烬中的车队",
            lastSequence = 5,
            fields = emptyList(),
            checks = emptyList(),
            timeline = emptyList(),
            scene = PresentedScene(
                id = DefinitionId("war.scene.ruins"),
                label = "钟楼废墟",
                participantIds = listOf(EntityId("player")),
                actions = listOf(PresentedAction(DefinitionId("war.action.search-supplies"), "搜查临街药房")),
            ),
            guidance = GuidancePresentation(),
        )

        val output = page.withPlayerFacingNarration(presentation).items.single().publicOutput.orEmpty()

        assertEquals("执行 搜查临街药房。", output)
        assertFalse(output.contains("eventType"))
        assertFalse(output.contains("worldloom.event"))
    }

    @Test
    fun recoveryScanClassifiesInterruptedTurnsOnceWithoutReplayingThem() = runTest {
        val store = InMemoryGameTurnStore()
        val run = RunId("run.recovery")
        val retrySafe = GameTurn(
            runId = run,
            turnId = TurnId("run.recovery.turn.1"),
            input = "look",
            status = GameTurnStatus.ACCEPTED,
            revision = 0,
            acceptedSequence = 4,
        )
        val narrationRequired = GameTurn(
            runId = run,
            turnId = TurnId("run.recovery.turn.2"),
            input = "travel",
            status = GameTurnStatus.RUNNING,
            revision = 1,
            acceptedSequence = 2,
        )
        assertIs<GameTurnStoreResult.Success>(store.save(retrySafe, null))
        assertIs<GameTurnStoreResult.Success>(store.save(narrationRequired, null))

        val first = assertIs<GameTurnRecoveryResult.Completed>(
            GameTurnRecoveryCoordinator(store).recover(run, currentEventSequence = 4),
        ).report
        assertEquals(2, first.recovered.size)
        assertEquals(GameTurnRecoveryKind.RETRY_SAFE, store.load(run, retrySafe.turnId)?.recoveryKind)
        val recoveredNarration = assertIs<GameTurn>(store.load(run, narrationRequired.turnId))
        assertEquals(GameTurnRecoveryKind.NARRATION_REQUIRED, recoveredNarration.recoveryKind)
        assertEquals(2, recoveredNarration.evidenceFromSequenceExclusive)
        assertEquals(4, recoveredNarration.evidenceThroughSequenceInclusive)

        val second = assertIs<GameTurnRecoveryResult.Completed>(
            GameTurnRecoveryCoordinator(store).recover(run, currentEventSequence = 4),
        ).report
        assertTrue(second.recovered.isEmpty())
        assertEquals(recoveredNarration.revision, store.load(run, narrationRequired.turnId)?.revision)
    }

    @Test
    fun recoveryAndProjectionQuarantineTurnAcceptedBeyondCurrentEventLog() = runTest {
        val store = InMemoryGameTurnStore()
        val run = RunId("run.future-acceptance")
        val future = GameTurn(
            runId = run,
            turnId = TurnId("run.future-acceptance.turn.1"),
            input = "continue",
            status = GameTurnStatus.RUNNING,
            revision = 1,
            acceptedSequence = 9,
        )
        assertIs<GameTurnStoreResult.Success>(store.save(future, null))

        val recovery = assertIs<GameTurnRecoveryResult.Completed>(
            GameTurnRecoveryCoordinator(store).recover(run, currentEventSequence = 4),
        ).report
        assertTrue(recovery.recovered.isEmpty())
        assertEquals(GameTurnStatus.RUNNING, store.load(run, future.turnId)?.status)

        val projected = assertIs<HostedTurnHistoryResult.Success>(
            HostedTurnHistoryProjector.project(store.history(run), currentEventSequence = 4),
        ).page
        assertTrue(projected.items.isEmpty())
        assertEquals(GameTurnHistoryProblemCode.FUTURE_EVIDENCE, projected.issues.single().code)
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
