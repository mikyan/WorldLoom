package io.worldloom.behavior.runtime

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.TypedValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val BEHAVIOR_SCHEMA_V1: String = "worldloom.behavior/v1"

@Serializable
data class BehaviorDefinition(
    val schema: String,
    val id: DefinitionId,
    val trigger: BehaviorTrigger,
    val condition: BehaviorExpression,
    val effects: List<BehaviorCommandEffect>,
    val policy: BehaviorPolicy = BehaviorPolicy(),
    val visibility: BehaviorVisibility = BehaviorVisibility.PRIVATE,
)

@Serializable
data class BehaviorTrigger(
    val eventType: DefinitionId,
    val bindings: Map<String, String> = emptyMap(),
)

@Serializable
data class BehaviorPolicy(
    val priority: Int = 0,
    val maxFiringsPerEvent: Int = 1,
) {
    init {
        require(maxFiringsPerEvent in 1..100) { "Behavior firing limit must be between 1 and 100" }
    }
}

@Serializable
enum class BehaviorVisibility { PUBLIC, PRIVATE }

@Serializable
data class BehaviorCommandEffect(
    val commandId: DefinitionId,
    val arguments: Map<String, BehaviorExpression>,
)

@Serializable
sealed interface BehaviorExpression

@Serializable
@SerialName("value")
data class ValueExpression(val value: TypedValue) : BehaviorExpression

@Serializable
@SerialName("path")
data class PathExpression(val path: String) : BehaviorExpression {
    init { require(path.isNotBlank()) { "Behavior path must not be blank" } }
}

@Serializable
@SerialName("component-field")
data class ComponentFieldExpression(
    val entity: BehaviorExpression,
    val componentId: DefinitionId,
    val fieldId: DefinitionId,
) : BehaviorExpression

@Serializable
@SerialName("all")
data class AllExpression(val terms: List<BehaviorExpression>) : BehaviorExpression

@Serializable
@SerialName("any")
data class AnyExpression(val terms: List<BehaviorExpression>) : BehaviorExpression

@Serializable
@SerialName("not")
data class NotExpression(val term: BehaviorExpression) : BehaviorExpression

@Serializable
enum class ComparisonOperator { EQ, NEQ, LT, LTE, GT, GTE }

@Serializable
@SerialName("compare")
data class ComparisonExpression(
    val operator: ComparisonOperator,
    val left: BehaviorExpression,
    val right: BehaviorExpression,
) : BehaviorExpression

@Serializable
enum class ArithmeticOperator { ADD, SUBTRACT, MULTIPLY, DIVIDE, MIN, MAX }

@Serializable
@SerialName("arithmetic")
data class ArithmeticExpression(
    val operator: ArithmeticOperator,
    val left: BehaviorExpression,
    val right: BehaviorExpression,
) : BehaviorExpression

sealed interface BehaviorDecodeResult {
    data class Success(val behavior: BehaviorDefinition) : BehaviorDecodeResult
    data class Failure(val message: String) : BehaviorDecodeResult
}

object BehaviorCodec {
    private val json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    fun encode(behavior: BehaviorDefinition): String = json.encodeToString(behavior)

    fun decode(source: String): BehaviorDecodeResult = try {
        BehaviorDecodeResult.Success(json.decodeFromString<BehaviorDefinition>(source))
    } catch (error: SerializationException) {
        BehaviorDecodeResult.Failure(error.message ?: "Behavior JSON is invalid")
    } catch (error: IllegalArgumentException) {
        BehaviorDecodeResult.Failure(error.message ?: "Behavior value is invalid")
    }
}
