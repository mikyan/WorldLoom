package io.worldloom.rules.module.api

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.TypedValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val CURRENT_WORLD_MANIFEST_SCHEMA_VERSION: Int = 1
const val CURRENT_RULE_MODULE_API_VERSION: Int = 1

/** A stable semantic version used by built-in rule modules and world package selections. */
@Serializable
data class ModuleVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ModuleVersion> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "Module version parts must not be negative" }
    }

    override fun compareTo(other: ModuleVersion): Int =
        compareValuesBy(this, other, ModuleVersion::major, ModuleVersion::minor, ModuleVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"
}

@Serializable
data class WorldModuleSelection(
    val id: DefinitionId,
    val version: ModuleVersion,
    val parameters: Map<DefinitionId, TypedValue> = emptyMap(),
)

/** The executable-code boundary of a world package. Only registered module IDs may cross it. */
@Serializable
data class WorldManifest(
    val schemaVersion: Int,
    val runtimeApiVersion: Int,
    val worldId: DefinitionId,
    val worldDefinitionPath: String,
    val modules: List<WorldModuleSelection>,
    /** Optional declarative entry contract. Worlds without it remain valid legacy/fixture packages. */
    val playableContractPath: String? = null,
)

sealed interface WorldManifestDecodeResult {
    data class Success(val manifest: WorldManifest) : WorldManifestDecodeResult

    data class Failure(
        val code: String,
        val message: String,
    ) : WorldManifestDecodeResult
}

@OptIn(ExperimentalSerializationApi::class)
object WorldManifestCodec {
    private val json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        exceptionsWithDebugInfo = false
        prettyPrint = true
    }

    fun decode(source: String): WorldManifestDecodeResult =
        try {
            WorldManifestDecodeResult.Success(json.decodeFromString<WorldManifest>(source))
        } catch (error: SerializationException) {
            WorldManifestDecodeResult.Failure(
                code = "world-manifest.decode.invalid_json",
                message = error.message ?: "World manifest JSON is invalid",
            )
        } catch (error: IllegalArgumentException) {
            WorldManifestDecodeResult.Failure(
                code = "world-manifest.decode.invalid_value",
                message = error.message ?: "World manifest contains an invalid value",
            )
        }

    fun encode(manifest: WorldManifest): String = json.encodeToString(manifest)
}
