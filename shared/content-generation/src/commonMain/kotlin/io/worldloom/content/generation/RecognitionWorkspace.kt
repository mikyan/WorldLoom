package io.worldloom.content.generation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

const val CURRENT_RECOGNITION_JOB_SCHEMA_VERSION: Int = 1
const val CURRENT_RECOGNITION_DRAFT_SCHEMA_VERSION: Int = 1

@Serializable
enum class RecognitionStage { RECEIVED, PARSING, PARSED, RECOGNIZING, DRAFTED, CANCELLED, FAILED, SOURCE_CHANGED }

@Serializable
enum class RecognitionStatus { RUNNING, READY_FOR_REVIEW, CANCELLED, FAILED, SOURCE_CHANGED }

@Serializable
enum class RecognitionDiagnosticSeverity { INFO, WARNING, ERROR }

@Serializable
enum class RecognitionCandidateKind { CHARACTER, LOCATION, SCENE, OBJECTIVE, CANDIDATE_FACT }

@Serializable
enum class RecognitionConfidence { HIGH, MEDIUM, LOW }

@Serializable
data class RecognitionDiagnostic(
    val code: String,
    val severity: RecognitionDiagnosticSeverity,
    val message: String,
    val candidateId: String? = null,
    val sourceFragmentId: String? = null,
)

@Serializable
data class RecognitionSourceReference(
    val sourceFragmentId: String,
    val startCharacter: Int,
    val endCharacterExclusive: Int,
    val confidence: RecognitionConfidence,
    val diagnostic: String? = null,
)

@Serializable
data class RecognitionCandidate(
    val id: String,
    val kind: RecognitionCandidateKind,
    val label: String,
    val sourceReferences: List<RecognitionSourceReference>,
    val diagnostics: List<RecognitionDiagnostic> = emptyList(),
)

@Serializable
data class RecognitionDraft(
    val schemaVersion: Int = CURRENT_RECOGNITION_DRAFT_SCHEMA_VERSION,
    val version: Int = 1,
    val candidates: List<RecognitionCandidate>,
    val reviewQuestions: List<String> = emptyList(),
) {
    init {
        require(schemaVersion == CURRENT_RECOGNITION_DRAFT_SCHEMA_VERSION) {
            "Unsupported recognition draft schema version"
        }
        require(version > 0) { "Recognition draft version must be positive" }
    }
}

@Serializable
data class RecognitionCheckpoint(
    val stage: RecognitionStage,
    val completedUnits: Int,
    val totalUnits: Int,
    val publishedDraftVersion: Int? = null,
)

@Serializable
data class RecognitionJobState(
    val schemaVersion: Int = CURRENT_RECOGNITION_JOB_SCHEMA_VERSION,
    val jobId: String,
    val revision: Long = 0,
    val sourceName: String,
    val sourceFormat: SourceFormat,
    val sourceHash: String,
    val stage: RecognitionStage = RecognitionStage.RECEIVED,
    val status: RecognitionStatus = RecognitionStatus.RUNNING,
    val checkpoint: RecognitionCheckpoint = RecognitionCheckpoint(RecognitionStage.RECEIVED, 0, 3),
    val document: SourceDocument? = null,
    val draft: RecognitionDraft? = null,
    val diagnostics: List<RecognitionDiagnostic> = emptyList(),
) {
    init {
        require(schemaVersion == CURRENT_RECOGNITION_JOB_SCHEMA_VERSION) {
            "Unsupported recognition job schema version"
        }
        require(jobId.isNotBlank() && sourceName.isNotBlank()) { "Recognition job identity must not be blank" }
        require(sourceHash.length == 64 && sourceHash.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Recognition source hash must be lowercase SHA-256"
        }
        require(revision >= 0) { "Recognition job revision must not be negative" }
        require(checkpoint.completedUnits in 0..checkpoint.totalUnits && checkpoint.totalUnits > 0) {
            "Recognition progress is outside its bounds"
        }
    }
}

sealed interface RecognitionJobCreateResult {
    data object Created : RecognitionJobCreateResult
    data class Existing(val state: RecognitionJobState) : RecognitionJobCreateResult
}

sealed interface RecognitionJobUpdateResult {
    data object Updated : RecognitionJobUpdateResult
    data class Conflict(val current: RecognitionJobState?) : RecognitionJobUpdateResult
}

interface RecognitionJobStore {
    suspend fun create(state: RecognitionJobState): RecognitionJobCreateResult
    suspend fun load(jobId: String): RecognitionJobState?
    suspend fun update(expectedRevision: Long, state: RecognitionJobState): RecognitionJobUpdateResult
    suspend fun list(): List<RecognitionJobState>
}

