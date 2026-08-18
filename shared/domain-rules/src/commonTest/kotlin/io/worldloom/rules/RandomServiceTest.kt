package io.worldloom.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RandomServiceTest {
    @Test
    fun sameSeedProducesSameBoundedAuditRecordsOnEveryRun() {
        val first = SeededRandomService(42)
        val second = SeededRandomService(42)
        val request = DiceRandomRequest(count = 100, sides = 6)

        val firstRecord = assertIs<RandomServiceResult.Success>(
            first.resolve(request, RandomRecordId("random.first")),
        ).record
        val secondRecord = assertIs<RandomServiceResult.Success>(
            second.resolve(request, RandomRecordId("random.second")),
        ).record

        assertEquals(firstRecord.results, secondRecord.results)
        assertTrue(firstRecord.results.all { it in 1..6 })
        assertEquals(RANDOM_ALGORITHM_VERSION, firstRecord.algorithmVersion)
    }

    @Test
    fun invalidDiceNeverConsumeAnImplicitFallback() {
        val result = assertIs<RandomServiceResult.Failure>(
            SeededRandomService(1).resolve(DiceRandomRequest(0, 6), RandomRecordId("random.invalid")),
        )

        assertEquals(RandomServiceErrorCode.INVALID_REQUEST, result.error.code)
    }
}
