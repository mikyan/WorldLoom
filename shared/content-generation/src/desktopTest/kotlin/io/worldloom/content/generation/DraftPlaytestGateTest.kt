package io.worldloom.content.generation

import io.worldloom.definition.WorldDefinitionCodec
import io.worldloom.definition.WorldDefinitionDecodeResult
import io.worldloom.rules.module.api.WorldManifestCodec
import io.worldloom.rules.module.api.WorldManifestDecodeResult
import io.worldloom.rules.module.registry.StandardRuleModules
import io.worldloom.world.packageformat.ArchiveEntry
import io.worldloom.world.packageformat.ArchiveResult
import io.worldloom.world.packageformat.StoredZipArchive
import io.worldloom.world.packageformat.WorldPackageBuilder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DraftPlaytestGateTest {
    private val validator = DraftPlayabilityValidator(StandardRuleModules.registry())

    @Test
    fun sourceMappedBuiltInContractPassesTheAggregatePlayabilityGate() {
        val candidate = candidate(recognitionJob = recognitionJob())

        val valid = assertIs<DraftPlayabilityResult.Valid>(validator.validate(candidate)).draft

        assertEquals("contract.war-survival", valid.worldPackage.manifest.worldId.value)
        assertEquals(3, valid.worldPackage.playableContract?.source?.goldenRoutes?.size)
        assertTrue(valid.sourceAddress.startsWith("sha256:"))
    }

    @Test
    fun bothBuiltInWorldsPassTheSameDraftGate() {
        val worlds = listOf(
            builtInCandidate("war-survival", "draft.built-in-war"),
            builtInCandidate("station-ai", "draft.built-in-station"),
        )

        val worldIds = worlds.map { candidate ->
            assertIs<DraftPlayabilityResult.Valid>(validator.validate(candidate)).draft.worldPackage.manifest.worldId.value
        }

        assertEquals(listOf("contract.war-survival", "contract.station-ai"), worldIds)
    }

    @Test
    fun missingPlayableContractAndExecutablePayloadAreRejectedBeforeSandbox() {
        val manifest = assertIs<WorldManifestDecodeResult.Success>(
            WorldManifestCodec.decode(resource("war-survival/manifest.json")),
        ).manifest.copy(playableContractPath = null)
        val definition = assertIs<WorldDefinitionDecodeResult.Success>(
            WorldDefinitionCodec.decode(resource("war-survival/world.json")),
        ).definition
        val noContract = PlayableDraftCandidate(
            "draft.no-contract",
            1,
            WorldPackageBuilder.build(manifest, definition),
        )
        assertTrue(
            assertIs<DraftPlayabilityResult.Invalid>(validator.validate(noContract)).problems.any {
                it.code == DraftPlayabilityProblemCode.PLAYABLE_CONTRACT_REQUIRED
            },
        )

        val scripted = candidate(extraEntries = listOf(ArchiveEntry("scripts/escape.js", "runHostCode()".encodeToByteArray())))
        val invalid = assertIs<DraftPlayabilityResult.Invalid>(validator.validate(scripted))
        assertEquals(DraftPlayabilityProblemCode.EXECUTABLE_CONTENT_FORBIDDEN, invalid.problems.single().code)
        assertEquals("scripts/escape.js", invalid.problems.single().path)
    }

    @Test
    fun forbiddenBehaviorCommandAndUnreachableEndingAreRejectedWithPaths() {
        val maliciousBehavior = resource("war-survival/behaviors/activity-starts-quest.json")
            .replace("worldloom.command.quest.advance", "worldloom.command.system.exec")
        val malicious = candidate(
            replacements = mapOf("behaviors/activity-starts-quest.json" to maliciousBehavior),
        )
        val behaviorFailure = assertIs<DraftPlayabilityResult.Invalid>(validator.validate(malicious))
        assertTrue(
            behaviorFailure.problems.any { "behaviors[" in it.path && "not whitelisted" in it.message },
            behaviorFailure.problems.toString(),
        )

        val uncoveredContract = resource("war-survival/playable-world.json")
            .replace(
                "\"expectedEndingId\": \"war.ending.costly\"",
                "\"expectedEndingId\": \"war.ending.hopeful\"",
            )
        val uncovered = candidate(replacements = mapOf("playable-world.json" to uncoveredContract))
        val endingFailure = assertIs<DraftPlayabilityResult.Invalid>(validator.validate(uncovered))
        assertTrue(endingFailure.problems.any { "goldenRoutes" in it.path || "ending" in it.message.lowercase() })
    }

    @Test
    fun atomicInstallStripsSourcesAndSandboxFactsWhileFailureKeepsPreviousRecord() = runTest {
        val store = ToggleInstalledWorldStore()
        val installer = DraftInstaller(validator, store)
        val withPrivateArtifacts = candidate(
            extraEntries = listOf(
                ArchiveEntry("generation.json", "{\"sourceMappings\":[]}".encodeToByteArray()),
                ArchiveEntry("sources/original.txt", "完整来源正文".encodeToByteArray()),
                ArchiveEntry("sandbox/event-log.json", "[\"temporary fact\"]".encodeToByteArray()),
                ArchiveEntry("agent/private-memory.json", "PRIVATE".encodeToByteArray()),
            ),
            recognitionJob = recognitionJob(),
        )

        val first = assertIs<DraftInstallResult.Installed>(installer.install(withPrivateArtifacts)).record
        val installedEntries = assertIs<ArchiveResult.Success>(StoredZipArchive.decode(first.packageBytes)).entries
        assertFalse(installedEntries.any { it.path == "generation.json" })
        assertFalse(installedEntries.any { it.path.startsWith("sources/") })
        assertFalse(installedEntries.any { it.path.startsWith("sandbox/") })
        assertFalse(installedEntries.any { it.path.startsWith("agent/") })
        assertTrue(first.contentAddress.startsWith("sha256:"))
        val beforeFailure = assertNotNull(store.current(first.worldId))

        store.failPublish = true
        val failed = assertIs<DraftInstallResult.Failure>(
            installer.install(withPrivateArtifacts.copy(draftVersion = 2)),
        )

        assertEquals(DraftInstallFailureCode.PUBLISH_FAILED, failed.code)
        assertEquals(beforeFailure, store.current(first.worldId))
    }

    private fun candidate(
        extraEntries: List<ArchiveEntry> = emptyList(),
        recognitionJob: RecognitionJobState? = null,
        replacements: Map<String, String> = emptyMap(),
    ): PlayableDraftCandidate = builtInCandidate(
        folder = "war-survival",
        draftId = "draft.source-mapped-war",
        recognitionJob = recognitionJob,
        extraEntries = extraEntries,
        replacements = replacements,
    )

    private fun builtInCandidate(
        folder: String,
        draftId: String,
        recognitionJob: RecognitionJobState? = null,
        extraEntries: List<ArchiveEntry> = emptyList(),
        replacements: Map<String, String> = emptyMap(),
    ): PlayableDraftCandidate {
        val manifest = assertIs<WorldManifestDecodeResult.Success>(
            WorldManifestCodec.decode(resource("$folder/manifest.json")),
        ).manifest
        val definition = assertIs<WorldDefinitionDecodeResult.Success>(
            WorldDefinitionCodec.decode(resource("$folder/world.json")),
        ).definition
        val authoredEntries = listOf(
            "playable-world.json",
            "character-profile.json",
            "behaviors/activity-starts-quest.json",
            "behaviors/quest-raises-threat.json",
            "behaviors/timed-supply.json",
        ).map { path -> ArchiveEntry(path, (replacements[path] ?: resource("$folder/$path")).encodeToByteArray()) }
        return PlayableDraftCandidate(
            draftId = draftId,
            draftVersion = 1,
            packageBytes = WorldPackageBuilder.build(manifest, definition, authoredEntries + extraEntries),
            recognitionJob = recognitionJob,
        )
    }

    private fun recognitionJob(): RecognitionJobState {
        val text = "少年在钟楼废墟醒来，并决定寻找撤离车队。"
        val locator = SourceLocator(sourcePath = "story.txt", startCharacter = 0, endCharacterExclusive = text.length)
        val chunk = SourceChunk("source.chunk.1", "source.section.1", text, locator)
        val section = SourceSection("source.section.1", "开场", text, locator)
        val draft = RecognitionDraft(
            candidates = RecognitionCandidateKind.entries.mapIndexed { index, kind ->
                RecognitionCandidate(
                    id = "source.candidate.$index",
                    kind = kind,
                    label = kind.name,
                    sourceReferences = listOf(
                        RecognitionSourceReference(
                            chunk.id,
                            0,
                            text.length,
                            RecognitionConfidence.HIGH,
                            "作者已核对来源范围。",
                        ),
                    ),
                )
            },
        )
        return RecognitionJobState(
            jobId = "recognition.playable",
            revision = 3,
            sourceName = "story.txt",
            sourceFormat = SourceFormat.TXT,
            sourceHash = "b".repeat(64),
            stage = RecognitionStage.DRAFTED,
            status = RecognitionStatus.READY_FOR_REVIEW,
            checkpoint = RecognitionCheckpoint(RecognitionStage.DRAFTED, 3, 3, 1),
            document = SourceDocument(
                id = "source",
                format = SourceFormat.TXT,
                title = "开场",
                characterCount = text.length,
                sections = listOf(section),
                chunks = listOf(chunk),
            ),
            draft = draft,
        )
    }

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()
}

private class ToggleInstalledWorldStore : InstalledWorldStore {
    private val delegate = InMemoryInstalledWorldStore()
    var failPublish: Boolean = false

    override suspend fun current(worldId: io.worldloom.definition.DefinitionId): InstalledWorldRecord? =
        delegate.current(worldId)

    override suspend fun publish(
        expectedAddress: String?,
        record: InstalledWorldRecord,
    ): InstalledWorldPublishResult = if (failPublish) {
        InstalledWorldPublishResult.Failure("Injected copy failure")
    } else {
        delegate.publish(expectedAddress, record)
    }
}
