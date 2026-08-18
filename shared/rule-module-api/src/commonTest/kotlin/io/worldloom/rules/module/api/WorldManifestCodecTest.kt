package io.worldloom.rules.module.api

import io.worldloom.definition.BooleanValue
import io.worldloom.definition.DefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorldManifestCodecTest {
    @Test
    fun roundTripsTypedModuleParameters() {
        val manifest = WorldManifest(
            schemaVersion = CURRENT_WORLD_MANIFEST_SCHEMA_VERSION,
            runtimeApiVersion = CURRENT_RULE_MODULE_API_VERSION,
            worldId = DefinitionId("contract.example"),
            worldDefinitionPath = "world.json",
            modules = listOf(
                WorldModuleSelection(
                    DefinitionId("worldloom.core.numeric-state"),
                    ModuleVersion(1, 0, 0),
                    mapOf(DefinitionId("worldloom.parameter.direct-adjustment") to BooleanValue(true)),
                ),
            ),
        )

        val decoded = assertIs<WorldManifestDecodeResult.Success>(
            WorldManifestCodec.decode(WorldManifestCodec.encode(manifest)),
        ).manifest

        assertEquals(manifest, decoded)
    }
}
