package io.worldloom.rules

import io.worldloom.definition.CheckProfileDefinition
import io.worldloom.definition.CheckResolutionMode
import io.worldloom.definition.DiceExpression
import io.worldloom.world.CURRENT_EVENT_SCHEMA_VERSION
import io.worldloom.world.EventEnvelope
import io.worldloom.world.EventId

enum class CheckResolutionErrorCode {
    RANDOM_SERVICE_REJECTED,
    RANDOM_RECORD_MISMATCH,
    INTEGER_OVERFLOW,
    NO_MATCHING_OUTCOME,
}

data class CheckResolutionError(
    val code: CheckResolutionErrorCode,
    val message: String,
)

sealed interface CheckResolutionResult {
    data class Success(val event: EventEnvelope) : CheckResolutionResult

    data class Failure(val error: CheckResolutionError) : CheckResolutionResult
}

object RuleEngine {
    fun resolve(
        command: ValidatedCheckCommand,
        eventId: EventId,
        checkId: CheckId,
        randomRecordId: RandomRecordId,
        randomService: RandomService,
    ): CheckResolutionResult {
        val randomRecord = when (command.profile.mode) {
            CheckResolutionMode.RANDOM -> {
                val dice = requireNotNull(command.profile.dice)
                when (
                    val random = randomService.resolve(
                        DiceRandomRequest(dice.count, dice.sides),
                        randomRecordId,
                    )
                ) {
                    is RandomServiceResult.Success -> random.record
                    is RandomServiceResult.Failure -> return failure(
                        CheckResolutionErrorCode.RANDOM_SERVICE_REJECTED,
                        random.error.message,
                    )
                }
            }

            CheckResolutionMode.DETERMINISTIC -> null
        }
        val record = when (val calculated = calculate(command.profile, command.payload.modifier, randomRecord, checkId)) {
            is CheckCalculationResult.Success -> calculated.record
            is CheckCalculationResult.Failure -> return CheckResolutionResult.Failure(calculated.error)
        }
        val envelope = command.envelope
        return CheckResolutionResult.Success(
            EventEnvelope(
                schemaVersion = CURRENT_EVENT_SCHEMA_VERSION,
                eventId = eventId,
                runId = envelope.runId,
                sequence = envelope.expectedSequence + 1,
                causationId = envelope.commandId,
                correlationId = envelope.correlationId ?: envelope.commandId.value,
                payload = CheckResolvedEvent(record),
            ),
        )
    }

    fun verify(
        profile: CheckProfileDefinition,
        record: CheckRecord,
    ): CheckResolutionError? {
        val calculated = calculate(profile, record.modifier, record.randomRecord, record.checkId)
        return when (calculated) {
            is CheckCalculationResult.Failure -> calculated.error
            is CheckCalculationResult.Success -> if (calculated.record != record) {
                CheckResolutionError(
                    CheckResolutionErrorCode.RANDOM_RECORD_MISMATCH,
                    "Check record does not match its profile and random audit record",
                )
            } else {
                null
            }
        }
    }

    private fun calculate(
        profile: CheckProfileDefinition,
        modifier: Long,
        randomRecord: RandomRecord?,
        checkId: CheckId,
    ): CheckCalculationResult {
        val randomTotal = when (profile.mode) {
            CheckResolutionMode.RANDOM -> {
                val dice = requireNotNull(profile.dice)
                val error = validateRandomRecord(randomRecord, dice)
                if (error != null) return CheckCalculationResult.Failure(error)
                requireNotNull(randomRecord).results.sumOf { it.toLong() }
            }

            CheckResolutionMode.DETERMINISTIC -> {
                if (randomRecord != null) {
                    return CheckCalculationResult.Failure(
                        CheckResolutionError(
                            CheckResolutionErrorCode.RANDOM_RECORD_MISMATCH,
                            "Deterministic check must not contain a random record",
                        ),
                    )
                }
                0L
            }
        }
        val withModifier = safeAdd(profile.baseValue, modifier)
            ?: return CheckCalculationResult.Failure(
                CheckResolutionError(CheckResolutionErrorCode.INTEGER_OVERFLOW, "Check modifier overflowed"),
            )
        val total = safeAdd(withModifier, randomTotal)
            ?: return CheckCalculationResult.Failure(
                CheckResolutionError(CheckResolutionErrorCode.INTEGER_OVERFLOW, "Check total overflowed"),
            )
        val outcome = profile.outcomes.sortedByDescending { it.minimumTotal }.firstOrNull { total >= it.minimumTotal }
            ?: return CheckCalculationResult.Failure(
                CheckResolutionError(
                    CheckResolutionErrorCode.NO_MATCHING_OUTCOME,
                    "No check outcome accepts total $total",
                ),
            )
        return CheckCalculationResult.Success(
            CheckRecord(
                checkId = checkId,
                profileId = profile.id,
                baseValue = profile.baseValue,
                modifier = modifier,
                randomRecord = randomRecord,
                total = total,
                outcomeId = outcome.id,
            ),
        )
    }

    private fun validateRandomRecord(
        record: RandomRecord?,
        dice: DiceExpression,
    ): CheckResolutionError? {
        val request = record?.request as? DiceRandomRequest
        val valid = record != null &&
            record.algorithmVersion == RANDOM_ALGORITHM_VERSION &&
            request == DiceRandomRequest(dice.count, dice.sides) &&
            record.results.size == dice.count &&
            record.results.all { it in 1..dice.sides }
        return if (valid) {
            null
        } else {
            CheckResolutionError(
                CheckResolutionErrorCode.RANDOM_RECORD_MISMATCH,
                "Random record does not match the configured dice request",
            )
        }
    }

    private fun safeAdd(left: Long, right: Long): Long? =
        if ((right > 0 && left > Long.MAX_VALUE - right) || (right < 0 && left < Long.MIN_VALUE - right)) {
            null
        } else {
            left + right
        }

    private fun failure(
        code: CheckResolutionErrorCode,
        message: String,
    ): CheckResolutionResult.Failure = CheckResolutionResult.Failure(CheckResolutionError(code, message))

    private sealed interface CheckCalculationResult {
        data class Success(val record: CheckRecord) : CheckCalculationResult

        data class Failure(val error: CheckResolutionError) : CheckCalculationResult
    }
}
