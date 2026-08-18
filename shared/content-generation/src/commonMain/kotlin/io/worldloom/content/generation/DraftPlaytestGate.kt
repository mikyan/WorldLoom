package io.worldloom.content.generation

import io.worldloom.definition.DefinitionId
import io.worldloom.rules.module.registry.RuleModuleRegistry
import io.worldloom.world.packageformat.ArchiveEntry
import io.worldloom.world.packageformat.ArchiveResult
import io.worldloom.world.packageformat.LoadedWorldPackage
import io.worldloom.world.packageformat.PlayableRouteSimulationResult
import io.worldloom.world.packageformat.StoredZipArchive
import io.worldloom.world.packageformat.WorldPackageLoadResult
import io.worldloom.world.packageformat.WorldPackageLoader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlayableDraftCandidate(
    val draftId: String,
    val draftVersion: Int,
    val packageBytes: ByteArray,
    val recognitionJob: RecognitionJobState? = null,
) {
    init {
        require(draftId.isNotBlank()) { "Draft id must not be blank" }
        require(draftVersion > 0) { "Draft version must be positive" }
    }

    override fun equals(other: Any?): Boolean = other is PlayableDraftCandidate &&
        draftId == other.draftId && draftVersion == other.draftVersion &&
        packageBytes.contentEquals(other.packageBytes) && recognitionJob == other.recognitionJob

    override fun hashCode(): Int = 31 * (31 * draftId.hashCode() + draftVersion) + packageBytes.contentHashCode()
}

enum class DraftPlayabilityProblemCode {
    PACKAGE_INVALID,
    PLAYABLE_CONTRACT_REQUIRED,
    GOLDEN_ROUTE_REQUIRED,
    ENDING_NOT_COVERED,
    ROUTE_INVALID,
    EXECUTABLE_CONTENT_FORBIDDEN,
    SOURCE_JOB_INVALID,
    SOURCE_MAPPING_INVALID,
}

data class DraftPlayabilityProblem(
    val code: DraftPlayabilityProblemCode,
    val path: String,
    val message: String,
)

class ValidatedPlayableDraft internal constructor(
    val candidate: PlayableDraftCandidate,
    val worldPackage: LoadedWorldPackage,
    val sourceAddress: String,
)

sealed interface DraftPlayabilityResult {
    data class Valid(val draft: ValidatedPlayableDraft) : DraftPlayabilityResult
    data class Invalid(val problems: List<DraftPlayabilityProblem>) : DraftPlayabilityResult
}

