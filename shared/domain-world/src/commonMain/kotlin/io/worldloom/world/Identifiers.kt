package io.worldloom.world

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

private val STABLE_ID_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9._:-]*$")

private fun requireStableId(
    value: String,
    label: String,
) {
    require(STABLE_ID_PATTERN.matches(value)) { "$label must be a non-blank stable identifier" }
}

@Serializable
@JvmInline
value class RunId(val value: String) {
    init {
        requireStableId(value, "RunId")
    }
}

@Serializable
@JvmInline
value class EntityId(val value: String) {
    init {
        requireStableId(value, "EntityId")
    }
}

@Serializable
@JvmInline
value class ActorId(val value: String) {
    init {
        requireStableId(value, "ActorId")
    }
}

@Serializable
@JvmInline
value class CommandId(val value: String) {
    init {
        requireStableId(value, "CommandId")
    }
}

@Serializable
@JvmInline
value class EventId(val value: String) {
    init {
        requireStableId(value, "EventId")
    }
}
