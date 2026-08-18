package io.worldloom.persistence

import io.worldloom.agent.runtime.NpcWorkCreateResult
import io.worldloom.agent.runtime.NpcWorkItem
import io.worldloom.agent.runtime.NpcWorkStore
import io.worldloom.agent.runtime.NpcWorkUpdateResult
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.RunId

class SqlDelightNpcWorkStore(
    private val database: WorldloomDatabase,
) : NpcWorkStore {
    override suspend fun create(item: NpcWorkItem): NpcWorkCreateResult = database.transactionWithResult {
        if (read(item.runId, item.id.value) != null) return@transactionWithResult NpcWorkCreateResult.Exists
        database.worldloomQueries.insertNpcWork(
            run_id = item.runId.value,
            work_id = item.id.value,
            revision = item.revision,
            source_sequence = item.sourceSequence,
            status = item.status.name,
            work_json = PersistenceCodec.encodeNpcWork(item),
        )
        NpcWorkCreateResult.Created
    }

    override suspend fun list(runId: RunId): List<NpcWorkItem> =
        database.worldloomQueries.selectNpcWorks(runId.value).executeAsList().mapNotNull { source ->
            when (val decoded = PersistenceCodec.decodeNpcWork(source)) {
                is PersistenceDecodeResult.Success -> decoded.value
                is PersistenceDecodeResult.Failure -> null
            }
        }

    override suspend fun update(expectedRevision: Long, item: NpcWorkItem): NpcWorkUpdateResult =
        database.transactionWithResult {
            val current = read(item.runId, item.id.value)
                ?: return@transactionWithResult NpcWorkUpdateResult.RevisionConflict
            if (current.revision != expectedRevision || item.revision != expectedRevision + 1) {
                return@transactionWithResult NpcWorkUpdateResult.RevisionConflict
            }
            database.worldloomQueries.updateNpcWork(
                revision = item.revision,
                status = item.status.name,
                work_json = PersistenceCodec.encodeNpcWork(item),
                run_id = item.runId.value,
                work_id = item.id.value,
                revision_ = expectedRevision,
            )
            NpcWorkUpdateResult.Updated
        }

    private fun read(runId: RunId, workId: String): NpcWorkItem? {
        val source = database.worldloomQueries.selectNpcWork(runId.value, workId).executeAsOneOrNull() ?: return null
        return when (val decoded = PersistenceCodec.decodeNpcWork(source)) {
            is PersistenceDecodeResult.Success -> decoded.value
            is PersistenceDecodeResult.Failure -> null
        }
    }
}
