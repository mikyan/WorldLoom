package io.worldloom.rules.module.registry

import io.worldloom.definition.DefinitionId
import io.worldloom.rules.module.api.WorldManifestCodec
import io.worldloom.rules.module.api.WorldManifestDecodeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ContractManifestTest {
    @Test
    fun contractWorldsResolveDifferentCapabilitiesWithoutRuntimeBranches() {
        val cases = listOf(
            ContractCase(
                directory = "war-survival",
                worldId = DefinitionId("contract.war-survival"),
                includedCapability = DefinitionId("worldloom.schema.random-check-profile"),
                excludedCapability = DefinitionId("worldloom.schema.deterministic-check-profile"),
            ),
            ContractCase(
                directory = "station-ai",
                worldId = DefinitionId("contract.station-ai"),
                includedCapability = DefinitionId("worldloom.schema.deterministic-check-profile"),
                excludedCapability = DefinitionId("worldloom.schema.random-check-profile"),
            ),
        )

        cases.forEach { case ->
            val manifestSource = requireNotNull(javaClass.classLoader.getResource("${case.directory}/manifest.json"))
                .readText()
            val manifest = assertIs<WorldManifestDecodeResult.Success>(
                WorldManifestCodec.decode(manifestSource),
            ).manifest
            val registered = assertIs<io.worldloom.rules.module.registry.ModuleRegistrationResult.Success>(
                StandardRuleModules.registry().register(manifest),
            ).modules

            assertEquals(case.worldId, manifest.worldId)
            assertNotNull(registered.capability(case.includedCapability))
            assertNull(registered.capability(case.excludedCapability))
        }
    }

    private data class ContractCase(
        val directory: String,
        val worldId: DefinitionId,
        val includedCapability: DefinitionId,
        val excludedCapability: DefinitionId,
    )
}
