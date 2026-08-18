package io.worldloom.persistence

import io.worldloom.agent.runtime.CURRENT_GM_TURN_SCHEMA_VERSION
import io.worldloom.agent.runtime.GameTurn
import io.worldloom.agent.runtime.GameTurnHistoryEntry
import io.worldloom.agent.runtime.GameTurnHistoryPage
import io.worldloom.agent.runtime.GameTurnHistoryProblem
import io.worldloom.agent.runtime.GameTurnHistoryProblemCode
import io.worldloom.agent.runtime.GameTurnHistoryResult
import io.worldloom.agent.runtime.GameTurnStore
import io.worldloom.agent.runtime.GameTurnStoreResult
import io.worldloom.agent.runtime.LEGACY_GM_TURN_SCHEMA_VERSION
import io.worldloom.agent.runtime.TurnId
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.RunId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SqlDelightGameTurnStore(database: WorldloomDatabase) : GameTurnStore {
    private val queries = database.worldloomQueries
    private val mutex = Mutex()

    override suspend fun nextTurnId(runId: RunId): TurnId = mutex.withLock {
        val ordinal = queries.selectLatestGmTurnOrdinal(runId.value).executeAsOne() + 1
        TurnId("${runId.value}.turn.$ordinal")
    }

    override suspend fun load(runId: RunId, turnId: TurnId): GameTurn? = mutex.withLock {
        decode(runId, turnId)
    }

    override suspend fun save(turn: GameTurn, expectedRevision: Long?): GameTurnStoreResult = mutex.withLock {
        try {
            val existing = queries.selectGmTurn(turn.runId.value, turn.turnId.value).executeAsOneOrNull()
            if (existing?.revision != expectedRevision) return@withLock GameTurnStoreResult.RevisionConflict
            val current = turn.toCurrentSchema()
            val ordinal = existing?.turn_ordinal
                ?: queries.selectLatestGmTurnOrdinal(turn.runId.value).executeAsOne() + 1
            queries.upsertGmTurn(
                current.runId.value,
                current.turnId.value,
                ordinal,
                current.revision,
                current.schemaVersion.toLong(),
                PersistenceCodec.encodeGameTurn(current),
            )
            GameTurnStoreResult.Success
        } catch (_: Exception) {
            GameTurnStoreResult.Failure("Unable to persist GM turn")
        }
    }

    override suspend fun history(
        runId: RunId,
        beforeOrdinalExclusive: Long?,
        limit: Int,
    ): GameTurnHistoryResult = mutex.withLock {
        if (limit !in 1..200) return@withLock GameTurnHistoryResult.Failure(
            "History limit must be within 1..200",
        )
        if (beforeOrdinalExclusive != null && beforeOrdinalExclusive <= 0) {
            return@withLock GameTurnHistoryResult.Failure("History boundary must be positive")
        }
        try {
            val boundary = beforeOrdinalExclusive ?: Long.MAX_VALUE
            val rows = queries.selectGmTurnPage(runId.value, boundary, limit.toLong()).executeAsList()
            val entries = rows.map { row ->
                decodeHistoryEntry(
                    expectedRunId = runId,
                    rawTurnId = row.turn_id,
                    ordinal = row.turn_ordinal,
                    revision = row.revision,
                    schemaVersion = row.turn_schema_version,
                    json = row.turn_json,
                )
            }.reversed()
            val eligibleCount = queries.countGmTurnsBefore(runId.value, boundary).executeAsOne()
            GameTurnHistoryResult.Success(
                GameTurnHistoryPage(entries, hasEarlier = eligibleCount > rows.size.toLong()),
            )
        } catch (_: Exception) {
            GameTurnHistoryResult.Failure("Unable to read GM turn history")
        }
    }

    private fun decode(runId: RunId, turnId: TurnId): GameTurn? {
        val row = queries.selectGmTurn(runId.value, turnId.value).executeAsOneOrNull() ?: return null
        require(row.turn_schema_version in
            LEGACY_GM_TURN_SCHEMA_VERSION.toLong()..CURRENT_GM_TURN_SCHEMA_VERSION.toLong()) {
            "Unsupported GM turn schema: ${row.turn_schema_version}"
        }
        return when (val decoded = PersistenceCodec.decodeGameTurn(row.turn_json)) {
            is PersistenceDecodeResult.Success -> decoded.value.also {
                require(
                    it.runId == runId &&
                        it.turnId == turnId &&
                        it.revision == row.revision &&
                        it.schemaVersion.toLong() == row.turn_schema_version,
                ) {
                    "GM turn identity or revision does not match its row"
                }
            }.toCurrentSchema()
            is PersistenceDecodeResult.Failure -> throw IllegalStateException(decoded.message)
        }
    }

    private fun decodeHistoryEntry(
        expectedRunId: RunId,
        rawTurnId: String,
        ordinal: Long,
        revision: Long,
        schemaVersion: Long,
        json: String,
    ): GameTurnHistoryEntry {
        val turnId = runCatching { TurnId(rawTurnId) }.getOrElse { TurnId("invalid.turn.$ordinal") }
        if (schemaVersion !in
            LEGACY_GM_TURN_SCHEMA_VERSION.toLong()..CURRENT_GM_TURN_SCHEMA_VERSION.toLong()) {
            return problemEntry(ordinal, turnId, GameTurnHistoryProblemCode.INVALID_SCHEMA)
        }
        val decoded = when (val result = PersistenceCodec.decodeGameTurn(json)) {
            is PersistenceDecodeResult.Success -> result.value
            is PersistenceDecodeResult.Failure -> return problemEntry(
                ordinal,
                turnId,
                GameTurnHistoryProblemCode.INVALID_JSON,
            )
        }
        if (
            decoded.runId != expectedRunId ||
            decoded.turnId.value != rawTurnId ||
            decoded.revision != revision ||
            decoded.schemaVersion.toLong() != schemaVersion
        ) {
            return problemEntry(ordinal, turnId, GameTurnHistoryProblemCode.IDENTITY_MISMATCH)
        }
        return runCatching { GameTurnHistoryEntry(ordinal, turn = decoded.toCurrentSchema()) }.getOrElse {
            problemEntry(ordinal, turnId, GameTurnHistoryProblemCode.INVALID_SCHEMA)
        }
    }

    private fun problemEntry(
        ordinal: Long,
        turnId: TurnId,
        code: GameTurnHistoryProblemCode,
    ) = GameTurnHistoryEntry(
        ordinal = ordinal,
        problem = GameTurnHistoryProblem(turnId, code, "GM turn history record is invalid"),
    )
}
