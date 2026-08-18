package io.worldloom.agent.runtime

import io.worldloom.provider.api.ProviderMessage
import io.worldloom.world.ActorId
import io.worldloom.world.RunId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AgentSessionSnapshot(
    val sessionId: AgentSessionId,
    val ownerAgentId: AgentId,
    val ownerActorId: ActorId,
    val revision: Long,
    val messages: List<ProviderMessage>,
    val runId: RunId? = null,
)

sealed interface AgentSessionLoadResult {
    data class Success(val snapshot: AgentSessionSnapshot) : AgentSessionLoadResult

    data object OwnershipMismatch : AgentSessionLoadResult

    data class StorageFailure(val message: String) : AgentSessionLoadResult
}

sealed interface AgentSessionSaveResult {
    data class Success(val revision: Long) : AgentSessionSaveResult

    data object OwnershipMismatch : AgentSessionSaveResult

    data object RevisionConflict : AgentSessionSaveResult

    data class StorageFailure(val message: String) : AgentSessionSaveResult
}

interface AgentSessionStore {
    suspend fun load(
        sessionId: AgentSessionId,
        identity: AgentIdentity,
        runId: RunId? = null,
    ): AgentSessionLoadResult

    suspend fun save(
        snapshot: AgentSessionSnapshot,
        expectedRevision: Long,
    ): AgentSessionSaveResult
}

/** In-memory private memory store. Durable implementations must preserve the same ownership checks. */
class InMemoryAgentSessionStore : AgentSessionStore {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<AgentSessionId, AgentSessionSnapshot>()

    suspend fun load(
        sessionId: AgentSessionId,
        identity: AgentIdentity,
    ): AgentSessionLoadResult = load(sessionId, identity, null)

    override suspend fun load(
        sessionId: AgentSessionId,
        identity: AgentIdentity,
        runId: RunId?,
    ): AgentSessionLoadResult = mutex.withLock {
        val existing = sessions[sessionId]
        if (existing == null) {
            AgentSessionLoadResult.Success(
                AgentSessionSnapshot(
                    sessionId = sessionId,
                    ownerAgentId = identity.agentId,
                    ownerActorId = identity.actorId,
                    revision = 0,
                    messages = emptyList(),
                    runId = runId,
                ),
            )
        } else if (
            existing.ownerAgentId != identity.agentId ||
            existing.ownerActorId != identity.actorId ||
            existing.runId != runId
        ) {
            AgentSessionLoadResult.OwnershipMismatch
        } else {
            AgentSessionLoadResult.Success(existing.copy(messages = existing.messages.toList()))
        }
    }

    override suspend fun save(
        snapshot: AgentSessionSnapshot,
        expectedRevision: Long,
    ): AgentSessionSaveResult = mutex.withLock {
        val existing = sessions[snapshot.sessionId]
        if (
            existing != null &&
            (
                existing.ownerAgentId != snapshot.ownerAgentId ||
                    existing.ownerActorId != snapshot.ownerActorId ||
                    existing.runId != snapshot.runId
            )
        ) {
            return@withLock AgentSessionSaveResult.OwnershipMismatch
        }
        val currentRevision = existing?.revision ?: 0
        if (currentRevision != expectedRevision) {
            return@withLock AgentSessionSaveResult.RevisionConflict
        }
        val nextRevision = currentRevision + 1
        sessions[snapshot.sessionId] = snapshot.copy(
            revision = nextRevision,
            messages = snapshot.messages.toList(),
        )
        AgentSessionSaveResult.Success(nextRevision)
    }
}
