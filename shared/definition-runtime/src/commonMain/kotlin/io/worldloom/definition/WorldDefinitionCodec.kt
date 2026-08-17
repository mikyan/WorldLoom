package io.worldloom.definition

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface WorldDefinitionDecodeResult {
    data class Success(val definition: WorldDefinition) : WorldDefinitionDecodeResult

    data class Failure(
        val code: String,
        val message: String,
    ) : WorldDefinitionDecodeResult
}

@OptIn(ExperimentalSerializationApi::class)
object WorldDefinitionCodec {
    private val json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        exceptionsWithDebugInfo = false
        prettyPrint = true
    }

    fun decode(source: String): WorldDefinitionDecodeResult =
        try {
            WorldDefinitionDecodeResult.Success(json.decodeFromString<WorldDefinition>(source))
        } catch (error: SerializationException) {
            WorldDefinitionDecodeResult.Failure(
                code = "definition.decode.invalid_json",
                message = error.message ?: "World definition JSON is invalid",
            )
        } catch (error: IllegalArgumentException) {
            WorldDefinitionDecodeResult.Failure(
                code = "definition.decode.invalid_value",
                message = error.message ?: "World definition contains an invalid value",
            )
        }

    fun encode(definition: WorldDefinition): String = json.encodeToString(definition)
}
