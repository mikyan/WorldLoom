package io.worldloom.application

import io.worldloom.world.CommandId
import io.worldloom.world.EventId
import io.worldloom.world.RunId

/** Supplies explicit identities without introducing time or implicit randomness into the engine. */
interface SessionIdSource {
    fun nextRunId(): RunId

    fun nextCommandId(): CommandId

    fun nextEventId(): EventId
}

class SequentialSessionIdSource(private val prefix: String = "local") : SessionIdSource {
    private var runSequence: Long = 0
    private var commandSequence: Long = 0
    private var eventSequence: Long = 0

    override fun nextRunId(): RunId = RunId("$prefix.run.${++runSequence}")

    override fun nextCommandId(): CommandId = CommandId("$prefix.command.${++commandSequence}")

    override fun nextEventId(): EventId = EventId("$prefix.event.${++eventSequence}")
}
