package io.worldloom.definition

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The minimal deterministic value types accepted by the initialization schema. */
@Serializable
sealed interface TypedValue

@Serializable
@SerialName("boolean")
data class BooleanValue(val value: Boolean) : TypedValue

@Serializable
@SerialName("integer")
data class IntegerValue(val value: Long) : TypedValue

/**
 * A platform-independent decimal represented without floating-point arithmetic.
 * For example, `1234` with scale `2` represents `12.34`.
 */
@Serializable
@SerialName("decimal")
data class DecimalValue(
    val unscaledValue: Long,
    val scale: Int,
) : TypedValue {
    init {
        require(scale in 0..9) { "Decimal scale must be between 0 and 9" }
    }
}

@Serializable
@SerialName("text")
data class TextValue(val value: String) : TypedValue

@Serializable
@SerialName("definition-reference")
data class DefinitionReferenceValue(val value: DefinitionId) : TypedValue

@Serializable
enum class ValueType {
    BOOLEAN,
    INTEGER,
    DECIMAL,
    TEXT,
    DEFINITION_REFERENCE,
}

fun TypedValue.valueType(): ValueType =
    when (this) {
        is BooleanValue -> ValueType.BOOLEAN
        is IntegerValue -> ValueType.INTEGER
        is DecimalValue -> ValueType.DECIMAL
        is TextValue -> ValueType.TEXT
        is DefinitionReferenceValue -> ValueType.DEFINITION_REFERENCE
    }
