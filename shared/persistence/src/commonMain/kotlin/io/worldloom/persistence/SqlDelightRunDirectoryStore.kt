package io.worldloom.persistence

import io.worldloom.application.RunDirectoryEntry
import io.worldloom.application.RunDirectoryStore
import io.worldloom.definition.DefinitionId
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.RunId
import io.worldloom.world.RunLifecycle
import io.worldloom.world.RunLifecycleChangedEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SqlDelightRunDirectoryStore(
    database: WorldloomDatabase,
) : RunDirectoryStore {
    private val queries = database.worldloomQueries
    private val mutex = Mutex()

    override suspend fun list(): List<RunDirectoryEntry> = mutex.withLock {
        queries.selectRunDirectory().executeAsList().map { row ->
            var lifecycle = RunLifecycle.ACTIVE
            var diagnostic: String? = null
            val snapshot = queries.selectSnapshot(row.run_id).executeAsOneOrNull()
            if (snapshot != null) {
                when (val decoded = PersistenceCodec.decodeState(snapshot.state_json)) {
                    is PersistenceDecodeResult.Success -> lifecycle = decoded.value.lifecycle
                    is PersistenceDecodeResult.Failure -> diagnostic = "Snapshot 无效，将在继续时从 EventLog 重建"
                }
            }
            try {
                queries.selectEventsAfter(row.run_id, 0).executeAsList().forEach { source ->
                    when (val decoded = PersistenceCodec.decodeEvent(source)) {
                        is PersistenceDecodeResult.Success -> {
                            val change = decoded.value.payload as? RunLifecycleChangedEvent
                            if (change != null) lifecycle = change.lifecycle
                        }
                        is PersistenceDecodeResult.Failure -> diagnostic = "EventLog 包含无法解码的事件"
                    }
                }
            } catch (_: Exception) {
                diagnostic = "EventLog 无法读取"
            }
            RunDirectoryEntry(
                runId = RunId(row.run_id),
                worldId = DefinitionId(row.world_definition_id),
                worldContentVersion = row.world_content_version.toInt(),
                displayName = row.display_name.ifBlank { row.run_id },
                archived = row.archived != 0L,
                lastSequence = row.last_sequence,
                lifecycle = lifecycle,
                diagnostic = diagnostic,
            )
        }
    }

    override suspend fun rename(runId: RunId, displayName: String): Boolean = mutex.withLock {
        if (queries.selectRun(runId.value).executeAsOneOrNull() == null) return@withLock false
        queries.renameRun(displayName, runId.value)
        true
    }

    override suspend fun setArchived(runId: RunId, archived: Boolean): Boolean = mutex.withLock {
        if (queries.selectRun(runId.value).executeAsOneOrNull() == null) return@withLock false
        queries.setRunArchived(if (archived) 1 else 0, runId.value)
        true
    }

    override suspend fun setWorldContentVersion(runId: RunId, version: Int): Boolean = mutex.withLock {
        if (queries.selectRun(runId.value).executeAsOneOrNull() == null) return@withLock false
        queries.setRunContentVersion(version.toLong(), runId.value)
        true
    }
}
