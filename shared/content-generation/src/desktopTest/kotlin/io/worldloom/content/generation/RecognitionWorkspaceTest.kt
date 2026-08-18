package io.worldloom.content.generation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecognitionWorkspaceTest {
    @Test
    fun sha256BindsJobToExactSourceBytes() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SourceFingerprint.sha256("abc".encodeToByteArray()),
        )
    }

    @Test
    fun recognitionKeepsTheCorpusCharacterBoundary() = runTest {
        val ingestor = TxtIngestor(JvmGb18030Decoder)

        assertIs<SourceIngestResult.Success>(
            ingestor.ingest("recognition.boundary", "boundary.txt", "界".repeat(MAX_SOURCE_CHARACTERS).encodeToByteArray()),
        )
        val overflow = assertIs<SourceIngestResult.Failure>(
            ingestor.ingest(
                "recognition.overflow",
                "overflow.txt",
                "界".repeat(MAX_SOURCE_CHARACTERS + 1).encodeToByteArray(),
            ),
        )
        assertEquals(SourceIngestProblemCode.SOURCE_TOO_LARGE, overflow.problem.code)
    }

    @Test
    fun cancelledParsingCheckpointClearsTextAndResumesIdempotently() = runTest {
        val store = InMemoryRecognitionJobStore()
        val firstModel = CountingRecognitionModel()
        var cancelled = false
        val request = RecognitionRequest("recognition.resume", "story.txt", CorpusFileType.TXT)
        val source = "第一章\n莱拉在中继站发现异常。".encodeToByteArray()
        val first = RecognitionCoordinator(
            store = store,
            model = firstModel,
            txtIngestor = TxtIngestor(JvmGb18030Decoder),
            workerDispatcher = StandardTestDispatcher(testScheduler),
        ).run(
            request,
            source,
            cancellation = CancellationProbe { cancelled },
            progress = RecognitionProgressListener { job ->
                if (job.stage == RecognitionStage.PARSED) cancelled = true
            },
        )

        assertIs<RecognitionResult.Cancelled>(first)
        assertEquals(0, firstModel.calls)
        val cancelledJob = assertIs<RecognitionJobState>(store.load(request.jobId))
        assertEquals(RecognitionStatus.CANCELLED, cancelledJob.status)
        assertNull(cancelledJob.document)
        assertNull(cancelledJob.draft)

        val secondModel = CountingRecognitionModel()
        val resumed = RecognitionCoordinator(
            store = store,
            model = secondModel,
            txtIngestor = TxtIngestor(JvmGb18030Decoder),
            workerDispatcher = StandardTestDispatcher(testScheduler),
        ).run(request, source)

        val ready = assertIs<RecognitionResult.Ready>(resumed).job
        assertEquals(1, secondModel.calls)
        assertEquals(RecognitionStatus.READY_FOR_REVIEW, ready.status)
        assertEquals(RecognitionStage.DRAFTED, ready.stage)
        val revision = ready.revision
        assertIs<RecognitionResult.Ready>(
            RecognitionCoordinator(
                store,
                secondModel,
                TxtIngestor(JvmGb18030Decoder),
                workerDispatcher = StandardTestDispatcher(testScheduler),
            ).run(request, source),
        )
        assertEquals(1, secondModel.calls)
        assertEquals(revision, store.load(request.jobId)?.revision)
    }

    @Test
    fun changedSourceStopsOldJobAndRequiresNewVersionId() = runTest {
        val store = InMemoryRecognitionJobStore()
        val coordinator = RecognitionCoordinator(
            store,
            txtIngestor = TxtIngestor(JvmGb18030Decoder),
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val request = RecognitionRequest("recognition.changed", "story.txt", CorpusFileType.TXT)
        assertIs<RecognitionResult.Ready>(coordinator.run(request, "原始内容".encodeToByteArray()))

        val changed = assertIs<RecognitionResult.Failure>(
            coordinator.run(request, "已经变化的内容".encodeToByteArray()),
        )

        assertEquals(RecognitionFailureCode.SOURCE_CHANGED, changed.code)
        assertEquals(RecognitionStatus.SOURCE_CHANGED, store.load(request.jobId)?.status)
        assertIs<RecognitionResult.Ready>(
            coordinator.run(request.copy(jobId = "recognition.changed.v2"), "已经变化的内容".encodeToByteArray()),
        )
    }

    @Test
    fun workspaceShowsBoundedSourceContextButKeepsPlaytestAndInstallDisabled() = runTest {
        val store = InMemoryRecognitionJobStore()
        val request = RecognitionRequest("recognition.workspace", "novel.txt", CorpusFileType.TXT)
        val result = assertIs<RecognitionResult.Ready>(
            RecognitionCoordinator(
                store,
                txtIngestor = TxtIngestor(JvmGb18030Decoder),
                workerDispatcher = StandardTestDispatcher(testScheduler),
            ).run(request, ("第一幕\n" + "空间站发生级联故障。".repeat(80)).encodeToByteArray()),
        )
        val candidate = result.job.draft!!.candidates.first()

        val workspace = RecognitionWorkspaceProjector.project(result.job, candidate.id)

        assertEquals(RecognitionCandidateKind.entries.toSet(), workspace.candidates.map { it.kind }.toSet())
        assertTrue(workspace.candidates.all { " · " in it.sourceLabel })
        assertTrue(workspace.selectedSourceContext!!.excerpt.length <= 320)
        assertTrue(workspace.diagnostics.any { "试玩和安装保持禁用" in it })
        assertFalse(workspace.canPlaytest)
        assertFalse(workspace.canInstall)
    }

    @Test
    fun missingOrInvalidSourceEvidenceBecomesAnExplicitDiagnostic() = runTest {
        val store = InMemoryRecognitionJobStore()
        val model = SourceRecognitionModel { document ->
            val fragment = document.chunks.single()
            RecognitionDraft(
                candidates = listOf(
                    RecognitionCandidate(
                        id = "candidate.missing",
                        kind = RecognitionCandidateKind.CHARACTER,
                        label = "无来源角色",
                        sourceReferences = emptyList(),
                    ),
                    RecognitionCandidate(
                        id = "candidate.invalid",
                        kind = RecognitionCandidateKind.SCENE,
                        label = "越界场景",
                        sourceReferences = listOf(
                            RecognitionSourceReference(
                                sourceFragmentId = fragment.id,
                                startCharacter = fragment.locator.startCharacter,
                                endCharacterExclusive = fragment.locator.endCharacterExclusive + 1,
                                confidence = RecognitionConfidence.LOW,
                            ),
                        ),
                    ),
                ),
            )
        }

        val ready = assertIs<RecognitionResult.Ready>(
            RecognitionCoordinator(
                store,
                model,
                TxtIngestor(JvmGb18030Decoder),
                workerDispatcher = StandardTestDispatcher(testScheduler),
            ).run(
                RecognitionRequest("recognition.invalid-mapping", "story.txt", CorpusFileType.TXT),
                "一段有来源的正文".encodeToByteArray(),
            ),
        ).job

        assertTrue(ready.diagnostics.any { it.code == "SOURCE_MAPPING_MISSING" && it.candidateId == "candidate.missing" })
        assertTrue(ready.diagnostics.any { it.code == "SOURCE_MAPPING_INVALID" && it.candidateId == "candidate.invalid" })
        assertTrue(ready.diagnostics.any { it.code == "CONFIDENCE_DIAGNOSTIC_MISSING" })
        assertFalse(RecognitionWorkspaceProjector.project(ready).canInstall)
    }

    @Test
    fun coroutineCancellationPropagatesAndPersistsOnlySafeMetadata() = runTest {
        val store = InMemoryRecognitionJobStore()
        val entered = CompletableDeferred<Unit>()
        val model = SourceRecognitionModel {
            entered.complete(Unit)
            CompletableDeferred<RecognitionDraft>().await()
        }
        val coordinator = RecognitionCoordinator(
            store,
            model,
            TxtIngestor(JvmGb18030Decoder),
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val request = RecognitionRequest("recognition.cancel", "private.txt", CorpusFileType.TXT)
        val secretSource = "api_key=should-not-enter-diagnostics\n完整私人正文".encodeToByteArray()
        val task = launch { coordinator.run(request, secretSource) }
        entered.await()

        task.cancelAndJoin()

        val cancelled = assertIs<RecognitionJobState>(store.load(request.jobId))
        assertEquals(RecognitionStatus.CANCELLED, cancelled.status)
        assertNull(cancelled.document)
        assertNull(cancelled.draft)
        val diagnosticText = cancelled.diagnostics.joinToString { it.message }
        assertFalse("api_key" in diagnosticText)
        assertFalse("私人正文" in diagnosticText)
    }
}

private class CountingRecognitionModel : SourceRecognitionModel {
    var calls: Int = 0

    override suspend fun recognize(document: SourceDocument): RecognitionDraft {
        calls += 1
        return DeterministicSourceRecognitionModel().recognize(document)
    }
}
