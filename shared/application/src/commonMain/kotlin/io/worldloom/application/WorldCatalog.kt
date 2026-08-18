package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.WorldDefinitionCodec
import io.worldloom.definition.WorldDefinitionDecodeResult
import io.worldloom.rules.module.api.RegisteredWorldModules
import io.worldloom.rules.module.api.WorldManifest
import io.worldloom.rules.module.api.WorldManifestCodec
import io.worldloom.rules.module.api.WorldManifestDecodeResult
import io.worldloom.rules.module.registry.ModuleRegistrationResult
import io.worldloom.rules.module.registry.RuleModuleRegistry
import io.worldloom.rules.module.registry.StandardRuleModules

data class WorldCatalogEntry(
    val id: DefinitionId,
    val title: String,
    val moduleIds: List<DefinitionId>,
)

data class LoadedWorldPackage(
    val manifest: WorldManifest,
    val definition: WorldDefinition,
    val modules: RegisteredWorldModules,
)

/** Serialized, declarative files from one world package. No executable code is accepted here. */
data class WorldPackageSource(
    val manifestJson: String,
    val files: Map<String, String>,
)

interface WorldCatalog {
    val entries: List<WorldCatalogEntry>

    suspend fun load(id: DefinitionId): LoadedWorldPackage?
}

sealed interface StaticWorldCatalogResult {
    data class Success(val catalog: StaticWorldCatalog) : StaticWorldCatalogResult

    data class Failure(
        val sourceIndex: Int,
        val code: String,
        val message: String,
        val path: String? = null,
    ) : StaticWorldCatalogResult
}

/** In-memory package catalog used by the development apps and tests. */
class StaticWorldCatalog private constructor(
    private val packages: Map<DefinitionId, LoadedWorldPackage>,
) : WorldCatalog {
    override val entries: List<WorldCatalogEntry> = packages.values
        .map { loaded ->
            WorldCatalogEntry(
                id = loaded.definition.id,
                title = loaded.definition.title,
                moduleIds = loaded.modules.modules.map { it.descriptor.id },
            )
        }
        .sortedBy { it.id.value }

    override suspend fun load(id: DefinitionId): LoadedWorldPackage? = packages[id]

    companion object {
        fun fromPackageSources(
            sources: List<WorldPackageSource>,
            registry: RuleModuleRegistry = StandardRuleModules.registry(),
        ): StaticWorldCatalogResult {
            val packages = linkedMapOf<DefinitionId, LoadedWorldPackage>()
            sources.forEachIndexed { index, source ->
                val manifest = when (val decoded = WorldManifestCodec.decode(source.manifestJson)) {
                    is WorldManifestDecodeResult.Success -> decoded.manifest
                    is WorldManifestDecodeResult.Failure -> {
                        return StaticWorldCatalogResult.Failure(index, decoded.code, decoded.message, "manifest.json")
                    }
                }
                val modules = when (val registration = registry.register(manifest)) {
                    is ModuleRegistrationResult.Success -> registration.modules
                    is ModuleRegistrationResult.Failure -> {
                        val first = registration.problems.first()
                        return StaticWorldCatalogResult.Failure(
                            index,
                            "world-package.module.${first.code.name.lowercase()}",
                            first.message,
                            "manifest.json:${first.path}",
                        )
                    }
                }
                val definitionSource = source.files[manifest.worldDefinitionPath]
                    ?: return StaticWorldCatalogResult.Failure(
                        index,
                        "world-package.definition.missing",
                        "World definition file is missing: ${manifest.worldDefinitionPath}",
                        manifest.worldDefinitionPath,
                    )
                val definition = when (val decoded = WorldDefinitionCodec.decode(definitionSource)) {
                    is WorldDefinitionDecodeResult.Success -> decoded.definition
                    is WorldDefinitionDecodeResult.Failure -> {
                        return StaticWorldCatalogResult.Failure(
                            index,
                            decoded.code,
                            decoded.message,
                            manifest.worldDefinitionPath,
                        )
                    }
                }
                if (definition.id != manifest.worldId) {
                    return StaticWorldCatalogResult.Failure(
                        index,
                        "world-package.world_id_mismatch",
                        "Manifest world ID ${manifest.worldId} does not match definition ID ${definition.id}",
                        manifest.worldDefinitionPath,
                    )
                }
                if (packages.put(definition.id, LoadedWorldPackage(manifest, definition, modules)) != null) {
                    return StaticWorldCatalogResult.Failure(
                        sourceIndex = index,
                        code = "catalog.duplicate_world",
                        message = "Duplicate world definition: ${definition.id}",
                    )
                }
            }
            return StaticWorldCatalogResult.Success(StaticWorldCatalog(packages))
        }
    }
}