class InMemoryRecognitionJobStore : RecognitionJobStore {
    private val mutex = Mutex()
    private val jobs = mutableMapOf<String, RecognitionJobState>()

    override suspend fun create(state: RecognitionJobState): RecognitionJobCreateResult = mutex.withLock {
        val existing = jobs[state.jobId]
        if (existing == null) {
            jobs[state.jobId] = state
            RecognitionJobCreateResult.Created
        } else {
            RecognitionJobCreateResult.Existing(existing)
        }
    }

    override suspend fun load(jobId: String): RecognitionJobState? = mutex.withLock { jobs[jobId] }

    override suspend fun update(
        expectedRevision: Long,
        state: RecognitionJobState,
    ): RecognitionJobUpdateResult = mutex.withLock {
        val current = jobs[state.jobId]
        if (current == null || current.revision != expectedRevision || state.revision != expectedRevision + 1) {
            RecognitionJobUpdateResult.Conflict(current)
        } else {
            jobs[state.jobId] = state
            RecognitionJobUpdateResult.Updated
        }
    }

    override suspend fun list(): List<RecognitionJobState> = mutex.withLock { jobs.values.sortedBy { it.jobId } }
}

data class RecognitionRequest(
    val jobId: String,
    val sourceName: String,
    val fileType: CorpusFileType,
) {
    init {
        require(jobId.isNotBlank() && sourceName.isNotBlank()) { "Recognition request identity must not be blank" }
    }
}

fun interface SourceRecognitionModel {
    suspend fun recognize(document: SourceDocument): RecognitionDraft
}

/** Deterministic offline baseline; a Provider-backed recognizer can replace it without changing job semantics. */
class DeterministicSourceRecognitionModel : SourceRecognitionModel {
    override suspend fun recognize(document: SourceDocument): RecognitionDraft {
        val fragments = document.chunks.filter { it.text.isNotBlank() }.ifEmpty {
            return RecognitionDraft(
                candidates = emptyList(),
                reviewQuestions = listOf("来源中没有可识别的文本片段。"),
            )
        }
        val kinds = RecognitionCandidateKind.entries
        val candidates = kinds.mapIndexed { index, kind ->
            val fragment = fragments[index % fragments.size]
            val label = fragment.text.lineSequence().firstOrNull { it.isNotBlank() }
                ?.trim()?.take(80)?.ifBlank { null }
                ?: "${kind.name.lowercase()} ${index + 1}"
            RecognitionCandidate(
                id = "${document.id}.candidate.${kind.name.lowercase()}.1",
                kind = kind,
                label = label,
                sourceReferences = listOf(
                    RecognitionSourceReference(
                        sourceFragmentId = fragment.id,
                        startCharacter = fragment.locator.startCharacter,
                        endCharacterExclusive = fragment.locator.endCharacterExclusive,
                        confidence = RecognitionConfidence.MEDIUM,
                        diagnostic = "离线基线识别，需要作者复核。",
                    ),
                ),
            )
        }
        return RecognitionDraft(
            candidates = candidates,
            reviewQuestions = listOf("确认候选场景顺序、初始场景和至少一个可达结局。"),
        )
    }
}

fun interface RecognitionProgressListener {
    suspend fun onProgress(job: RecognitionJobState)
}

enum class RecognitionFailureCode { SOURCE_CHANGED, INGEST_FAILED, INVALID_MAPPING, STORE_CONFLICT, FAILED }

sealed interface RecognitionResult {
    data class Ready(val job: RecognitionJobState) : RecognitionResult
    data class Cancelled(val job: RecognitionJobState) : RecognitionResult
    data class Failure(
        val code: RecognitionFailureCode,
        val message: String,
        val job: RecognitionJobState? = null,
    ) : RecognitionResult
}

