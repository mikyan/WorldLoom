package io.worldloom.persistence

import io.worldloom.rules.CheckResolvedEvent
import io.worldloom.world.EventEnvelope
import io.worldloom.world.GameEventPayload
import io.worldloom.world.GameState
import io.worldloom.world.NumericComponentAdjustedEvent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

sealed interface PersistenceDecodeResult<out T> {
    data class Success<T>(val value: T) : PersistenceDecodeResult<T>

    data class Failure(
        val code: String,
        val message: String,
    ) : PersistenceDecodeResult<Nothing>
}

@OptIn(ExperimentalSerializationApi::class)
object PersistenceCodec {
    private val json = Json {
        serializersModule = SerializersModule {
            polymorphic(GameEventPayload::class) {
                subclass(NumericComponentAdjustedEvent::class)
                subclass(CheckResolvedEvent::class)
            }
        }
        classDiscriminator = "kind"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        exceptionsWithDebugInfo = false
    }

    fun encodeEvent(event: EventEnvelope): String = json.encodeToString(event)

    fun decodeEvent(source: String): PersistenceDecodeResult<EventEnvelope> = decode("event", source)

    fun encodeState(state: GameState): String = json.encodeToString(state)

    fun decodeState(source: String): PersistenceDecodeResult<GameState> = decode("state", source)

    private inline fun <reified T> decode(
        label: String,
        source: String,
    ): PersistenceDecodeResult<T> =
        try {
            PersistenceDecodeResult.Success(json.decodeFromString<T>(source))
        } catch (error: SerializationException) {
            PersistenceDecodeResult.Failure(
                "persistence.$label.invalid_json",
                error.message ?: "Stored $label JSON is invalid",
            )
        } catch (error: IllegalArgumentException) {
            PersistenceDecodeResult.Failure(
                "persistence.$label.invalid_value",
                error.message ?: "Stored $label contains an invalid value",
            )
        }
}
