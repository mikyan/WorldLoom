package io.worldloom.application

enum class GuidanceTurnCompletion { COMPLETED, RUNNING, INTERRUPTED, PROVIDER_FAILED }

data class GuidanceTurnEvidence(
    val completion: GuidanceTurnCompletion,
    val authoritativeProgress: Boolean,
    val intentionalRoleplayOnly: Boolean = false,
)

/** Conservative public-evidence detector; it can only expose a hint affordance, never submit an action. */
object GuidanceStallDetector {
    fun shouldOfferNudge(turns: List<GuidanceTurnEvidence>): Boolean {
        val eligible = turns.asReversed().takeWhile { turn ->
            turn.completion == GuidanceTurnCompletion.COMPLETED && !turn.intentionalRoleplayOnly
        }.take(2)
        return eligible.size == 2 && eligible.none(GuidanceTurnEvidence::authoritativeProgress)
    }
}