class RecognitionCoordinator(
    private val store: RecognitionJobStore,
    private val model: SourceRecognitionModel = DeterministicSourceRecognitionModel(),
    private val txtIngestor: TxtIngestor = TxtIngestor(),
    private val epubIngestor: EpubIngestor = EpubIngestor(StoredEpubArchiveReader),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun run(
        request: RecognitionRequest,
        source: ByteArray,
        cancellation: CancellationProbe = CancellationProbe { false },
        progress: RecognitionProgressListener = RecognitionProgressListener { _ -> },
    ): RecognitionResult = withContext(workerDispatcher) {
        val hash = SourceFingerprint.sha256(source)
        var job = when (val existing = store.load(request.jobId)) {
            null -> {
                val created = RecognitionJobState(
                    jobId = request.jobId,
                    sourceName = request.sourceName,
                    sourceFormat = request.fileType.toSourceFormat(),
                    sourceHash = hash,
                )
                when (val result = store.create(created)) {
                    RecognitionJobCreateResult.Created -> created
                    is RecognitionJobCreateResult.Existing -> result.state
                }
            }
            else -> existing
        }
        if (job.sourceHash != hash || job.sourceName != request.sourceName || job.sourceFormat != request.fileType.toSourceFormat()) {
            val changed = job.next(
                stage = RecognitionStage.SOURCE_CHANGED,
                status = RecognitionStatus.SOURCE_CHANGED,
                diagnostics = job.diagnostics + RecognitionDiagnostic(
                    code = "SOURCE_CHANGED",
                    severity = RecognitionDiagnosticSeverity.ERROR,
                    message = "来源内容或身份已变化；请创建新的识别任务版本。",
                ),
            )
            persist(job, changed)?.let { return@withContext it }
            return@withContext RecognitionResult.Failure(
                RecognitionFailureCode.SOURCE_CHANGED,
                "Source does not match the recognition checkpoint",
                changed,
            )
        }
        if (job.status == RecognitionStatus.SOURCE_CHANGED) {
            return@withContext RecognitionResult.Failure(
                RecognitionFailureCode.SOURCE_CHANGED,
                "Source-changed jobs require a new job id",
                job,
            )
        }
        if (job.status == RecognitionStatus.READY_FOR_REVIEW && job.draft != null && job.document != null) {
            return@withContext RecognitionResult.Ready(job)
        }

        val coroutineJob = currentCoroutineContext()[Job]
        val combinedCancellation = CancellationProbe {
            cancellation.isCancelled() || coroutineJob?.isActive == false
        }
        try {
            currentCoroutineContext().ensureActive()
            if (job.document == null) {
                val parsing = job.next(
                    stage = RecognitionStage.PARSING,
                    status = RecognitionStatus.RUNNING,
                    checkpoint = RecognitionCheckpoint(RecognitionStage.PARSING, 0, 3),
                    draft = null,
                    diagnostics = emptyList(),
                )
                persist(job, parsing)?.let { return@withContext it }
                job = parsing
                progress.onProgress(job)
                val ingested = when (request.fileType) {
                    CorpusFileType.TXT -> txtIngestor.ingest(request.jobId, request.sourceName, source, combinedCancellation)
                    CorpusFileType.EPUB -> epubIngestor.ingest(request.jobId, request.sourceName, source, combinedCancellation)
                }
                when (ingested) {
                    is SourceIngestResult.Failure -> {
                        if (ingested.problem.code == SourceIngestProblemCode.CANCELLED) {
                            return@withContext cancelPersisted(job)
                        }
                        val failed = job.next(
                            stage = RecognitionStage.FAILED,
                            status = RecognitionStatus.FAILED,
                            document = null,
                            draft = null,
                            diagnostics = listOf(
                                RecognitionDiagnostic(
                                    "INGEST_FAILED",
                                    RecognitionDiagnosticSeverity.ERROR,
                                    ingested.problem.message.take(240),
                                ),
                            ),
                        )
                        persist(job, failed)?.let { return@withContext it }
                        return@withContext RecognitionResult.Failure(
                            RecognitionFailureCode.INGEST_FAILED,
                            "Source could not be parsed",
                            failed,
                        )
                    }
                    is SourceIngestResult.Success -> {
                        val parsed = job.next(
                            stage = RecognitionStage.PARSED,
                            checkpoint = RecognitionCheckpoint(RecognitionStage.PARSED, 1, 3),
                            document = ingested.document,
                        )
                        persist(job, parsed)?.let { return@withContext it }
                        job = parsed
                        progress.onProgress(job)
                    }
                }
            }
            if (combinedCancellation.isCancelled()) return@withContext cancelPersisted(job)
            currentCoroutineContext().ensureActive()
            if (job.draft == null) {
                val recognizing = job.next(
                    stage = RecognitionStage.RECOGNIZING,
                    checkpoint = RecognitionCheckpoint(RecognitionStage.RECOGNIZING, 1, 3),
                )
                persist(job, recognizing)?.let { return@withContext it }
                job = recognizing
                progress.onProgress(job)
                val draft = model.recognize(requireNotNull(job.document))
                currentCoroutineContext().ensureActive()
                val mappingDiagnostics = validateMappings(requireNotNull(job.document), draft)
                val reviewDiagnostics = mappingDiagnostics + playabilityDiagnostics(draft)
                val drafted = job.next(
                    stage = RecognitionStage.DRAFTED,
                    status = RecognitionStatus.READY_FOR_REVIEW,
                    checkpoint = RecognitionCheckpoint(RecognitionStage.DRAFTED, 3, 3, draft.version),
                    draft = draft,
                    diagnostics = reviewDiagnostics,
                )
                persist(job, drafted)?.let { return@withContext it }
                job = drafted
                progress.onProgress(job)
            }
            RecognitionResult.Ready(job)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { cancelPersisted(job) }
            throw cancelled
        } catch (_: Exception) {
            val failed = job.next(
                stage = RecognitionStage.FAILED,
                status = RecognitionStatus.FAILED,
                document = null,
                draft = null,
                diagnostics = listOf(
                    RecognitionDiagnostic(
                        "RECOGNITION_FAILED",
                        RecognitionDiagnosticSeverity.ERROR,
                        "识别任务失败；来源正文和模型错误未写入诊断。",
                    ),
                ),
            )
            persist(job, failed)?.let { return@withContext it }
            RecognitionResult.Failure(RecognitionFailureCode.FAILED, "Recognition failed", failed)
        }
    }

    suspend fun cancel(jobId: String): RecognitionResult = withContext(workerDispatcher) {
        val current = store.load(jobId)
            ?: return@withContext RecognitionResult.Failure(RecognitionFailureCode.FAILED, "Recognition job not found")
        cancelPersisted(current)
    }

    private suspend fun cancelPersisted(current: RecognitionJobState): RecognitionResult {
        if (current.status == RecognitionStatus.CANCELLED) return RecognitionResult.Cancelled(current)
        val cancelled = current.next(
            stage = RecognitionStage.CANCELLED,
            status = RecognitionStatus.CANCELLED,
            checkpoint = RecognitionCheckpoint(RecognitionStage.CANCELLED, current.checkpoint.completedUnits, 3),
            document = null,
            draft = null,
            diagnostics = listOf(
                RecognitionDiagnostic(
                    "CANCELLED",
                    RecognitionDiagnosticSeverity.INFO,
                    "识别已取消；临时正文与未发布草稿已清理，可使用相同来源重新开始。",
                ),
            ),
        )
        persist(current, cancelled)?.let { return it }
        return RecognitionResult.Cancelled(cancelled)
    }

    private suspend fun persist(
        current: RecognitionJobState,
        next: RecognitionJobState,
    ): RecognitionResult.Failure? = when (val result = store.update(current.revision, next)) {
        RecognitionJobUpdateResult.Updated -> null
        is RecognitionJobUpdateResult.Conflict -> RecognitionResult.Failure(
            RecognitionFailureCode.STORE_CONFLICT,
            "Recognition job changed concurrently",
            result.current,
        )
    }

    private fun validateMappings(
        document: SourceDocument,
        draft: RecognitionDraft,
    ): List<RecognitionDiagnostic> {
        val fragments = document.chunks.associateBy(SourceChunk::id)
        return buildList {
            draft.candidates.forEach { candidate ->
                if (candidate.sourceReferences.isEmpty()) {
                    add(
                        RecognitionDiagnostic(
                            "SOURCE_MAPPING_MISSING",
                            RecognitionDiagnosticSeverity.ERROR,
                            "候选项缺少来源片段。",
                            candidate.id,
                        ),
                    )
                }
                candidate.sourceReferences.forEach { reference ->
                    val fragment = fragments[reference.sourceFragmentId]
                    if (
                        fragment == null || reference.startCharacter < fragment.locator.startCharacter ||
                        reference.endCharacterExclusive > fragment.locator.endCharacterExclusive ||
                        reference.startCharacter >= reference.endCharacterExclusive
                    ) {
                        add(
                            RecognitionDiagnostic(
                                "SOURCE_MAPPING_INVALID",
                                RecognitionDiagnosticSeverity.ERROR,
                                "候选项的来源范围无效。",
                                candidate.id,
                                reference.sourceFragmentId,
                            ),
                        )
                    }
                    if (reference.diagnostic.isNullOrBlank()) {
                        add(
                            RecognitionDiagnostic(
                                "CONFIDENCE_DIAGNOSTIC_MISSING",
                                RecognitionDiagnosticSeverity.ERROR,
                                "候选项缺少置信判断说明。",
                                candidate.id,
                                reference.sourceFragmentId,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun playabilityDiagnostics(draft: RecognitionDraft): List<RecognitionDiagnostic> = buildList {
        val presentKinds = draft.candidates.map(RecognitionCandidate::kind).toSet()
        RecognitionCandidateKind.entries.filterNot(presentKinds::contains).forEach { kind ->
            add(
                RecognitionDiagnostic(
                    "CANDIDATE_KIND_MISSING",
                    RecognitionDiagnosticSeverity.WARNING,
                    "尚未识别 ${kind.name.lowercase()} 候选。",
                ),
            )
        }
        add(
            RecognitionDiagnostic(
                "PLAYABILITY_NOT_VALIDATED",
                RecognitionDiagnosticSeverity.WARNING,
                "识别草稿尚未通过初始场景、结局可达性、权限与 Behavior 验证，试玩和安装保持禁用。",
            ),
        )
    }
}

data class RecognitionCandidatePresentation(
    val id: String,
    val kind: RecognitionCandidateKind,
    val label: String,
    val confidence: RecognitionConfidence?,
    val sourceLabel: String,
    val diagnostics: List<String>,
)

data class RecognitionSourceContextPresentation(
    val fragmentId: String,
    val locator: SourceLocator,
    val excerpt: String,
)

data class RecognitionWorkspacePresentation(
    val jobId: String,
    val sourceName: String,
    val status: RecognitionStatus,
    val stage: RecognitionStage,
    val completedUnits: Int,
    val totalUnits: Int,
    val candidates: List<RecognitionCandidatePresentation>,
    val diagnostics: List<String>,
    val selectedSourceContext: RecognitionSourceContextPresentation? = null,
    val canCancel: Boolean,
    val canResume: Boolean,
    val canPlaytest: Boolean = false,
    val canInstall: Boolean = false,
)

object RecognitionWorkspaceProjector {
    fun project(job: RecognitionJobState, selectedCandidateId: String? = null): RecognitionWorkspacePresentation {
        val draft = job.draft
        val candidates = draft?.candidates.orEmpty().map { candidate ->
            val reference = candidate.sourceReferences.firstOrNull()
            RecognitionCandidatePresentation(
                id = candidate.id,
                kind = candidate.kind,
                label = candidate.label.take(120),
                confidence = reference?.confidence,
                sourceLabel = reference?.let {
                    "${it.sourceFragmentId} · ${it.startCharacter}–${it.endCharacterExclusive}"
                } ?: "缺少来源",
                diagnostics = (candidate.diagnostics.map(RecognitionDiagnostic::message) +
                    job.diagnostics.filter { it.candidateId == candidate.id }.map(RecognitionDiagnostic::message)),
            )
        }
        val selected = draft?.candidates?.firstOrNull { it.id == selectedCandidateId }
        val reference = selected?.sourceReferences?.firstOrNull()
        val fragment = reference?.let { source -> job.document?.chunks?.firstOrNull { it.id == source.sourceFragmentId } }
        val context = if (reference != null && fragment != null) {
            RecognitionSourceContextPresentation(
                fragmentId = fragment.id,
                locator = fragment.locator,
                excerpt = fragment.text.take(320),
            )
        } else {
            null
        }
        return RecognitionWorkspacePresentation(
            jobId = job.jobId,
            sourceName = job.sourceName,
            status = job.status,
            stage = job.stage,
            completedUnits = job.checkpoint.completedUnits,
            totalUnits = job.checkpoint.totalUnits,
            candidates = candidates,
            diagnostics = job.diagnostics.map(RecognitionDiagnostic::message),
            selectedSourceContext = context,
            canCancel = job.status == RecognitionStatus.RUNNING,
            canResume = job.status in setOf(RecognitionStatus.CANCELLED, RecognitionStatus.FAILED),
        )
    }
}

private fun RecognitionJobState.next(
    stage: RecognitionStage = this.stage,
    status: RecognitionStatus = this.status,
    checkpoint: RecognitionCheckpoint = this.checkpoint,
    document: SourceDocument? = this.document,
    draft: RecognitionDraft? = this.draft,
    diagnostics: List<RecognitionDiagnostic> = this.diagnostics,
) = copy(
    revision = revision + 1,
    stage = stage,
    status = status,
    checkpoint = checkpoint,
    document = document,
    draft = draft,
    diagnostics = diagnostics,
)

private fun CorpusFileType.toSourceFormat(): SourceFormat = when (this) {
    CorpusFileType.TXT -> SourceFormat.TXT
    CorpusFileType.EPUB -> SourceFormat.EPUB
}
