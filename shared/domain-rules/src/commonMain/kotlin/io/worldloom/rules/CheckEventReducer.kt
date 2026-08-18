package io.worldloom.rules

import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.world.CURRENT_EVENT_SCHEMA_VERSION
import io.worldloom.world.EventEnvelope
import io.worldloom.world.EventReducer
import io.worldloom.world.GameEventPayload
import io.worldloom.world.GameState
import io.worldloom.world.StateReductionError
import io.worldloom.world.StateReductionErrorCode
import io.worldloom.world.StateReductionResult

object CheckEventReducer : EventReducer {
    override fun supports(payload: GameEventPayload): Boolean = payload is CheckResolvedEvent

    override fun reduce(
        state: GameState,
        definition: ValidatedWorldDefinition,
        event: EventEnvelope,
    ): StateReductionResult {
        if (event.schemaVersion != CURRENT_EVENT_SCHEMA_VERSION) {
            return failure(StateReductionErrorCode.UNSUPPORTED_SCHEMA_VERSION, "schemaVersion", "Unsupported event schema")
        }
        if (event.runId != state.runId) {
            return failure(StateReductionErrorCode.RUN_MISMATCH, "runId", "Event run does not match state")
        }
        if (event.sequence != state.lastSequence + 1) {
            return failure(
                StateReductionErrorCode.SEQUENCE_MISMATCH,
                "sequence",
                "Event sequence must immediately follow state",
            )
        }
        val payload = event.payload as? CheckResolvedEvent
            ?: return failure(
                StateReductionErrorCode.UNSUPPORTED_EVENT_PAYLOAD,
                "payload",
                "Check reducer requires CheckResolvedEvent",
            )
        val profile = definition.checkProfile(payload.record.profileId)
            ?: return failure(StateReductionErrorCode.FIELD_NOT_FOUND, "payload.record.profileId", "Check profile not found")
        val verificationError = RuleEngine.verify(profile, payload.record)
        if (verificationError != null) {
            return failure(
                StateReductionErrorCode.INVALID_EVENT_ARITHMETIC,
                "payload.record",
                verificationError.message,
            )
        }
        return StateReductionResult.Success(state.copy(lastSequence = event.sequence))
    }

    private fun failure(
        code: StateReductionErrorCode,
        path: String,
        message: String,
    ): StateReductionResult.Failure =
        StateReductionResult.Failure(StateReductionError(code, path, message))
}
