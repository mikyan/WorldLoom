package io.worldloom.behavior.runtime

import io.worldloom.definition.DefinitionId
import io.worldloom.world.EventId
import io.worldloom.world.RunId
import io.worldloom.world.CommandId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

const val CURRENT_BEHAVIOR_WORK_SCHEMA_VERSION: Int = 1

@Serializable
@JvmInline
value class BehaviorWorkId(val value: String) {
    init {
        require(value.isNotBlank()) { "BehaviorWorkId must not be blank" }
    }
}

@Serializable
enum class BehaviorWorkStatus { PENDING, RUNNING, COMPLETED, PAUSED }

/** Durable audit record for one Behavior firing caused by one committed parent Event. */
@Serializable
data class BehaviorWorkItem(
    val schemaVersion: Int = CURRENT_BEHAVIOR_WORK_SCHEMA_VERSION,
    val id: BehaviorWorkId,
    val runId: RunId,
    val rootEventId: EventId,
    val parentEventId: EventId,
    val parentSequence: Long,
    val parentEventType: DefinitionId,
    val behaviorId: DefinitionId,
    val priority: Int,
    val causalDepth: Int,
    val triggerOrdinal: Int,
    val signature: String,
    val status: BehaviorWorkStatus = BehaviorWorkStatus.PENDING,
    val derivedCommandCount: Int = 0,
    val derivedCommandIds: List<CommandId> = emptyList(),
    val derivedCommandSignatures: List<String> = emptyList(),
    val committedThroughSequence: Long? = null,
    val message: String? = null,
    val revision: Long = 0,
) {
    init {
        require(parentSequence > 0 && causalDepth >= 0 && triggerOrdinal >= 0) { "Behavior work coordinates are invalid" }
        require(
            signature.isNotBlank() && revision >= 0 && derivedCommandCount >= 0 &&
                derivedCommandIds.size == derivedCommandSignatures.size,
        ) { "Behavior work audit fields are invalid" }
    }
}

sealed interface BehaviorWorkCreateResult {
    data class Created(val item: BehaviorWorkItem) : BehaviorWorkCreateResult
    data class Existing(val item: BehaviorWorkItem) : BehaviorWorkCreateResult
    data class Failure(val message: String) : BehaviorWorkCreateResult
}

sealed interface BehaviorWorkUpdateResult {
    data class Updated(val item: BehaviorWorkItem) : BehaviorWorkUpdateResult
    data class Conflict(val current: BehaviorWorkItem?) : BehaviorWorkUpdateResult
    data class Failure(val message: String) : BehaviorWorkUpdateResult
}

interface BehaviorWorkStore {
    suspend fun create(item: BehaviorWorkItem): BehaviorWorkCreateResult
    suspend fun list(runId: RunId): List<BehaviorWorkItem>
    suspend fun update(expectedRevision: Long, item: BehaviorWorkItem): BehaviorWorkUpdateResult
}

class InMemoryBehaviorWorkStore : BehaviorWorkStore {
    private val mutex = Mutex()
    private val items = linkedMapOf<Pair<RunId, BehaviorWorkId>, BehaviorWorkItem>()

    override suspend fun create(item: BehaviorWorkItem): BehaviorWorkCreateResult = mutex.withLock {
        val key = item.runId to item.id
        val existing = items[key]
        if (existing != null) BehaviorWorkCreateResult.Existing(existing) else {
            items[key] = item
            BehaviorWorkCreateResult.Created(item)
        }
    }

    override suspend fun list(runId: RunId): List<BehaviorWorkItem> = mutex.withLock {
        items.values.filter { it.runId == runId }.sortedWith(BEHAVIOR_WORK_ORDER)
    }

    override suspend fun update(expectedRevision: Long, item: BehaviorWorkItem): BehaviorWorkUpdateResult = mutex.withLock {
        val key = item.runId to item.id
        val current = items[key]
        if (current == null || current.revision != expectedRevision) {
            BehaviorWorkUpdateResult.Conflict(current)
        } else {
            val updated = item.copy(revision = expectedRevision + 1)
            items[key] = updated
            BehaviorWorkUpdateResult.Updated(updated)
        }
    }
}

data class BehaviorDispatchLimits(
    val maximumCausalDepth: Int = 8,
    val maximumFiringsPerChain: Int = 64,
    val maximumDerivedCommandsPerChain: Int = 128,
    val maximumRepeatedSignature: Int = 2,
) {
    init {
        require(
            maximumCausalDepth > 0 && maximumFiringsPerChain > 0 &&
                maximumDerivedCommandsPerChain > 0 && maximumRepeatedSignature > 0,
        ) { "Behavior dispatch limits must be positive" }
    }
}

val BEHAVIOR_WORK_ORDER: Comparator<BehaviorWorkItem> = compareBy<BehaviorWorkItem>(
    BehaviorWorkItem::parentSequence,
    BehaviorWorkItem::causalDepth,
).thenByDescending(BehaviorWorkItem::priority)
    .thenBy { it.behaviorId.value }
    .thenBy(BehaviorWorkItem::triggerOrdinal)
    .thenBy { it.id.value }
