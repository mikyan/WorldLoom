package io.worldloom.application

import io.worldloom.world.CommandId
import io.worldloom.world.EventId
import io.worldloom.world.RunId
import io.worldloom.rules.CheckId
import io.worldloom.rules.RandomRecordId

/** Supplies explicit identities without introducing time or implicit randomness into the engine. */
interface SessionIdSource {
    fun nextRunId(): RunId

    fun nextCommandId(): CommandId

    fun nextEventId(): EventId

    fun nextCheckId(): CheckId

    fun nextRandomRecordId(): RandomRecordId

    fun synchronize(lastSequence: Long) {}
}

class SequentialSessionIdSource(private val prefix: String = "local") : SessionIdSource {
    private var runSequence: Long = 0
    private var commandSequence: Long = 0
    private var eventSequence: Long = 0
    private var checkSequence: Long = 0
    private var randomRecordSequence: Long = 0

    override fun nextRunId(): RunId = RunId("$prefix.run.${++runSequence}")

    override fun nextCommandId(): CommandId = CommandId("$prefix.command.${++commandSequence}")

    override fun nextEventId(): EventId = EventId("$prefix.event.${++eventSequence}")

    override fun nextCheckId(): CheckId = CheckId("$prefix.check.${++checkSequence}")

    override fun nextRandomRecordId(): RandomRecordId = RandomRecordId("$prefix.random.${++randomRecordSequence}")

    override fun synchronize(lastSequence: Long) {
        commandSequence = maxOf(commandSequence, lastSequence)
        eventSequence = maxOf(eventSequence, lastSequence)
        checkSequence = maxOf(checkSequence, lastSequence)
        randomRecordSequence = maxOf(randomRecordSequence, lastSequence)
    }
}
