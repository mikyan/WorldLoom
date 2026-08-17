package io.worldloom.world

object WorldEngine {
    /**
     * Produces facts from a validated command. Event identity is supplied explicitly so the
     * authoritative engine never consults system time, a UUID generator, or implicit randomness.
     */
    fun handle(
        command: ValidatedCommand,
        eventId: EventId,
    ): List<EventEnvelope> =
        when (command) {
            is ValidatedCommand.AdjustNumericComponent -> listOf(command.toEvent(eventId))
        }

    private fun ValidatedCommand.AdjustNumericComponent.toEvent(eventId: EventId): EventEnvelope =
        EventEnvelope(
            schemaVersion = CURRENT_EVENT_SCHEMA_VERSION,
            eventId = eventId,
            runId = envelope.runId,
            sequence = envelope.expectedSequence + 1,
            causationId = envelope.commandId,
            correlationId = envelope.correlationId ?: envelope.commandId.value,
            payload = NumericComponentAdjustedEvent(
                entityId = payload.entityId,
                componentId = payload.componentId,
                fieldId = payload.fieldId,
                previousValue = previousValue,
                delta = payload.delta,
                newValue = newValue,
            ),
        )
}
