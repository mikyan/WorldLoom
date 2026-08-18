package io.worldloom.world.packageformat

import io.worldloom.definition.WorldDefinitionCodec
import io.worldloom.definition.WorldDefinitionDecodeResult
import io.worldloom.rules.module.api.WorldManifestCodec
import io.worldloom.rules.module.api.WorldManifestDecodeResult
import io.worldloom.rules.module.registry.StandardRuleModules
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorldPackageTest {
    @Test
    fun bothContractWorldsRoundTripThroughRealZipContainer() {
        listOf("war-survival", "station-ai").forEach { directory ->
            val manifest = assertIs<WorldManifestDecodeResult.Success>(
                WorldManifestCodec.decode(resource("$directory/manifest.json")),
            ).manifest
            val definition = assertIs<WorldDefinitionDecodeResult.Success>(
                WorldDefinitionCodec.decode(resource("$directory/world.json")),
            ).definition
            val source = "source for $directory".encodeToByteArray()
            val archive = WorldPackageBuilder.build(
                manifest,
                definition,
                listOf(ArchiveEntry("sources/source.txt", source)),
            )

            assertContentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04), archive.copyOfRange(0, 4))
            val loaded = assertIs<WorldPackageLoadResult.Success>(
                WorldPackageLoader(StandardRuleModules.registry()).load(archive),
            ).worldPackage
            assertEquals(manifest.worldId, loaded.definition.source.id)
            assertContentEquals(source, loaded.entry("sources/source.txt"))
        }
    }

    @Test
    fun archiveRejectsTraversalDuplicateEntriesAndCrcTampering() {
        assertFailsWith<IllegalArgumentException> {
            StoredZipArchive.encode(listOf(ArchiveEntry("../secret", byteArrayOf(1))))
        }
        assertFailsWith<IllegalArgumentException> {
            StoredZipArchive.encode(
                listOf(ArchiveEntry("manifest.json", byteArrayOf(1)), ArchiveEntry("manifest.json", byteArrayOf(2))),
            )
        }
        val archive = StoredZipArchive.encode(listOf(ArchiveEntry("safe.txt", "safe".encodeToByteArray())))
        val tampered = archive.copyOf()
        val dataIndex = 30 + "safe.txt".encodeToByteArray().size
        tampered[dataIndex] = (tampered[dataIndex].toInt() xor 1).toByte()

        val failure = assertIs<ArchiveResult.Failure>(StoredZipArchive.decode(tampered))
        assertTrue(failure.message.contains("CRC"))
    }

    @Test
    fun loaderRejectsManifestDefinitionIdentityMismatch() {
        val manifest = assertIs<WorldManifestDecodeResult.Success>(
            WorldManifestCodec.decode(resource("war-survival/manifest.json")),
        ).manifest
        val definition = assertIs<WorldDefinitionDecodeResult.Success>(
            WorldDefinitionCodec.decode(resource("station-ai/world.json")),
        ).definition
        val archive = StoredZipArchive.encode(
            listOf(
                ArchiveEntry("manifest.json", WorldManifestCodec.encode(manifest).encodeToByteArray()),
                ArchiveEntry(manifest.worldDefinitionPath, WorldDefinitionCodec.encode(definition).encodeToByteArray()),
            ),
        )

        val failure = assertIs<WorldPackageLoadResult.Failure>(
            WorldPackageLoader(StandardRuleModules.registry()).load(archive),
        )
        assertEquals(WorldPackageProblemCode.WORLD_ID_MISMATCH, failure.problems.single().code)
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader.getResource(path)).readText()
}
