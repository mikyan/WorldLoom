package io.worldloom.content.generation

import io.worldloom.behavior.runtime.BehaviorDefinition
import io.worldloom.behavior.runtime.BehaviorValidationResult
import io.worldloom.behavior.runtime.BehaviorValidator
import io.worldloom.content.schema.CharacterCreationProfile
import io.worldloom.content.schema.CharacterCreationProfileValidator
import io.worldloom.content.schema.CharacterProfileValidationResult
import io.worldloom.content.schema.ContentProfileCodec
import io.worldloom.content.schema.RuleProfile
import io.worldloom.content.schema.RuleProfileValidationResult
import io.worldloom.content.schema.RuleProfileValidator
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.ValueType
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.WorldDefinitionValidator
import io.worldloom.rules.module.api.WorldManifest
import io.worldloom.rules.module.registry.RuleModuleRegistry
import io.worldloom.world.InitialGameStateFactory
import io.worldloom.world.RunId
import io.worldloom.world.packageformat.ArchiveEntry
import io.worldloom.world.packageformat.WorldPackageBuilder
import io.worldloom.world.packageformat.WorldPackageLoadResult
import io.worldloom.world.packageformat.WorldPackageLoader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WorldOutline(
    val title: String,
    val premise: String,
    val entityNames: List<String>,
    val ambiguities: List<String> = emptyList(),
)

@Serializable
data class GeneratedBehaviorDraft(
    val behavior: BehaviorDefinition,
    val pathTypes: Map<String, ValueType>,
)

@Serializable
data class SourceMapping(
    val targetId: String,
    val sourceChunkIds: List<String>,
)

@Serializable
data class GeneratedWorldDraft(
    val manifest: WorldManifest,
    val definition: WorldDefinition,
    val characterCreation: CharacterCreationProfile,
    val rules: RuleProfile,
    val behaviors: List<GeneratedBehaviorDraft> = emptyList(),
    val sourceMappings: List<SourceMapping>,
    val reviewQuestions: List<String> = emptyList(),
)

data class WorldGenerationRequest(
    val jobId: String,
    val worldId: DefinitionId,
    val title: String? = null,
    val author: String = "Worldloom",
    val language: String = "zh-Hans",
    val includeSourceInPackage: Boolean = false,
) {
    init {
        require(jobId.isNotBlank()) { "Generation job id must not be blank" }
        require(title == null || title.isNotBlank()) { "World title must not be blank" }
        require(author.isNotBlank() && language.isNotBlank()) { "World author and language must not be blank" }
    }
}

interface WorldCreationModel {
    suspend fun outline(request: WorldGenerationRequest, document: SourceDocument): WorldOutline

    suspend fun draft(
        request: WorldGenerationRequest,
        document: SourceDocument,
        outline: WorldOutline,
    ): GeneratedWorldDraft
}

enum class GenerationStage {
    RECEIVED,
    INGESTED,
    OUTLINED,
    DRAFTED,
    VALIDATED,
    SIMULATED,
    PACKAGED,
    COMPLETED,
}

data class GenerationProgress(
    val jobId: String,
    val stage: GenerationStage,
    val completedUnits: Int,
    val totalUnits: Int,
    val message: String,
)

fun interface GenerationProgressListener {
    suspend fun onProgress(progress: GenerationProgress)
}

data class GenerationTaskState(
    val jobId: String,
    val stage: GenerationStage,
    val source: SourceDocument? = null,
    val outline: WorldOutline? = null,
    val draft: GeneratedWorldDraft? = null,
    val packageBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is GenerationTaskState &&
        jobId == other.jobId && stage == other.stage && source == other.source && outline == other.outline &&
        draft == other.draft && packageBytes.contentEqualsNullable(other.packageBytes)

    override fun hashCode(): Int = 31 * (31 * jobId.hashCode() + stage.hashCode()) + (packageBytes?.contentHashCode() ?: 0)
}

interface GenerationTaskStore {
    suspend fun load(jobId: String): GenerationTaskState?
    suspend fun save(state: GenerationTaskState)
}

class InMemoryGenerationTaskStore : GenerationTaskStore {
    private val mutex = Mutex()
    private val states = mutableMapOf<String, GenerationTaskState>()

    override suspend fun load(jobId: String): GenerationTaskState? = mutex.withLock {
        states[jobId]?.copy(packageBytes = states[jobId]?.packageBytes?.copyOf())
    }