class DraftPlayabilityValidator(
    private val registry: RuleModuleRegistry,
) {
    fun validate(candidate: PlayableDraftCandidate): DraftPlayabilityResult {
        val executableProblems = forbiddenExecutableProblems(candidate.packageBytes)
        if (executableProblems.isNotEmpty()) return DraftPlayabilityResult.Invalid(executableProblems)
        val loaded = when (val result = WorldPackageLoader(registry).load(candidate.packageBytes)) {
            is WorldPackageLoadResult.Success -> result.worldPackage
            is WorldPackageLoadResult.Failure -> return DraftPlayabilityResult.Invalid(
                result.problems.mapIndexed { index, problem ->
                    val pathSeparator = problem.message.indexOf(": ")
                    DraftPlayabilityProblem(
                        DraftPlayabilityProblemCode.PACKAGE_INVALID,
                        if (pathSeparator > 0) problem.message.substring(0, pathSeparator) else "package[$index]",
                        "${problem.code.name}: ${problem.message}",
                    )
                },
            )
        }
        val contract = loaded.playableContract ?: return DraftPlayabilityResult.Invalid(
            listOf(
                DraftPlayabilityProblem(
                    DraftPlayabilityProblemCode.PLAYABLE_CONTRACT_REQUIRED,
                    "manifest.playableContractPath",
                    "A draft must declare a validated playable-world/v1 contract",
                ),
            ),
        )
        val problems = mutableListOf<DraftPlayabilityProblem>()
        if (contract.source.goldenRoutes.isEmpty()) {
            problems += DraftPlayabilityProblem(
                DraftPlayabilityProblemCode.GOLDEN_ROUTE_REQUIRED,
                "playable-world.goldenRoutes",
                "At least one authoritative golden route is required",
            )
        }
        val reachedEndings = mutableSetOf<DefinitionId>()
        contract.source.goldenRoutes.forEachIndexed { index, route ->
            when (val simulation = contract.simulate(route.id)) {
                is PlayableRouteSimulationResult.Complete -> reachedEndings += simulation.endingId
                is PlayableRouteSimulationResult.Failure -> problems += DraftPlayabilityProblem(
                    DraftPlayabilityProblemCode.ROUTE_INVALID,
                    "playable-world.goldenRoutes[$index]",
                    simulation.problem.message,
                )
            }
        }
        contract.source.endings.filter { it.id !in reachedEndings }.forEach { ending ->
            problems += DraftPlayabilityProblem(
                DraftPlayabilityProblemCode.ENDING_NOT_COVERED,
                "playable-world.endings[${ending.id.value}]",
                "Ending is not covered by a complete golden route",
            )
        }
        candidate.recognitionJob?.let { job -> problems += sourceMappingProblems(job) }
        return if (problems.isEmpty()) {
            DraftPlayabilityResult.Valid(
                ValidatedPlayableDraft(
                    candidate = candidate,
                    worldPackage = loaded,
                    sourceAddress = "sha256:${SourceFingerprint.sha256(candidate.packageBytes)}",
                ),
            )
        } else {
            DraftPlayabilityResult.Invalid(problems)
        }
    }

    private fun forbiddenExecutableProblems(packageBytes: ByteArray): List<DraftPlayabilityProblem> {
        val entries = (StoredZipArchive.decode(packageBytes) as? ArchiveResult.Success)?.entries.orEmpty()
        val forbiddenExtensions = setOf("js", "mjs", "lua", "kt", "kts", "class", "jar", "dex", "wasm", "so", "dll", "dylib")
        return entries.mapNotNull { entry ->
            val extension = entry.path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            if (extension in forbiddenExtensions) {
                DraftPlayabilityProblem(
                    DraftPlayabilityProblemCode.EXECUTABLE_CONTENT_FORBIDDEN,
                    entry.path,
                    "World drafts may contain only declarative data and validated Behavior AST",
                )
            } else {
                null
            }
        }
    }

    private fun sourceMappingProblems(job: RecognitionJobState): List<DraftPlayabilityProblem> {
        val document = job.document
        val draft = job.draft
        if (
            job.status != RecognitionStatus.READY_FOR_REVIEW || job.stage != RecognitionStage.DRAFTED ||
            document == null || draft == null || job.checkpoint.stage != RecognitionStage.DRAFTED ||
            job.checkpoint.completedUnits != job.checkpoint.totalUnits ||
            job.checkpoint.publishedDraftVersion != draft.version
        ) {
            return listOf(
                DraftPlayabilityProblem(
                    DraftPlayabilityProblemCode.SOURCE_JOB_INVALID,
                    "recognitionJob",
                    "Source-backed drafts require a completed recognition review checkpoint",
                ),
            )
        }
        val chunks = document.chunks.associateBy(SourceChunk::id)
        return buildList {
            RecognitionCandidateKind.entries
                .filterNot { requiredKind -> draft.candidates.any { it.kind == requiredKind } }
                .forEach { missingKind ->
                    add(
                        DraftPlayabilityProblem(
                            DraftPlayabilityProblemCode.SOURCE_MAPPING_INVALID,
                            "recognitionJob.draft.candidates[${missingKind.name}]",
                            "Source-backed draft is missing a ${missingKind.name} candidate",
                        ),
                    )
                }
            draft.candidates.forEachIndexed { candidateIndex, candidate ->
                if (candidate.sourceReferences.isEmpty()) {
                    add(
                        DraftPlayabilityProblem(
                            DraftPlayabilityProblemCode.SOURCE_MAPPING_INVALID,
                            "recognitionJob.draft.candidates[$candidateIndex].sourceReferences",
                            "Candidate source mapping is missing",
                        ),
                    )
                }
                candidate.sourceReferences.forEachIndexed { referenceIndex, reference ->
                    val chunk = chunks[reference.sourceFragmentId]
                    if (
                        chunk == null || reference.startCharacter < chunk.locator.startCharacter ||
                        reference.endCharacterExclusive > chunk.locator.endCharacterExclusive ||
                        reference.startCharacter >= reference.endCharacterExclusive || reference.diagnostic.isNullOrBlank()
                    ) {
                        add(
                            DraftPlayabilityProblem(
                                DraftPlayabilityProblemCode.SOURCE_MAPPING_INVALID,
                                "recognitionJob.draft.candidates[$candidateIndex].sourceReferences[$referenceIndex]",
                                "Candidate source mapping or confidence diagnostic is invalid",
                            ),
                        )
                    }
                }
            }
        }
    }
}

data class InstalledWorldRecord(
    val worldId: DefinitionId,
    val contentVersion: Int,
    val contentAddress: String,
    val draftId: String,
    val draftVersion: Int,
    val packageBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is InstalledWorldRecord &&
        worldId == other.worldId && contentVersion == other.contentVersion && contentAddress == other.contentAddress &&
        draftId == other.draftId && draftVersion == other.draftVersion && packageBytes.contentEquals(other.packageBytes)

    override fun hashCode(): Int = 31 * contentAddress.hashCode() + packageBytes.contentHashCode()
}

