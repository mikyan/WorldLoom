package io.worldloom.rules

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

private val RULE_RECORD_ID_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9._:-]*$")

@Serializable
@JvmInline
value class CheckId(val value: String) {
    init {
        require(RULE_RECORD_ID_PATTERN.matches(value)) { "CheckId must be a stable identifier" }
    }
}

@Serializable
@JvmInline
value class RandomRecordId(val value: String) {
    init {
        require(RULE_RECORD_ID_PATTERN.matches(value)) { "RandomRecordId must be a stable identifier" }
    }
}
