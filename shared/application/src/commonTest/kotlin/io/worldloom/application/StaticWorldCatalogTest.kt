package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.rules.module.api.CURRENT_RULE_MODULE_API_VERSION
import io.worldloom.rules.module.api.CURRENT_WORLD_MANIFEST_SCHEMA_VERSION
import io.worldloom.rules.module.api.ModuleVersion
import io.worldloom.rules.module.api.WorldManifest
import io.worldloom.rules.module.api.WorldManifestCodec
import io.worldloom.rules.module.api.WorldModuleSelection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StaticWorldCatalogTest {
    @Test
    fun reportsSourceIndexWithoutAcceptingInvalidManifestJson() {
        val result = assertIs<StaticWorldCatalogResult.Failure>(
            StaticWorldCatalog.fromPackageSources(listOf(WorldPackageSource("{invalid", emptyMap()))),
        )

        assertEquals(0, result.sourceIndex)
        assertEquals("world-manifest.decode.invalid_json", result.code)
        assertEquals("manifest.json", result.path)
    }

    @Test
    fun rejectsManifestAndDefinitionWithDifferentWorldIds() {
        val source = packageSource(
            manifestWorldId = DefinitionId("contract.first"),
            definitionWorldId = DefinitionId("contract.second"),
        )

        val result = assertIs<StaticWorldCatalogResult.Failure>(
            StaticWorldCatalog.fromPackageSources(listOf(source)),
        )

        assertEquals("world-package.world_id_mismatch", result.code)
    }

    @Test
    fun emptyCatalogHasNoImplicitWorld() = runTest {
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(emptyList()),
        ).catalog

        assertEquals(emptyList(), catalog.entries)
        assertEquals(null, catalog.load(DefinitionId("missing.world")))
    }

    private fun packageSource(
        manifestWorldId: DefinitionId,
        definitionWorldId: DefinitionId,
    ): WorldPackageSource {
        val manifest = WorldManifest(
            schemaVersion = CURRENT_WORLD_MANIFEST_SCHEMA_VERSION,
            runtimeApiVersion = CURRENT_RULE_MODULE_API_VERSION,
            worldId = manifestWorldId,
            worldDefinitionPath = "world.json",
            modules = listOf(
                WorldModuleSelection(
                    DefinitionId("worldloom.core.numeric-state"),
                    ModuleVersion(1, 0, 0),
                    mapOf(
                        DefinitionId("worldloom.parameter.direct-adjustment") to
                            io.worldloom.definition.BooleanValue(true),
                    ),
                ),
            ),
        )
        val world = DefaultGameSessionTest.worldSource(namespace = definitionWorldId.value.substringAfter('.'))
            .copy(id = definitionWorldId)
        return WorldPackageSource(
            WorldManifestCodec.encode(manifest),
            mapOf("world.json" to io.worldloom.definition.WorldDefinitionCodec.encode(world)),
        )
    }
}