sealed interface InstalledWorldPublishResult {
    data object Published : InstalledWorldPublishResult
    data class Conflict(val current: InstalledWorldRecord?) : InstalledWorldPublishResult
    data class Failure(val message: String) : InstalledWorldPublishResult
}

interface InstalledWorldStore {
    suspend fun current(worldId: DefinitionId): InstalledWorldRecord?
    suspend fun publish(expectedAddress: String?, record: InstalledWorldRecord): InstalledWorldPublishResult
}

class InMemoryInstalledWorldStore : InstalledWorldStore {
    private val mutex = Mutex()
    private val records = mutableMapOf<DefinitionId, InstalledWorldRecord>()

    override suspend fun current(worldId: DefinitionId): InstalledWorldRecord? = mutex.withLock {
        records[worldId]?.copy(packageBytes = records[worldId]!!.packageBytes.copyOf())
    }

    override suspend fun publish(
        expectedAddress: String?,
        record: InstalledWorldRecord,
    ): InstalledWorldPublishResult = mutex.withLock {
        val current = records[record.worldId]
        if (current?.contentAddress != expectedAddress) {
            InstalledWorldPublishResult.Conflict(current)
        } else {
            records[record.worldId] = record.copy(packageBytes = record.packageBytes.copyOf())
            InstalledWorldPublishResult.Published
        }
    }
}

enum class DraftInstallFailureCode { VALIDATION_FAILED, FINAL_VALIDATION_FAILED, PUBLISH_FAILED, PUBLISH_CONFLICT }

sealed interface DraftInstallResult {
    data class Installed(val record: InstalledWorldRecord) : DraftInstallResult
    data class Failure(
        val code: DraftInstallFailureCode,
        val message: String,
        val problems: List<DraftPlayabilityProblem> = emptyList(),
    ) : DraftInstallResult
}

class DraftInstaller(
    private val validator: DraftPlayabilityValidator,
    private val store: InstalledWorldStore,
) {
    suspend fun install(candidate: PlayableDraftCandidate): DraftInstallResult {
        val validated = when (val result = validator.validate(candidate)) {
            is DraftPlayabilityResult.Valid -> result.draft
            is DraftPlayabilityResult.Invalid -> return DraftInstallResult.Failure(
                DraftInstallFailureCode.VALIDATION_FAILED,
                "Draft did not pass the playability gate",
                result.problems,
            )
        }
        val sanitizedBytes = sanitize(validated)
        val sanitizedCandidate = candidate.copy(packageBytes = sanitizedBytes)
        val final = when (val result = validator.validate(sanitizedCandidate)) {
            is DraftPlayabilityResult.Valid -> result.draft
            is DraftPlayabilityResult.Invalid -> return DraftInstallResult.Failure(
                DraftInstallFailureCode.FINAL_VALIDATION_FAILED,
                "Sanitized package did not pass final validation",
                result.problems,
            )
        }
        val contract = requireNotNull(final.worldPackage.playableContract)
        val record = InstalledWorldRecord(
            worldId = final.worldPackage.manifest.worldId,
            contentVersion = contract.source.contentVersion,
            contentAddress = "sha256:${SourceFingerprint.sha256(sanitizedBytes)}",
            draftId = candidate.draftId,
            draftVersion = candidate.draftVersion,
            packageBytes = sanitizedBytes,
        )
        val previous = store.current(record.worldId)
        return when (val result = store.publish(previous?.contentAddress, record)) {
            InstalledWorldPublishResult.Published -> DraftInstallResult.Installed(record)
            is InstalledWorldPublishResult.Conflict -> DraftInstallResult.Failure(
                DraftInstallFailureCode.PUBLISH_CONFLICT,
                "Installed world changed concurrently",
            )
            is InstalledWorldPublishResult.Failure -> DraftInstallResult.Failure(
                DraftInstallFailureCode.PUBLISH_FAILED,
                result.message,
            )
        }
    }

    private fun sanitize(draft: ValidatedPlayableDraft): ByteArray {
        val contract = requireNotNull(draft.worldPackage.playableContract).source
        val allowed = buildSet {
            add("manifest.json")
            add(draft.worldPackage.manifest.worldDefinitionPath)
            draft.worldPackage.manifest.playableContractPath?.let(::add)
            contract.character.profilePath?.let(::add)
            contract.behaviors.mapTo(this) { it.path }
        }
        val entries = draft.worldPackage.entries
            .filterKeys(allowed::contains)
            .map { (path, content) -> ArchiveEntry(path, content.copyOf()) }
            .sortedBy(ArchiveEntry::path)
        return StoredZipArchive.encode(entries)
    }
}
