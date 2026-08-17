package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.WorldDefinitionCodec
import io.worldloom.definition.WorldDefinitionDecodeResult

data class WorldCatalogEntry(
    val id: DefinitionId,
    val title: String,
)

interface WorldCatalog {
    val entries: List<WorldCatalogEntry>

    suspend fun load(id: DefinitionId): WorldDefinition?
}

sealed interface StaticWorldCatalogResult {
    data class Success(val catalog: StaticWorldCatalog) : StaticWorldCatalogResult

    data class Failure(
        val sourceIndex: Int,
        val code: String,
        val message: String,
    ) : StaticWorldCatalogResult
}

/** In-memory catalog used by the initialization demo and tests. */
class StaticWorldCatalog private constructor(
    private val definitions: Map<DefinitionId, WorldDefinition>,
) : WorldCatalog {
    override val entries: List<WorldCatalogEntry> = definitions.values
        .map { WorldCatalogEntry(it.id, it.title) }
        .sortedBy { it.id.value }

    override suspend fun load(id: DefinitionId): WorldDefinition? = definitions[id]

    companion object {
        fun fromJsonSources(sources: List<String>): StaticWorldCatalogResult {
            val definitions = linkedMapOf<DefinitionId, WorldDefinition>()
            sources.forEachIndexed { index, source ->
                val definition = when (val decoded = WorldDefinitionCodec.decode(source)) {
                    is WorldDefinitionDecodeResult.Success -> decoded.definition
                    is WorldDefinitionDecodeResult.Failure -> {
                        return StaticWorldCatalogResult.Failure(index, decoded.code, decoded.message)
                    }
                }
                if (definitions.put(definition.id, definition) != null) {
                    return StaticWorldCatalogResult.Failure(
                        sourceIndex = index,
                        code = "catalog.duplicate_world",
                        message = "Duplicate world definition: ${definition.id}",
                    )
                }
            }
            return StaticWorldCatalogResult.Success(StaticWorldCatalog(definitions))
        }
    }
}
