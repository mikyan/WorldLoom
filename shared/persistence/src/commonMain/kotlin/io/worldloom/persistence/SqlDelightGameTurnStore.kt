package io.worldloom.persistence

import io.worldloom.agent.runtime.CURRENT_GM_TURN_SCHEMA_VERSION
import io.worldloom.agent.runtime.GameTurn
import io.worldloom.agent.runtime.GameTurnStore
import io.worldloom.agent.runtime.GameTurnStoreResult
import io.worldloom.agent.runtime.TurnId
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.RunId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SqlDelightGameTurnStore(database: WorldloomDatabase) : GameTurnStore {
    private val queries = database.worldloomQueries
    private val mutex = Mutex()

    override suspend fun nextTurnId(runId: RunId): TurnId = mutex.withLock {
        val ordinal = queries.countGmTurnsForRun(runId.value).executeAsOne() + 1
        TurnId("${runId.value}.turn.$ordinal")
    }

    override suspend fun load(runId: RunId, turnId: TurnId): GameTurn? = mutex.withLock {
        decode(runId, turnId)
    }

    override suspend fun save(turn: GameTurn, expectedRevision: Long?): GameTurnStoreResult = mutex.withLock {
        try {
            val existing = queries.selectGmTurn(turn.runId.value, turn.turnId.value).executeAsOneOrNull()
            if (existing?.revision != expectedRevision) return@withLock GameTurnStoreResult.RevisionConflict
            queries.upsertGmTurn(
                turn.runId.value,
                turn.turnId.value,
                turn.revision,
                turn.schemaVersion.toLong(),
                PersistenceCodec.encodeGameTurn(turn),
            )
            GameTurnStoreResult.Success
        } catch (_: Exception) {
            GameTurnStoreResult.Failure("Unable to persist GM turn")
        }
    }

    private fun decode(runId: RunId, turnId: TurnId): GameTurn? {
        val row = queries.selectGmTurn(runId.value, turnId.value).executeAsOneOrNull() ?: return null
        require(row.turn_schema_version == CURRENT_GM_TURN_SCHEMA_VERSION.toLong()) {
            "Unsupported GM turn schema: ${row.turn_schema_version}"
        }
        return when (val decoded = PersistenceCodec.decodeGameTurn(row.turn_json)) {
            is PersistenceDecodeResult.Success -> decoded.value.also {
                require(it.runId == runId && it.turnId == turnId && it.revision == row.revision) {
                    "GM turn identity or revision does not match its row"
                }
            }
            is PersistenceDecodeResult.Failure -> throw IllegalStateException(decoded.message)
        }
    }
}
