package io.worldloom.persistence

import io.worldloom.behavior.runtime.BehaviorWorkCreateResult
import io.worldloom.behavior.runtime.BehaviorWorkItem
import io.worldloom.behavior.runtime.BehaviorWorkStore
import io.worldloom.behavior.runtime.BehaviorWorkUpdateResult
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.RunId

class SqlDelightBehaviorWorkStore(
    private val database: WorldloomDatabase,
) : BehaviorWorkStore {
    override suspend fun create(item: BehaviorWorkItem): BehaviorWorkCreateResult = database.transactionWithResult {
        read(item.runId, item.id.value)?.let { existing ->
            return@transactionWithResult BehaviorWorkCreateResult.Existing(existing)
        }
        database.worldloomQueries.insertBehaviorWork(
            run_id = item.runId.value,
            work_id = item.id.value,
            revision = item.revision,
            parent_sequence = item.parentSequence,
            status = item.status.name,
            work_json = PersistenceCodec.encodeBehaviorWork(item),
        )
        BehaviorWorkCreateResult.Created(item)
    }

    override suspend fun list(runId: RunId): List<BehaviorWorkItem> =
        database.worldloomQueries.selectBehaviorWorks(runId.value).executeAsList().mapNotNull { source ->
            when (val decoded = PersistenceCodec.decodeBehaviorWork(source)) {
                is PersistenceDecodeResult.Success -> decoded.value
                is PersistenceDecodeResult.Failure -> null
            }
        }

    override suspend fun update(
        expectedRevision: Long,
        item: BehaviorWorkItem,
    ): BehaviorWorkUpdateResult = database.transactionWithResult {
        val current = read(item.runId, item.id.value)
            ?: return@transactionWithResult BehaviorWorkUpdateResult.Conflict(null)
        if (current.revision != expectedRevision) {
            return@transactionWithResult BehaviorWorkUpdateResult.Conflict(current)
        }
        val updated = item.copy(revision = expectedRevision + 1)
        database.worldloomQueries.updateBehaviorWork(
            revision = updated.revision,
            parent_sequence = updated.parentSequence,
            status = updated.status.name,
            work_json = PersistenceCodec.encodeBehaviorWork(updated),
            run_id = updated.runId.value,
            work_id = updated.id.value,
            revision_ = expectedRevision,
        )
        BehaviorWorkUpdateResult.Updated(updated)
    }

    private fun read(runId: RunId, workId: String): BehaviorWorkItem? {
        val source = database.worldloomQueries.selectBehaviorWork(runId.value, workId).executeAsOneOrNull() ?: return null
        return when (val decoded = PersistenceCodec.decodeBehaviorWork(source)) {
            is PersistenceDecodeResult.Success -> decoded.value
            is PersistenceDecodeResult.Failure -> null
        }
    }
}
