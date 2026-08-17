package io.worldloom.definition

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

private val DEFINITION_ID_PATTERN = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+$")

/** Stable, namespaced identifier for data supplied by the Runtime or a world package. */
@Serializable
@JvmInline
value class DefinitionId(val value: String) {
    init {
        require(DEFINITION_ID_PATTERN.matches(value)) {
            "DefinitionId must be lowercase, namespaced, and stable: $value"
        }
    }

    override fun toString(): String = value
}
