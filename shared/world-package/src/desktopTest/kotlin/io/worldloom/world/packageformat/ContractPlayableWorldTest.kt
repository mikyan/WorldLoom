package io.worldloom.world.packageformat

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.WorldDefinitionCodec
import io.worldloom.definition.WorldDefinitionDecodeResult
import io.worldloom.rules.module.api.WorldManifestCodec
import io.worldloom.rules.module.api.WorldManifestDecodeResult
import io.worldloom.rules.module.registry.StandardRuleModules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContractPlayableWorldTest {
    @Test
    fun warAndStationUseTheSamePlayableContractLoader() {
        val cases = listOf(
            ContractCase("war-survival", 3, DefinitionId("war.ending.hopeful")),
            ContractCase("station-ai", 1, DefinitionId("station.ending.stable")),
        )

        cases.forEach { case ->
            val loaded = load(case.directory)
            val contract = assertNotNull(loaded.playableContract)
            val routes = contract.source.goldenRoutes

            assertEquals(case.routeCount, routes.size)
            val result = assertIs<PlayableRouteSimulationResult.Complete>(contract.simulate(routes.first().id))
            assertEquals(case.expectedGoldenEnding, result.endingId)
            assertTrue(result.trace.isNotEmpty())
        }
    }

    @Test
    fun loaderRejectsManifestThatDeclaresMissingPlayableContract() {
        val manifest = assertIs<WorldManifestDecodeResult.Success>(
            WorldManifestCodec.decode(resource("war-survival/manifest.json")),
        ).manifest
        val definition = assertIs<WorldDefinitionDecodeResult.Success>(
            WorldDefinitionCodec.decode(resource("war-survival/world.json")),
        ).definition
        val archive = WorldPackageBuilder.build(manifest, definition)

        val failure = assertIs<WorldPackageLoadResult.Failure>(
            WorldPackageLoader(StandardRuleModules.registry()).load(archive),
        )

        assertEquals(WorldPackageProblemCode.PLAYABLE_CONTRACT_MISSING, failure.problems.single().code)
        assertTrue(failure.problems.single().message.contains("playable-world.json"))
    }

    private fun load(directory: String): LoadedWorldPackage {
        val manifest = assertIs<WorldManifestDecodeResult.Success>(
            WorldManifestCodec.decode(resource("$directory/manifest.json")),
        ).manifest
        val definition = assertIs<WorldDefinitionDecodeResult.Success>(
            WorldDefinitionCodec.decode(resource("$directory/world.json")),
        ).definition
        val contractPath = assertNotNull(manifest.playableContractPath)
        val contractJson = resource("$directory/$contractPath")
        val contract = assertIs<PlayableWorldContractDecodeResult.Success>(
            PlayableWorldContractCodec.decode(contractJson),
        ).contract
        val entries = buildList {
            add(ArchiveEntry(contractPath, contractJson.encodeToByteArray()))
            contract.character.profilePath?.let { path ->
                add(ArchiveEntry(path, resource("$directory/$path").encodeToByteArray()))
            }
            contract.behaviors.forEach { behavior ->
                add(ArchiveEntry(behavior.path, resource("$directory/${behavior.path}").encodeToByteArray()))
            }
        }
        val archive = WorldPackageBuilder.build(manifest, definition, entries)
        return assertIs<WorldPackageLoadResult.Success>(
            WorldPackageLoader(StandardRuleModules.registry()).load(archive),
        ).worldPackage
    }

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()

    private data class ContractCase(
        val directory: String,
        val routeCount: Int,
        val expectedGoldenEnding: DefinitionId,
    )
}