    override suspend fun save(state: GenerationTaskState) {
        mutex.withLock { states[state.jobId] = state.copy(packageBytes = state.packageBytes?.copyOf()) }
    }
}

data class ContentValidationProblem(val path: String, val message: String)

sealed interface ContentDraftValidationResult {
    data class Valid(val draft: GeneratedWorldDraft) : ContentDraftValidationResult
    data class Invalid(val problems: List<ContentValidationProblem>) : ContentDraftValidationResult
}

class ContentDraftValidator(
    private val registry: RuleModuleRegistry,
) {
    fun validate(draft: GeneratedWorldDraft, document: SourceDocument): ContentDraftValidationResult {
        val definition = when (val result = WorldDefinitionValidator.validate(draft.definition)) {
            is DefinitionValidationResult.Valid -> result.definition
            is DefinitionValidationResult.Invalid -> return ContentDraftValidationResult.Invalid(
                result.problems.map { ContentValidationProblem("definition.${it.path}", it.message) },
            )
        }
        val problems = mutableListOf<ContentValidationProblem>()
        if (draft.manifest.worldId != draft.definition.id) {
            problems += ContentValidationProblem("manifest.worldId", "Manifest world id does not match the definition")
        }
        when (val result = CharacterCreationProfileValidator.validate(draft.characterCreation, definition)) {
            is CharacterProfileValidationResult.Valid -> Unit
            is CharacterProfileValidationResult.Invalid -> problems += result.problems.map {
                ContentValidationProblem("characterCreation.${it.path}", it.message)
            }
        }
        when (val result = RuleProfileValidator.validate(draft.rules, draft.manifest, definition, registry)) {
            is RuleProfileValidationResult.Valid -> Unit
            is RuleProfileValidationResult.Invalid -> problems += result.problems.map {
                ContentValidationProblem("rules.${it.path}", it.message)
            }
        }
        draft.behaviors.forEachIndexed { index, behavior ->
            when (val result = BehaviorValidator.validate(behavior.behavior, definition, behavior.pathTypes)) {
                is BehaviorValidationResult.Valid -> Unit
                is BehaviorValidationResult.Invalid -> problems += result.problems.map {
                    ContentValidationProblem("behaviors[$index].${it.path}", it.message)
                }
            }
        }
        val chunkIds = document.chunks.map(SourceChunk::id).toSet()
        draft.sourceMappings.forEachIndexed { index, mapping ->
            if (mapping.targetId.isBlank()) problems += ContentValidationProblem("sourceMappings[$index].targetId", "Target id is blank")
            if (mapping.sourceChunkIds.isEmpty() || mapping.sourceChunkIds.any { it !in chunkIds }) {
                problems += ContentValidationProblem("sourceMappings[$index].sourceChunkIds", "Source mapping is empty or invalid")
            }
        }
        return if (problems.isEmpty()) ContentDraftValidationResult.Valid(draft)
        else ContentDraftValidationResult.Invalid(problems)
    }

    fun simulate(draft: GeneratedWorldDraft): ContentValidationProblem? {
        val definition = (WorldDefinitionValidator.validate(draft.definition) as? DefinitionValidationResult.Valid)?.definition
            ?: return ContentValidationProblem("simulation", "World definition is not valid")
        return try {
            val state = InitialGameStateFactory.create(definition, RunId("generation.simulation"))
            if (state.lastSequence != 0L) ContentValidationProblem("simulation", "Initial sequence is not zero") else null
        } catch (_: Exception) {
            ContentValidationProblem("simulation", "World could not create an initial state")
        }
    }
}

data class PublishedWorld(
    val packageBytes: ByteArray,
    val outline: WorldOutline,
    val draft: GeneratedWorldDraft,
) {
    override fun equals(other: Any?): Boolean = other is PublishedWorld &&
        packageBytes.contentEquals(other.packageBytes) && outline == other.outline && draft == other.draft
    override fun hashCode(): Int = packageBytes.contentHashCode()
}

sealed interface GenerationResult {
    data class Success(val world: PublishedWorld) : GenerationResult
    data class Cancelled(val stage: GenerationStage) : GenerationResult
    data class Failure(val stage: GenerationStage, val problems: List<ContentValidationProblem>) : GenerationResult
}

enum class CorpusFileType { TXT, EPUB }

