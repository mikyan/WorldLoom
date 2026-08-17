package io.worldloom.world

import io.worldloom.definition.ValidatedWorldDefinition

sealed interface ReplayResult {
    data class Success(val state: GameState) : ReplayResult

    data class Failure(
        val eventId: EventId,
        val error: StateReductionError,
    ) : ReplayResult
}

object EventReplayer {
    fun replay(
        initialState: GameState,
        definition: ValidatedWorldDefinition,
        events: List<EventEnvelope>,
    ): ReplayResult {
        var state = initialState
        for (event in events.sortedBy { it.sequence }) {
            when (val result = StateReducer.reduce(state, definition, event)) {
                is StateReductionResult.Success -> state = result.state
                is StateReductionResult.Failure -> return ReplayResult.Failure(event.eventId, result.error)
            }
        }
        return ReplayResult.Success(state)
    }
}
