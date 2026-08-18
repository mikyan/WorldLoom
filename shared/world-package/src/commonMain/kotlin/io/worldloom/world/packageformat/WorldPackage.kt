package io.worldloom.world.packageformat

import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.WorldDefinitionCodec
import io.worldloom.definition.WorldDefinitionDecodeResult
import io.worldloom.definition.WorldDefinitionValidator
import io.worldloom.rules.module.api.RegisteredWorldModules
import io.worldloom.rules.module.api.WorldManifest
import io.worldloom.rules.module.api.WorldManifestCodec
import io.worldloom.rules.module.api.WorldManifestDecodeResult
import io.worldloom.rules.module.registry.ModuleRegistrationResult
import io.worldloom.rules.module.registry.RuleModuleRegistry

const val WORLD_PACKAGE_MANIFEST_PATH: String = "manifest.json"

data class LoadedWorldPackage(
    val manifest: WorldManifest,
    val definition: ValidatedWorldDefinition,
    val modules: RegisteredWorldModules,
    val entries: Map<String, ByteArray>,
) {
    fun entry(path: String): ByteArray? = entries[path]?.copyOf()
}

enum class WorldPackageProblemCode {
    INVALID_ARCHIVE,
    MANIFEST_MISSING,
    MANIFEST_INVALID,
    DEFINITION_MISSING,
    DEFINITION_INVALID,
    WORLD_ID_MISMATCH,
    MODULE_REGISTRATION_FAILED,
}

data class WorldPackageProblem(val code: WorldPackageProblemCode, val message: String)

sealed interface WorldPackageLoadResult {
    data class Success(val worldPackage: LoadedWorldPackage) : WorldPackageLoadResult
    data class Failure(val problems: List<WorldPackageProblem>) : WorldPackageLoadResult
}

object WorldPackageBuilder {
    fun build(
        manifest: WorldManifest,
        definition: WorldDefinition,
        additionalEntries: List<ArchiveEntry> = emptyList(),
    ): ByteArray {
        require(manifest.worldId == definition.id) { "Manifest world id must match the definition" }
        require(additionalEntries.none { it.path == WORLD_PACKAGE_MANIFEST_PATH || it.path == manifest.worldDefinitionPath }) {
            "Additional entries cannot replace authoritative package files"
        }
        return StoredZipArchive.encode(
            listOf(
                ArchiveEntry(WORLD_PACKAGE_MANIFEST_PATH, WorldManifestCodec.encode(manifest).encodeToByteArray()),
                ArchiveEntry(manifest.worldDefinitionPath, WorldDefinitionCodec.encode(definition).encodeToByteArray()),
            ) + additionalEntries,
        )
    }
}

class WorldPackageLoader(
    private val moduleRegistry: RuleModuleRegistry,
) {
    fun load(source: ByteArray): WorldPackageLoadResult {
        val entries = when (val archive = StoredZipArchive.decode(source)) {
            is ArchiveResult.Success -> archive.entries.associate { it.path to it.content }
            is ArchiveResult.Failure -> return failure(WorldPackageProblemCode.INVALID_ARCHIVE, archive.message)
        }
        val manifestSource = entries[WORLD_PACKAGE_MANIFEST_PATH]?.decodeToString()
            ?: return failure(WorldPackageProblemCode.MANIFEST_MISSING, "World package manifest is missing")
        val manifest = when (val decoded = WorldManifestCodec.decode(manifestSource)) {
            is WorldManifestDecodeResult.Success -> decoded.manifest
            is WorldManifestDecodeResult.Failure -> return failure(
                WorldPackageProblemCode.MANIFEST_INVALID,
                decoded.message,
            )
        }
        val definitionSource = entries[manifest.worldDefinitionPath]?.decodeToString()
            ?: return failure(WorldPackageProblemCode.DEFINITION_MISSING, "World definition entry is missing")
        val definition = when (val decoded = WorldDefinitionCodec.decode(definitionSource)) {
            is WorldDefinitionDecodeResult.Success -> decoded.definition
            is WorldDefinitionDecodeResult.Failure -> return failure(
                WorldPackageProblemCode.DEFINITION_INVALID,
                decoded.message,
            )
        }
        if (manifest.worldId != definition.id) {
            return failure(WorldPackageProblemCode.WORLD_ID_MISMATCH, "Manifest and definition world ids differ")
        }
        val validated = when (val result = WorldDefinitionValidator.validate(definition)) {
            is DefinitionValidationResult.Valid -> result.definition
            is DefinitionValidationResult.Invalid -> return WorldPackageLoadResult.Failure(
                result.problems.map {
                    WorldPackageProblem(WorldPackageProblemCode.DEFINITION_INVALID, "${it.path}: ${it.message}")
                },
            )
        }
        val modules = when (val registration = moduleRegistry.register(manifest)) {
            is ModuleRegistrationResult.Success -> registration.modules
            is ModuleRegistrationResult.Failure -> return WorldPackageLoadResult.Failure(
                registration.problems.map {
                    WorldPackageProblem(WorldPackageProblemCode.MODULE_REGISTRATION_FAILED, "${it.path}: ${it.message}")
                },
            )
        }
        return WorldPackageLoadResult.Success(
            LoadedWorldPackage(manifest, validated, modules, entries.mapValues { it.value.copyOf() }),
        )
    }

    private fun failure(code: WorldPackageProblemCode, message: String) =
        WorldPackageLoadResult.Failure(listOf(WorldPackageProblem(code, message)))
}
