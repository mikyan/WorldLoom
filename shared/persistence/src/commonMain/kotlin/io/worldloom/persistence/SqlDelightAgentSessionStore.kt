package io.worldloom.persistence

import io.worldloom.agent.runtime.AgentId
import io.worldloom.agent.runtime.AgentIdentity
import io.worldloom.agent.runtime.AgentSessionId
import io.worldloom.agent.runtime.AgentSessionLoadResult
import io.worldloom.agent.runtime.AgentSessionSaveResult
import io.worldloom.agent.runtime.AgentSessionSnapshot
import io.worldloom.agent.runtime.AgentSessionStore
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.provider.api.ProviderMessage
import io.worldloom.world.ActorId
import io.worldloom.world.RunId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

const val CURRENT_AGENT_SESSION_SCHEMA_VERSION: Int = 1

/** Durable, run-scoped private Agent conversation storage with optimistic publication. */
class SqlDelightAgentSessionStore(
    database: WorldloomDatabase,
    private val defaultRunId: RunId? = null,
) : AgentSessionStore {
    private val queries = database.worldloomQueries
    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    suspend fun load(
        sessionId: AgentSessionId,
        identity: AgentIdentity,
    ): AgentSessionLoadResult = load(sessionId, identity, null)

    override suspend fun load(
        sessionId: AgentSessionId,
        identity: AgentIdentity,
        runId: RunId?,
    ): AgentSessionLoadResult = mutex.withLock {
        try {
            val scope = runId ?: defaultRunId
                ?: return@withLock AgentSessionLoadResult.StorageFailure("Agent session requires a run id")
            val row = queries.selectAgentSession(scope.value, sessionId.value).executeAsOneOrNull()
                ?: return@withLock AgentSessionLoadResult.Success(
                    AgentSessionSnapshot(
                        sessionId = sessionId,
                        ownerAgentId = identity.agentId,
                        ownerActorId = identity.actorId,
                        revision = 0,
                        messages = emptyList(),
                        runId = scope,
                    ),
                )
            if (row.owner_agent_id != identity.agentId.value || row.owner_actor_id != identity.actorId.value) {
                return@withLock AgentSessionLoadResult.OwnershipMismatch
            }
            if (row.session_schema_version != CURRENT_AGENT_SESSION_SCHEMA_VERSION.toLong()) {
                return@withLock AgentSessionLoadResult.StorageFailure("Unsupported Agent session schema version")
            }
            val messages = json.decodeFromString(
                ListSerializer(ProviderMessage.serializer()),
                row.messages_json,
            )
            AgentSessionLoadResult.Success(
                AgentSessionSnapshot(
                    sessionId = AgentSessionId(row.session_id),
                    ownerAgentId = AgentId(row.owner_agent_id),
                    ownerActorId = ActorId(row.owner_actor_id),
                    revision = row.revision,
                    messages = messages,
                    runId = scope,
                ),
            )
        } catch (_: SerializationException) {
            AgentSessionLoadResult.StorageFailure("Stored Agent session messages are invalid")
        } catch (_: Exception) {
            AgentSessionLoadResult.StorageFailure("Unable to load Agent session")
        }
    }

    override suspend fun save(
        snapshot: AgentSessionSnapshot,
        expectedRevision: Long,
    ): AgentSessionSaveResult = mutex.withLock {
        try {
            val scope = snapshot.runId ?: defaultRunId
                ?: return@withLock AgentSessionSaveResult.StorageFailure("Agent session requires a run id")
            var result: AgentSessionSaveResult? = null
            val messagesJson = json.encodeToString(
                ListSerializer(ProviderMessage.serializer()),
                snapshot.messages,
            )
            queries.transaction {
                val row = queries.selectAgentSession(scope.value, snapshot.sessionId.value).executeAsOneOrNull()
                if (row != null &&
                    (row.owner_agent_id != snapshot.ownerAgentId.value || row.owner_actor_id != snapshot.ownerActorId.value)
                ) {
                    result = AgentSessionSaveResult.OwnershipMismatch
                    return@transaction
                }
                val revision = row?.revision ?: 0
                if (revision != expectedRevision) {
                    result = AgentSessionSaveResult.RevisionConflict
                    return@transaction
                }
                val nextRevision = revision + 1
                if (row == null) {
                    queries.insertAgentSession(
                        run_id = scope.value,
                        session_id = snapshot.sessionId.value,
                        owner_agent_id = snapshot.ownerAgentId.value,
                        owner_actor_id = snapshot.ownerActorId.value,
                        revision = nextRevision,
                        messages_json = messagesJson,
                        session_schema_version = CURRENT_AGENT_SESSION_SCHEMA_VERSION.toLong(),
                    )
                } else {
                    queries.updateAgentSession(
                        revision = nextRevision,
                        messages_json = messagesJson,
                        session_schema_version = CURRENT_AGENT_SESSION_SCHEMA_VERSION.toLong(),
                        run_id = scope.value,
                        session_id = snapshot.sessionId.value,
                        revision_ = revision,
                    )
                }
                result = AgentSessionSaveResult.Success(nextRevision)
            }
            result ?: AgentSessionSaveResult.StorageFailure("Unable to publish Agent session")
        } catch (_: Exception) {
            AgentSessionSaveResult.StorageFailure("Unable to publish Agent session")
        }
    }
}
