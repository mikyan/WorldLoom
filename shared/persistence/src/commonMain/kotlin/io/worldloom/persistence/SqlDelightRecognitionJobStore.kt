package io.worldloom.persistence

import io.worldloom.content.generation.RecognitionJobCreateResult
import io.worldloom.content.generation.RecognitionJobState
import io.worldloom.content.generation.RecognitionJobStore
import io.worldloom.content.generation.RecognitionJobUpdateResult
import io.worldloom.persistence.db.Content_recognition_job
import io.worldloom.persistence.db.WorldloomDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class SqlDelightRecognitionJobStore(
    database: WorldloomDatabase,
) : RecognitionJobStore {
    private val queries = database.worldloomQueries
    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    override suspend fun create(state: RecognitionJobState): RecognitionJobCreateResult = mutex.withLock {
        val existing = queries.selectRecognitionJob(state.jobId).executeAsOneOrNull()?.decode()
        if (existing != null) return@withLock RecognitionJobCreateResult.Existing(existing)
        queries.insertRecognitionJob(
            job_id = state.jobId,
            schema_version = state.schemaVersion.toLong(),
            revision = state.revision,
            source_name = state.sourceName,
            source_format = state.sourceFormat.name,
            source_hash = state.sourceHash,
            stage = state.stage.name,
            status = state.status.name,
            job_json = json.encodeToString(RecognitionJobState.serializer(), state),
        )
        RecognitionJobCreateResult.Created
    }

    override suspend fun load(jobId: String): RecognitionJobState? = mutex.withLock {
        queries.selectRecognitionJob(jobId).executeAsOneOrNull()?.decode()
    }

    override suspend fun update(
        expectedRevision: Long,
        state: RecognitionJobState,
    ): RecognitionJobUpdateResult = mutex.withLock {
        val currentRow = queries.selectRecognitionJob(state.jobId).executeAsOneOrNull()
            ?: return@withLock RecognitionJobUpdateResult.Conflict(null)
        val current = currentRow.decode()
        if (current.revision != expectedRevision || state.revision != expectedRevision + 1) {
            return@withLock RecognitionJobUpdateResult.Conflict(current)
        }
        queries.updateRecognitionJob(
            schema_version = state.schemaVersion.toLong(),
            revision = state.revision,
            source_name = state.sourceName,
            source_format = state.sourceFormat.name,
            source_hash = state.sourceHash,
            stage = state.stage.name,
            status = state.status.name,
            job_json = json.encodeToString(RecognitionJobState.serializer(), state),
            job_id = state.jobId,
            revision_ = expectedRevision,
        )
        val persisted = queries.selectRecognitionJob(state.jobId).executeAsOneOrNull()?.decode()
        if (persisted?.revision == state.revision) {
            RecognitionJobUpdateResult.Updated
        } else {
            RecognitionJobUpdateResult.Conflict(persisted)
        }
    }

    override suspend fun list(): List<RecognitionJobState> = mutex.withLock {
        queries.selectRecognitionJobs().executeAsList().map { row -> row.decode() }
    }

    private fun Content_recognition_job.decode(): RecognitionJobState {
        val state = json.decodeFromString(RecognitionJobState.serializer(), job_json)
        require(state.jobId == job_id && state.schemaVersion.toLong() == schema_version) {
            "Recognition job identity or schema metadata does not match its payload"
        }
        require(
            state.revision == revision && state.sourceName == source_name && state.sourceFormat.name == source_format &&
                state.sourceHash == source_hash && state.stage.name == stage && state.status.name == status
        ) { "Recognition job index metadata does not match its payload" }
        return state
    }
}
