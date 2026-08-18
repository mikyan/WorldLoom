package io.worldloom.rules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val RANDOM_ALGORITHM_VERSION: Int = 1

@Serializable
sealed interface RandomRequest

@Serializable
@SerialName("dice")
data class DiceRandomRequest(
    val count: Int,
    val sides: Int,
) : RandomRequest

@Serializable
data class RandomRecord(
    val id: RandomRecordId,
    val algorithmVersion: Int,
    val request: RandomRequest,
    val results: List<Int>,
)

enum class RandomServiceErrorCode {
    INVALID_REQUEST,
}

data class RandomServiceError(
    val code: RandomServiceErrorCode,
    val message: String,
)

sealed interface RandomServiceResult {
    data class Success(val record: RandomRecord) : RandomServiceResult

    data class Failure(val error: RandomServiceError) : RandomServiceResult
}

interface RandomService {
    fun resolve(
        request: RandomRequest,
        recordId: RandomRecordId,
    ): RandomServiceResult
}

sealed interface RandomRestoreResult {
    data object Success : RandomRestoreResult

    data class Failure(val message: String) : RandomRestoreResult
}

interface RestorableRandomService : RandomService {
    /** Replays audit requests only to restore generator position; stored results remain authoritative. */
    fun restore(records: List<RandomRecord>): RandomRestoreResult
}

/**
 * Versioned SplitMix64 source with bounded rejection sampling. The algorithm is implemented in
 * common Kotlin so identical seeds produce identical audit records on every platform.
 */
class SeededRandomService(seed: Long) : RestorableRandomService {
    private var state: ULong = seed.toULong()

    override fun resolve(
        request: RandomRequest,
        recordId: RandomRecordId,
    ): RandomServiceResult =
        when (request) {
            is DiceRandomRequest -> {
                if (request.count !in 1..100 || request.sides !in 2..1000) {
                    RandomServiceResult.Failure(
                        RandomServiceError(
                            RandomServiceErrorCode.INVALID_REQUEST,
                            "Dice request must use 1..100 dice with 2..1000 sides",
                        ),
                    )
                } else {
                    RandomServiceResult.Success(
                        RandomRecord(
                            id = recordId,
                            algorithmVersion = RANDOM_ALGORITHM_VERSION,
                            request = request,
                            results = List(request.count) { nextBounded(request.sides) + 1 },
                        ),
                    )
                }
            }
        }

    override fun restore(records: List<RandomRecord>): RandomRestoreResult {
        records.forEach { stored ->
            val regenerated = when (val result = resolve(stored.request, stored.id)) {
                is RandomServiceResult.Success -> result.record
                is RandomServiceResult.Failure -> return RandomRestoreResult.Failure(result.error.message)
            }
            if (regenerated != stored) {
                return RandomRestoreResult.Failure(
                    "Stored random audit record does not match the configured generator",
                )
            }
        }
        return RandomRestoreResult.Success
    }

    private fun nextBounded(bound: Int): Int {
        val domain = 1L shl 31
        val limit = domain - domain % bound
        while (true) {
            val candidate = (nextRaw() shr 33).toLong()
            if (candidate < limit) return (candidate % bound).toInt()
        }
    }

    private fun nextRaw(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var value = state
        value = (value xor (value shr 30)) * 0xBF58476D1CE4E5B9uL
        value = (value xor (value shr 27)) * 0x94D049BB133111EBuL
        return value xor (value shr 31)
    }
}
