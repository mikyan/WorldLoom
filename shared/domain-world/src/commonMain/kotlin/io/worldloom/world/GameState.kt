package io.worldloom.world

import io.worldloom.definition.ComponentSeed
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.EntitySeed
import io.worldloom.definition.TypedValue
import io.worldloom.definition.ValidatedWorldDefinition
import kotlinx.serialization.Serializable

@Serializable
enum class RunLifecycle {
    CREATED,
    CHARACTER_CREATION,
    ACTIVE,
    COMPLETED,
    ABANDONED,
}

object RunLifecycleTransitions {
    fun allows(from: RunLifecycle, to: RunLifecycle): Boolean = when (from) {
        RunLifecycle.CREATED -> to in setOf(RunLifecycle.CHARACTER_CREATION, RunLifecycle.ABANDONED)
        RunLifecycle.CHARACTER_CREATION -> to in setOf(RunLifecycle.ACTIVE, RunLifecycle.ABANDONED)
        RunLifecycle.ACTIVE -> to in setOf(RunLifecycle.COMPLETED, RunLifecycle.ABANDONED)
        RunLifecycle.COMPLETED, RunLifecycle.ABANDONED -> false
    }
}

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
    /** Defaults preserve snapshots created before explicit Run lifecycle events existed. */
    val lifecycle: RunLifecycle = RunLifecycle.ACTIVE,
    val playerEntityId: EntityId? = null,
    val currentSceneId: DefinitionId? = null,
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

    /** Creates the immutable base for a Run whose player Entity must be produced by authoritative Events. */
    fun createForCharacterCreation(
        definition: ValidatedWorldDefinition,
        runId: RunId,
        playerEntityId: EntityId,
    ): GameState {
        val base = create(definition, runId)
        return base.copy(
            entities = base.entities - playerEntityId,
            lifecycle = RunLifecycle.CREATED,
            playerEntityId = null,
            currentSceneId = null,
        )
    }

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