class WorldGenerationPipeline(
    private val model: WorldCreationModel,
    private val registry: RuleModuleRegistry,
    private val taskStore: GenerationTaskStore = InMemoryGenerationTaskStore(),
    private val txtIngestor: TxtIngestor = TxtIngestor(),
    private val epubIngestor: EpubIngestor = EpubIngestor(StoredEpubArchiveReader),
) {
    private val validator = ContentDraftValidator(registry)

    suspend fun runBrief(
        request: WorldGenerationRequest,
        brief: String,
        cancellation: CancellationProbe = CancellationProbe { false },
        progress: GenerationProgressListener = GenerationProgressListener { _ -> },
    ): GenerationResult {
        var state = taskStore.load(request.jobId) ?: GenerationTaskState(request.jobId, GenerationStage.RECEIVED)
        if (state.source == null) {
            val normalized = normalizeSourceText(brief)
            val count = unicodeCharacterCount(normalized)
            if (normalized.isBlank()) return failure(GenerationStage.RECEIVED, "brief", "Brief is empty")
            if (count > MAX_BRIEF_CHARACTERS) return failure(GenerationStage.RECEIVED, "brief", "Brief exceeds $MAX_BRIEF_CHARACTERS characters")
            val section = SourceSection(
                id = "${request.jobId}.section.0",
                title = request.title ?: normalized.lineSequence().first().take(80),
                text = normalized,
                locator = SourceLocator(startCharacter = 0, endCharacterExclusive = normalized.length),
            )
            val document = SourceDocument(
                id = request.jobId,
                format = SourceFormat.BRIEF,
                title = section.title,
                characterCount = count,
                sections = listOf(section),
                chunks = chunkSections(request.jobId, listOf(section)),
            )
            state = state.copy(stage = GenerationStage.INGESTED, source = document)
            taskStore.save(state)
            progress.emit(request.jobId, GenerationStage.INGESTED, "Brief normalized")
        }
        return continueGeneration(request, state, brief.encodeToByteArray(), cancellation, progress)
    }

    suspend fun runCorpus(
        request: WorldGenerationRequest,
        fileName: String,
        fileType: CorpusFileType,
        source: ByteArray,
        cancellation: CancellationProbe = CancellationProbe { false },
        progress: GenerationProgressListener = GenerationProgressListener { _ -> },
    ): GenerationResult {
        var state = taskStore.load(request.jobId) ?: GenerationTaskState(request.jobId, GenerationStage.RECEIVED)
        if (state.source == null) {
            val ingested = when (fileType) {
                CorpusFileType.TXT -> txtIngestor.ingest(request.jobId, fileName, source, cancellation)
                CorpusFileType.EPUB -> epubIngestor.ingest(request.jobId, fileName, source, cancellation)
            }
            when (ingested) {
                is SourceIngestResult.Failure -> return if (ingested.problem.code == SourceIngestProblemCode.CANCELLED) {
                    GenerationResult.Cancelled(GenerationStage.RECEIVED)
                } else {
                    failure(GenerationStage.RECEIVED, "source", ingested.problem.message)
                }
                is SourceIngestResult.Success -> {
                    state = state.copy(stage = GenerationStage.INGESTED, source = ingested.document)
                    taskStore.save(state)
                    progress.emit(request.jobId, GenerationStage.INGESTED, "Corpus parsed and chunked")
                }
            }
        }
        return continueGeneration(request, state, source, cancellation, progress)
    }

    private suspend fun continueGeneration(
        request: WorldGenerationRequest,
        initialState: GenerationTaskState,
        originalSource: ByteArray,
        cancellation: CancellationProbe,
        progress: GenerationProgressListener,
    ): GenerationResult {
        var state = initialState
        val document = requireNotNull(state.source)
        if (cancellation.isCancelled()) return GenerationResult.Cancelled(state.stage)
        if (state.outline == null) {
            val outline = model.outline(request, document)
            state = state.copy(stage = GenerationStage.OUTLINED, outline = outline)
            taskStore.save(state)
            progress.emit(request.jobId, GenerationStage.OUTLINED, "Outline extracted")
        }
        if (cancellation.isCancelled()) return GenerationResult.Cancelled(state.stage)
        if (state.draft == null) {
            val draft = model.draft(request, document, requireNotNull(state.outline))
            state = state.copy(stage = GenerationStage.DRAFTED, draft = draft)
            taskStore.save(state)
            progress.emit(request.jobId, GenerationStage.DRAFTED, "World draft generated")
        }
        if (cancellation.isCancelled()) return GenerationResult.Cancelled(state.stage)
        val draft = requireNotNull(state.draft)
        when (val validation = validator.validate(draft, document)) {
            is ContentDraftValidationResult.Invalid -> return GenerationResult.Failure(GenerationStage.DRAFTED, validation.problems)
            is ContentDraftValidationResult.Valid -> Unit
        }
        state = state.copy(stage = GenerationStage.VALIDATED)
        taskStore.save(state)
        progress.emit(request.jobId, GenerationStage.VALIDATED, "Draft validated")
        validator.simulate(draft)?.let { return GenerationResult.Failure(GenerationStage.VALIDATED, listOf(it)) }
        state = state.copy(stage = GenerationStage.SIMULATED)
        taskStore.save(state)
        progress.emit(request.jobId, GenerationStage.SIMULATED, "Quick simulation passed")
        if (cancellation.isCancelled()) return GenerationResult.Cancelled(state.stage)
        val packageBytes = state.packageBytes ?: buildPackage(request, document, draft, originalSource).also { bytes ->
            state = state.copy(stage = GenerationStage.PACKAGED, packageBytes = bytes)
            taskStore.save(state)
            progress.emit(request.jobId, GenerationStage.PACKAGED, "World package built")
        }
        when (val loaded = WorldPackageLoader(registry).load(packageBytes)) {
            is WorldPackageLoadResult.Success -> Unit
            is WorldPackageLoadResult.Failure -> return GenerationResult.Failure(
                GenerationStage.PACKAGED,
                loaded.problems.map { ContentValidationProblem("package", it.message) },
            )
        }
        state = state.copy(stage = GenerationStage.COMPLETED, packageBytes = packageBytes)
        taskStore.save(state)
        progress.emit(request.jobId, GenerationStage.COMPLETED, "World published")
        return GenerationResult.Success(PublishedWorld(packageBytes, requireNotNull(state.outline), draft))
    }

    private fun buildPackage(
        request: WorldGenerationRequest,
        document: SourceDocument,
        draft: GeneratedWorldDraft,
        originalSource: ByteArray,
    ): ByteArray {
        val json = Json { encodeDefaults = true; explicitNulls = false; prettyPrint = true }
        val metadata = GenerationMetadata(
            jobId = request.jobId,
            sourceDocumentId = document.id,
            sourceMappings = draft.sourceMappings,
            reviewQuestions = draft.reviewQuestions,
        )
        val entries = mutableListOf(
            ArchiveEntry(
                "definitions/character-creation.json",
                ContentProfileCodec.encodeCharacterCreation(draft.characterCreation).encodeToByteArray(),
            ),
            ArchiveEntry("definitions/rules.json", ContentProfileCodec.encodeRuleProfile(draft.rules).encodeToByteArray()),
            ArchiveEntry("generation.json", json.encodeToString(metadata).encodeToByteArray()),
        )
        draft.behaviors.forEach { behavior ->
            entries += ArchiveEntry(
                "behaviors/${behavior.behavior.id.value}.json",
                io.worldloom.behavior.runtime.BehaviorCodec.encode(behavior.behavior).encodeToByteArray(),
            )
        }
        if (request.includeSourceInPackage) {
            val extension = when (document.format) {
                SourceFormat.EPUB -> "epub"
                SourceFormat.TXT -> "txt"
                SourceFormat.BRIEF -> "txt"
            }
            entries += ArchiveEntry("sources/original.$extension", originalSource)
        }
        return WorldPackageBuilder.build(draft.manifest, draft.definition, entries)
    }

    private fun failure(stage: GenerationStage, path: String, message: String) =
        GenerationResult.Failure(stage, listOf(ContentValidationProblem(path, message)))
}

@Serializable
private data class GenerationMetadata(
    val jobId: String,
    val sourceDocumentId: String,
    val sourceMappings: List<SourceMapping>,
    val reviewQuestions: List<String>,
)

private suspend fun GenerationProgressListener.emit(jobId: String, stage: GenerationStage, message: String) {
    onProgress(GenerationProgress(jobId, stage, stage.ordinal, GenerationStage.COMPLETED.ordinal, message))
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null && other == null -> true
    this == null || other == null -> false
    else -> contentEquals(other)
}
