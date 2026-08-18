package io.worldloom.content.schema

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.rules.module.api.WorldManifest
import io.worldloom.rules.module.api.WorldModuleSelection
import io.worldloom.rules.module.registry.ModuleRegistrationResult
import io.worldloom.rules.module.registry.RuleModuleRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val CURRENT_RULE_PROFILE_SCHEMA_VERSION: Int = 1

@Serializable
data class RuleProfile(
    val schemaVersion: Int,
    val id: DefinitionId,
    val checkProfileIds: List<DefinitionId>,
    val modules: List<WorldModuleSelection>,
)

enum class RuleProfileProblemCode {
    UNSUPPORTED_SCHEMA,
    DUPLICATE_CHECK_PROFILE,
    CHECK_PROFILE_NOT_FOUND,
    MODULE_SELECTION_MISMATCH,
    MODULE_REGISTRATION_FAILED,
}

data class RuleProfileProblem(val code: RuleProfileProblemCode, val path: String, val message: String)

sealed interface RuleProfileValidationResult {
    data class Valid(val profile: RuleProfile) : RuleProfileValidationResult
    data class Invalid(val problems: List<RuleProfileProblem>) : RuleProfileValidationResult
}

object RuleProfileValidator {
    fun validate(
        profile: RuleProfile,
        manifest: WorldManifest,
        definition: ValidatedWorldDefinition,
        registry: RuleModuleRegistry,
    ): RuleProfileValidationResult {
        val problems = mutableListOf<RuleProfileProblem>()
        if (profile.schemaVersion != CURRENT_RULE_PROFILE_SCHEMA_VERSION) {
            problems += problem(RuleProfileProblemCode.UNSUPPORTED_SCHEMA, "schemaVersion", "Unsupported RuleProfile schema")
        }
        if (profile.checkProfileIds.distinct().size != profile.checkProfileIds.size) {
            problems += problem(RuleProfileProblemCode.DUPLICATE_CHECK_PROFILE, "checkProfileIds", "Check profiles are duplicated")
        }
        profile.checkProfileIds.forEachIndexed { index, id ->
            if (definition.checkProfile(id) == null) {
                problems += problem(RuleProfileProblemCode.CHECK_PROFILE_NOT_FOUND, "checkProfileIds[$index]", "Check profile is missing")
            }
        }
        if (profile.modules != manifest.modules) {
            problems += problem(
                RuleProfileProblemCode.MODULE_SELECTION_MISMATCH,
                "modules",
                "RuleProfile module selections must match the package manifest",
            )
        }
        val registrationManifest = manifest.copy(modules = profile.modules)
        if (registry.register(registrationManifest) is ModuleRegistrationResult.Failure) {
            problems += problem(
                RuleProfileProblemCode.MODULE_REGISTRATION_FAILED,
                "modules",
                "RuleProfile modules are not compatible with the Runtime",
            )
        }
        return if (problems.isEmpty()) RuleProfileValidationResult.Valid(profile)
        else RuleProfileValidationResult.Invalid(problems)
    }

    private fun problem(code: RuleProfileProblemCode, path: String, message: String) = RuleProfileProblem(code, path, message)
}

sealed interface ContentProfileDecodeResult<out T> {
    data class Success<T>(val value: T) : ContentProfileDecodeResult<T>
    data class Failure(val message: String) : ContentProfileDecodeResult<Nothing>
}

object ContentProfileCodec {
    private val json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    fun encodeCharacterCreation(profile: CharacterCreationProfile): String = json.encodeToString(profile)
    fun decodeCharacterCreation(source: String): ContentProfileDecodeResult<CharacterCreationProfile> = decode(source)
    fun encodeRuleProfile(profile: RuleProfile): String = json.encodeToString(profile)
    fun decodeRuleProfile(source: String): ContentProfileDecodeResult<RuleProfile> = decode(source)

    private inline fun <reified T> decode(source: String): ContentProfileDecodeResult<T> = try {
        ContentProfileDecodeResult.Success(json.decodeFromString<T>(source))
    } catch (error: SerializationException) {
        ContentProfileDecodeResult.Failure(error.message ?: "Content profile JSON is invalid")
    } catch (error: IllegalArgumentException) {
        ContentProfileDecodeResult.Failure(error.message ?: "Content profile value is invalid")
    }
}
