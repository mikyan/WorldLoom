package io.worldloom.persistence

import io.worldloom.application.CURRENT_CHARACTER_CREATION_DRAFT_SCHEMA_VERSION
import io.worldloom.application.CharacterCreationDraft
import io.worldloom.application.CharacterCreationDraftStore
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.world.RunId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SqlDelightCharacterCreationDraftStore(
    database: WorldloomDatabase,
) : CharacterCreationDraftStore {
    private val queries = database.worldloomQueries
    private val mutex = Mutex()

    override suspend fun load(runId: RunId): CharacterCreationDraft? = mutex.withLock {
        val row = queries.selectCharacterCreationDraft(runId.value).executeAsOneOrNull() ?: return@withLock null
        require(row.draft_schema_version == CURRENT_CHARACTER_CREATION_DRAFT_SCHEMA_VERSION.toLong()) {
            "Unsupported character draft schema: ${row.draft_schema_version}"
        }
        when (val decoded = PersistenceCodec.decodeCharacterDraft(row.draft_json)) {
            is PersistenceDecodeResult.Success -> decoded.value.also { draft ->
                require(draft.runId == runId) { "Character draft Run ID does not match its row" }
            }
            is PersistenceDecodeResult.Failure -> throw IllegalStateException(decoded.message)
        }
    }

    override suspend fun save(draft: CharacterCreationDraft) = mutex.withLock {
        require(draft.schemaVersion == CURRENT_CHARACTER_CREATION_DRAFT_SCHEMA_VERSION) {
            "Unsupported character draft schema: ${draft.schemaVersion}"
        }
        queries.upsertCharacterCreationDraft(
            draft.runId.value,
            draft.schemaVersion.toLong(),
            PersistenceCodec.encodeCharacterDraft(draft),
        )
        Unit
    }

    override suspend fun delete(runId: RunId) = mutex.withLock {
        queries.deleteCharacterCreationDraft(runId.value)
        Unit
    }
}
