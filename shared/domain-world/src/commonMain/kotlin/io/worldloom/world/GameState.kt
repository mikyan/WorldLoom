package io.worldloom.world

import io.worldloom.definition.ComponentSeed
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.EntitySeed
import io.worldloom.definition.TypedValue
import io.worldloom.definition.ValidatedWorldDefinition
import kotlinx.serialization.Serializable

@Serializable
data class ComponentInstance(
    val definitionId: DefinitionId,
    val fields: Map<DefinitionId, TypedValue>,
)

@Serializable
data class EntityState(
    val entityId: EntityId,
    val components: Map<DefinitionId, ComponentInstance>,
)

@Serializable
data class ModuleState(
    val fields: Map<DefinitionId, TypedValue>,
)

@Serializable
data class GameState(
    val runId: RunId,
    val worldDefinitionId: DefinitionId,
    val lastSequence: Long,
    val entities: Map<EntityId, EntityState>,
    val variables: Map<DefinitionId, TypedValue>,
    val moduleStates: Map<DefinitionId, ModuleState>,
)

object InitialGameStateFactory {
    fun create(
        definition: ValidatedWorldDefinition,
        runId: RunId,
    ): GameState =
        GameState(
            runId = runId,
            worldDefinitionId = definition.source.id,
            lastSequence = 0,
            entities = definition.source.initialEntities
                .sortedBy(EntitySeed::entityId)
                .associate { seed ->
                    val entityId = EntityId(seed.entityId)
                    entityId to seed.toState(entityId)
                },
            variables = emptyMap(),
            moduleStates = emptyMap(),
        )

    private fun EntitySeed.toState(entityId: EntityId): EntityState =
        EntityState(
            entityId = entityId,
            components = components
                .sortedBy { it.definitionId.value }
                .associate { seed -> seed.definitionId to seed.toState() },
        )

    private fun ComponentSeed.toState(): ComponentInstance =
        ComponentInstance(
            definitionId = definitionId,
            fields = fields
                .sortedBy { it.id.value }
                .associate { it.id to it.value },
        )
}
