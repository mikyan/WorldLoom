package io.worldloom.world

object WorldEngine {
    fun requiredEventCount(command: ValidatedCommand): Int = when (command) {
        is ValidatedCommand.AdjustNumericComponent,
        is ValidatedCommand.ChangeRunLifecycle,
        is ValidatedCommand.PublishNpcAction,
        -> 1
        is ValidatedCommand.CreatePlayerCharacter -> command.payload.entity.components.size + 3
        is ValidatedCommand.ApplyActionOutcome -> 1 +
            (if (command.payload.nextSceneId != null) 2 else 0) +
            (if (command.payload.endingId != null) 1 else 0)
    }

    /**
     * Produces facts from a validated command. Event identity is supplied explicitly so the
     * authoritative engine never consults system time, a UUID generator, or implicit randomness.
     */
    fun handle(
        command: ValidatedCommand,
        eventId: EventId,
    ): List<EventEnvelope> = handle(command, listOf(eventId))

    fun handle(
        command: ValidatedCommand,
        eventIds: List<EventId>,
    ): List<EventEnvelope> {
        require(eventIds.size == requiredEventCount(command)) {
            "Expected ${requiredEventCount(command)} event IDs, received ${eventIds.size}"
        }
        return when (command) {
            is ValidatedCommand.AdjustNumericComponent -> listOf(command.toEvent(eventIds.single()))
            is ValidatedCommand.ChangeRunLifecycle -> listOf(command.toEvent(eventIds.single()))
            is ValidatedCommand.CreatePlayerCharacter -> command.toEvents(eventIds)
            is ValidatedCommand.ApplyActionOutcome -> command.toEvents(eventIds)
            is ValidatedCommand.PublishNpcAction -> listOf(command.toEvent(eventIds.single()))
        }
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

    private fun ValidatedCommand.ChangeRunLifecycle.toEvent(eventId: EventId): EventEnvelope =
        event(
            eventId = eventId,
            sequenceOffset = 1,
            payload = RunLifecycleChangedEvent(previousLifecycle = previousLifecycle, lifecycle = payload.lifecycle),
        )

    private fun ValidatedCommand.PublishNpcAction.toEvent(eventId: EventId): EventEnvelope =
        event(
            eventId = eventId,
            sequenceOffset = 1,
            payload = NpcPublicActionPublishedEvent(
                entityId = payload.entityId,
                sceneId = payload.sceneId,
                kind = payload.kind,
                actionId = payload.actionId,
                content = payload.content,
            ),
        )

    private fun ValidatedCommand.CreatePlayerCharacter.toEvents(eventIds: List<EventId>): List<EventEnvelope> {
        val payloads = buildList {
            add(PlayerEntityCreatedEvent(entityId = entityId, profileId = payload.profileId))
            payload.entity.components
                .sortedBy { it.definitionId.value }
                .forEach { add(PlayerComponentInitializedEvent(entityId = entityId, component = it)) }
            add(
                PlayerEnteredInitialSceneEvent(
                    entityId = entityId,
                    sceneId = payload.initialSceneId,
                    participantIds = payload.initialSceneParticipantIds,
                ),
            )
            add(
                RunLifecycleChangedEvent(
                    previousLifecycle = RunLifecycle.CHARACTER_CREATION,
                    lifecycle = RunLifecycle.ACTIVE,
                ),
            )
        }
        return payloads.mapIndexed { index, eventPayload ->
            event(eventIds[index], index + 1L, eventPayload)
        }
    }

    private fun ValidatedCommand.event(
        eventId: EventId,
        sequenceOffset: Long,
        payload: GameEventPayload,
    ): EventEnvelope = EventEnvelope(
        schemaVersion = CURRENT_EVENT_SCHEMA_VERSION,
        eventId = eventId,
        runId = envelope.runId,
        sequence = envelope.expectedSequence + sequenceOffset,
        causationId = envelope.commandId,
        correlationId = envelope.correlationId ?: envelope.commandId.value,
        payload = payload,
    )

    private fun ValidatedCommand.ApplyActionOutcome.toEvents(eventIds: List<EventId>): List<EventEnvelope> {
        val payloads = buildList<GameEventPayload> {
            add(
                ActionOutcomeAppliedEvent(
                    actionId = payload.actionId,
                    outcomeId = payload.outcomeId,
                    objectiveIds = payload.objectiveIds,
                    endingId = payload.endingId,
                ),
            )
            payload.nextSceneId?.let { nextSceneId ->
                add(PlayerExitedSceneEvent(entityId = playerEntityId, sceneId = payload.fromSceneId))
                add(
                    PlayerEnteredSceneEvent(
                        entityId = playerEntityId,
                        sceneId = nextSceneId,
                        participantIds = payload.participantIds,
                    ),
                )
            }
            if (payload.endingId != null) {
                add(
                    RunLifecycleChangedEvent(
                        previousLifecycle = RunLifecycle.ACTIVE,
                        lifecycle = RunLifecycle.COMPLETED,
                    ),
                )
            }
        }
        return payloads.mapIndexed { index, payload -> event(eventIds[index], index + 1L, payload) }
    }
}
