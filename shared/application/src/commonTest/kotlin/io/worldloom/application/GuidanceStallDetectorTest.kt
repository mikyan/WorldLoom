package io.worldloom.application

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuidanceStallDetectorTest {
    @Test
    fun offersNudgeOnlyAfterTwoEligibleCompletedTurnsWithoutProgress() {
        assertTrue(
            GuidanceStallDetector.shouldOfferNudge(
                listOf(
                    GuidanceTurnEvidence(GuidanceTurnCompletion.COMPLETED, authoritativeProgress = false),
                    GuidanceTurnEvidence(GuidanceTurnCompletion.COMPLETED, authoritativeProgress = false),
                ),
            ),
        )
        assertFalse(
            GuidanceStallDetector.shouldOfferNudge(
                listOf(
                    GuidanceTurnEvidence(GuidanceTurnCompletion.COMPLETED, authoritativeProgress = false),
                    GuidanceTurnEvidence(GuidanceTurnCompletion.PROVIDER_FAILED, authoritativeProgress = false),
                ),
            ),
        )
        assertFalse(
            GuidanceStallDetector.shouldOfferNudge(
                listOf(
                    GuidanceTurnEvidence(GuidanceTurnCompletion.COMPLETED, authoritativeProgress = false),
                    GuidanceTurnEvidence(GuidanceTurnCompletion.COMPLETED, authoritativeProgress = false, intentionalRoleplayOnly = true),
                ),
            ),
        )
    }
}
