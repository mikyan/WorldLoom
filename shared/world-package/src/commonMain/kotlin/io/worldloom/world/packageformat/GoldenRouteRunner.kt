package io.worldloom.world.packageformat

import io.worldloom.definition.DefinitionId

/** A Fake Agent decision that an application adapter must execute through its public Tool/Command/Event path. */
data class GoldenRouteIntent(
    val routeId: DefinitionId,
    val stepIndex: Int,
    val actionId: DefinitionId,
    val selectedOutcomeId: DefinitionId?,
    val recordedRandomValues: List<Int>,
)

/** Audit receipt returned only after the application's authoritative event append succeeds. */
data class GoldenRouteStepReceipt(
    val actionId: DefinitionId,
    val toolId: DefinitionId,
    val commandId: String,
    val eventIds: List<String>,
    val lastEventSequence: Long,
    val endingId: DefinitionId? = null,
)

data class GoldenRouteReplayReceipt(
    val lastEventSequence: Long,
    val endingId: DefinitionId?,
)

/** Implemented by the application layer; the route runner itself has no state mutation capability. */
interface GoldenRouteDriver {
    suspend fun start(): Long

    suspend fun perform(intent: GoldenRouteIntent): GoldenRouteStepReceipt

    suspend fun replay(): GoldenRouteReplayReceipt
}

enum class GoldenRouteRunProblemCode {
    ROUTE_UNKNOWN,
    ROUTE_INVALID,
    ACTION_MISMATCH,
    AUTHORITATIVE_RECEIPT_MISSING,
    EVENT_SEQUENCE_NOT_ADVANCED,
    ENDING_MISMATCH,
    REPLAY_MISMATCH,
}

data class GoldenRouteRunProblem(
    val code: GoldenRouteRunProblemCode,
    val stepIndex: Int?,
    val message: String,
)

sealed interface GoldenRouteRunResult {
    data class Success(
        val routeId: DefinitionId,
        val endingId: DefinitionId,
        val receipts: List<GoldenRouteStepReceipt>,
    ) : GoldenRouteRunResult

    data class Failure(val problem: GoldenRouteRunProblem) : GoldenRouteRunResult
}

/** Runs deterministic Fake Agent choices while requiring typed Tool, Command, Event, and replay evidence from a driver. */
class GoldenRouteRunner(
    private val contract: ValidatedPlayableWorldContract,
    private val driver: GoldenRouteDriver,
) {
    suspend fun run(routeId: DefinitionId): GoldenRouteRunResult {
        val route = contract.route(routeId) ?: return failure(
            GoldenRouteRunProblemCode.ROUTE_UNKNOWN,
            null,
            "Unknown golden route: $routeId",
        )
        val simulation = contract.simulate(routeId)
        if (simulation is PlayableRouteSimulationResult.Failure) {
            return failure(GoldenRouteRunProblemCode.ROUTE_INVALID, null, simulation.problem.message)
        }

        var sequence = driver.start()
        val receipts = mutableListOf<GoldenRouteStepReceipt>()
        route.steps.forEachIndexed { index, step ->
            val receipt = driver.perform(
                GoldenRouteIntent(
                    routeId = routeId,
                    stepIndex = index,
                    actionId = step.actionId,
                    selectedOutcomeId = step.selectedOutcomeId,
                    recordedRandomValues = step.randomValues,
                ),
            )
            if (receipt.actionId != step.actionId) {
                return failure(
                    GoldenRouteRunProblemCode.ACTION_MISMATCH,
                    index,
                    "Driver committed ${receipt.actionId}, expected ${step.actionId}",
                )
            }
            if (receipt.commandId.isBlank() || receipt.eventIds.isEmpty() || receipt.eventIds.any(String::isBlank)) {
                return failure(
                    GoldenRouteRunProblemCode.AUTHORITATIVE_RECEIPT_MISSING,
                    index,
                    "Driver must return committed Tool, Command, and Event evidence",
                )
            }
            if (receipt.lastEventSequence <= sequence) {
                return failure(
                    GoldenRouteRunProblemCode.EVENT_SEQUENCE_NOT_ADVANCED,
                    index,
                    "Committed event sequence did not advance",
                )
            }
            sequence = receipt.lastEventSequence
            receipts += receipt
        }

        val endingId = receipts.lastOrNull()?.endingId
        if (endingId != route.expectedEndingId) {
            return failure(
                GoldenRouteRunProblemCode.ENDING_MISMATCH,
                route.steps.lastIndex,
                "Driver reached $endingId, expected ${route.expectedEndingId}",
            )
        }
        val replay = driver.replay()
        if (replay.lastEventSequence != sequence || replay.endingId != endingId) {
            return failure(
                GoldenRouteRunProblemCode.REPLAY_MISMATCH,
                null,
                "Replay did not reconstruct the committed route ending and sequence",
            )
        }
        return GoldenRouteRunResult.Success(routeId, endingId, receipts)
    }

    private fun failure(
        code: GoldenRouteRunProblemCode,
        stepIndex: Int?,
        message: String,
    ) = GoldenRouteRunResult.Failure(GoldenRouteRunProblem(code, stepIndex, message))
}
