package io.worldloom.definition

import kotlinx.serialization.Serializable

const val CURRENT_WORLD_DEFINITION_SCHEMA_VERSION: Int = 2

@Serializable
data class FieldDefinition(
    val id: DefinitionId,
    val valueType: ValueType,
    val required: Boolean = true,
    val minInteger: Long? = null,
    val maxInteger: Long? = null,
)

@Serializable
data class ComponentDefinition(
    val id: DefinitionId,
    val fields: List<FieldDefinition>,
)

@Serializable
data class FieldSeed(
    val id: DefinitionId,
    val value: TypedValue,
)

@Serializable
data class ComponentSeed(
    val definitionId: DefinitionId,
    val fields: List<FieldSeed>,
)

@Serializable
data class EntitySeed(
    val entityId: String,
    val components: List<ComponentSeed>,
)

/** A data binding consumed by PresentationMapper rather than by the authoritative engine. */
@Serializable
data class PresentationFieldDefinition(
    val id: DefinitionId,
    val entityId: String,
    val componentId: DefinitionId,
    val fieldId: DefinitionId,
    val label: String,
    val adjustmentStep: Long,
)

@Serializable
data class PresentationCheckDefinition(
    val id: DefinitionId,
    val checkProfileId: DefinitionId,
    val label: String,
)

@Serializable
data class WorldDefinition(
    val schemaVersion: Int,
    val id: DefinitionId,
    val title: String,
    val components: List<ComponentDefinition>,
    val initialEntities: List<EntitySeed>,
    val checkProfiles: List<CheckProfileDefinition> = emptyList(),
    val presentation: List<PresentationFieldDefinition>,
    val presentationChecks: List<PresentationCheckDefinition> = emptyList(),
)
