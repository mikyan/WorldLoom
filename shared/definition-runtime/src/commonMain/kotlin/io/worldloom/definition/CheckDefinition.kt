package io.worldloom.definition

import kotlinx.serialization.Serializable

@Serializable
enum class CheckResolutionMode {
    RANDOM,
    DETERMINISTIC,
}

@Serializable
data class DiceExpression(
    val count: Int,
    val sides: Int,
)

@Serializable
data class CheckOutcomeDefinition(
    val id: DefinitionId,
    val label: String,
    val minimumTotal: Long,
)

/** Declarative input to the deterministic RuleEngine; it contains no executable expressions. */
@Serializable
data class CheckProfileDefinition(
    val id: DefinitionId,
    val label: String,
    val mode: CheckResolutionMode,
    val dice: DiceExpression? = null,
    val baseValue: Long = 0,
    val outcomes: List<CheckOutcomeDefinition>,
)
