package io.worldloom.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.content.generation.RecognitionCheckpoint
import io.worldloom.content.generation.CancellationProbe
import io.worldloom.content.generation.CorpusFileType
import io.worldloom.content.generation.JvmGb18030Decoder
import io.worldloom.content.generation.RecognitionCoordinator
import io.worldloom.content.generation.RecognitionJobCreateResult
import io.worldloom.content.generation.RecognitionJobState
import io.worldloom.content.generation.RecognitionJobUpdateResult
import io.worldloom.content.generation.RecognitionProgressListener
import io.worldloom.content.generation.RecognitionRequest
import io.worldloom.content.generation.RecognitionResult
import io.worldloom.content.generation.RecognitionStage
import io.worldloom.content.generation.RecognitionStatus
import io.worldloom.content.generation.SourceFormat
import io.worldloom.content.generation.TxtIngestor
import io.worldloom.persistence.db.WorldloomDatabase
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SqlDelightRecognitionJobStoreTest {
    @Test
    fun persistsVersionedRecognitionJobAndRejectsStaleRevision() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            WorldloomDatabase.Schema.create(driver).value
            val first = SqlDelightRecognitionJobStore(WorldloomDatabase(driver))
            val received = RecognitionJobState(
                jobId = "recognition.sql",
                sourceName = "story.epub",
                sourceFormat = SourceFormat.EPUB,
                sourceHash = "a".repeat(64),
            )
            assertEquals(RecognitionJobCreateResult.Created, first.create(received))
            assertIs<RecognitionJobCreateResult.Existing>(first.create(received))
            val parsed = received.copy(
                revision = 1,
                stage = RecognitionStage.PARSED,
                checkpoint = RecognitionCheckpoint(RecognitionStage.PARSED, 1, 3),
            )
            assertEquals(RecognitionJobUpdateResult.Updated, first.update(0, parsed))

            val recreated = SqlDelightRecognitionJobStore(WorldloomDatabase(driver))
            assertEquals(parsed, recreated.load(received.jobId))
            assertEquals(listOf(parsed), recreated.list())
            assertIs<RecognitionJobUpdateResult.Conflict>(recreated.update(0, parsed.copy(revision = 1)))
        } finally {
            driver.close()
        }
    }

    @Test
    fun cancelledJobResumesAcrossStoreRecreationWithoutPublishingTemporaryDraft() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            WorldloomDatabase.Schema.create(driver).value
            val request = RecognitionRequest("recognition.sql.resume", "story.txt", CorpusFileType.TXT)
            val source = "第一章\n工程师在维护脊柱发现被锁死的隔离器。".encodeToByteArray()
            var cancelled = false
            val first = RecognitionCoordinator(
                store = SqlDelightRecognitionJobStore(WorldloomDatabase(driver)),
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
            val cancelledJob = assertIs<RecognitionResult.Cancelled>(first).job
            assertEquals(RecognitionStatus.CANCELLED, cancelledJob.status)
            assertEquals(null, cancelledJob.document)
            assertEquals(null, cancelledJob.draft)

            val resumed = RecognitionCoordinator(
                store = SqlDelightRecognitionJobStore(WorldloomDatabase(driver)),
                txtIngestor = TxtIngestor(JvmGb18030Decoder),
                workerDispatcher = StandardTestDispatcher(testScheduler),
            ).run(request, source)

            val ready = assertIs<RecognitionResult.Ready>(resumed).job
            assertEquals(RecognitionStatus.READY_FOR_REVIEW, ready.status)
            assertEquals(5, ready.draft?.candidates?.size)
            assertEquals(ready, SqlDelightRecognitionJobStore(WorldloomDatabase(driver)).load(request.jobId))
        } finally {
            driver.close()
        }
    }
}
