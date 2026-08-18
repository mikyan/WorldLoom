package io.worldloom.content.generation

import io.worldloom.content.schema.CURRENT_CHARACTER_CREATION_SCHEMA_VERSION
import io.worldloom.content.schema.CURRENT_RULE_PROFILE_SCHEMA_VERSION
import io.worldloom.content.schema.CharacterCreationMode
import io.worldloom.content.schema.CharacterCreationOption
import io.worldloom.content.schema.CharacterCreationProfile
import io.worldloom.content.schema.CharacterFieldRule
import io.worldloom.content.schema.CharacterValueAssignment
import io.worldloom.content.schema.RuleProfile
import io.worldloom.definition.BooleanValue
import io.worldloom.definition.CheckOutcomeDefinition
import io.worldloom.definition.CheckProfileDefinition
import io.worldloom.definition.CheckResolutionMode
import io.worldloom.definition.ComponentDefinition
import io.worldloom.definition.ComponentSeed
import io.worldloom.definition.CURRENT_WORLD_DEFINITION_SCHEMA_VERSION
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.EntitySeed
import io.worldloom.definition.FieldDefinition
import io.worldloom.definition.FieldSeed
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.PresentationCheckDefinition
import io.worldloom.definition.PresentationFieldDefinition
import io.worldloom.definition.ValueType
import io.worldloom.definition.WorldDefinition
import io.worldloom.rules.module.api.CURRENT_RULE_MODULE_API_VERSION
import io.worldloom.rules.module.api.CURRENT_WORLD_MANIFEST_SCHEMA_VERSION
import io.worldloom.rules.module.api.ModuleVersion
import io.worldloom.rules.module.api.WorldManifest
import io.worldloom.rules.module.api.WorldModuleSelection

/** Offline deterministic scaffold used for tests, previews, and fallback authoring; model-backed creation implements the same interface. */
class DeterministicCreationModel : WorldCreationModel {
    override suspend fun outline(request: WorldGenerationRequest, document: SourceDocument): WorldOutline = WorldOutline(
        title = request.title ?: document.title,
        premise = document.chunks.first().text.take(500),
        entityNames = document.sections.map(SourceSection::title).filter(String::isNotBlank).distinct().take(12),
    )

    override suspend fun draft(
        request: WorldGenerationRequest,
        document: SourceDocument,
        outline: WorldOutline,
    ): GeneratedWorldDraft {
        val namespace = request.worldId.value
        val componentId = DefinitionId("$namespace.character")
        val fieldId = DefinitionId("$namespace.momentum")
        val checkId = DefinitionId("$namespace.check.default")
        val value = IntegerValue(5)
        val definition = WorldDefinition(
            schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
            id = request.worldId,
            title = outline.title,
            components = listOf(
                ComponentDefinition(componentId, listOf(FieldDefinition(fieldId, ValueType.INTEGER, minInteger = 0, maxInteger = 10))),
            ),
            initialEntities = listOf(
                EntitySeed("player", listOf(ComponentSeed(componentId, listOf(FieldSeed(fieldId, value))))),
            ),
            checkProfiles = listOf(
                CheckProfileDefinition(
                    id = checkId,
                    label = "Default check",
                    mode = CheckResolutionMode.DETERMINISTIC,
                    outcomes = listOf(CheckOutcomeDefinition(DefinitionId("$namespace.outcome.success"), "Success", 0)),
                ),
            ),
            presentation = listOf(
                PresentationFieldDefinition(
                    id = DefinitionId("$namespace.presentation.momentum"),
                    entityId = "player",
                    componentId = componentId,
                    fieldId = fieldId,
                    label = "Momentum",
                    adjustmentStep = 1,
                ),
            ),
            presentationChecks = listOf(
                PresentationCheckDefinition(DefinitionId("$namespace.presentation.check"), checkId, "Act"),
            ),
        )
        val modules = listOf(
            WorldModuleSelection(
                id = DefinitionId("worldloom.core.numeric-state"),
                version = ModuleVersion(1, 0, 0),
                parameters = mapOf(DefinitionId("worldloom.parameter.direct-adjustment") to BooleanValue(true)),
            ),
            WorldModuleSelection(
                id = DefinitionId("worldloom.rules.deterministic-check"),
                version = ModuleVersion(1, 0, 0),
            ),
        )
        val manifest = WorldManifest(
            schemaVersion = CURRENT_WORLD_MANIFEST_SCHEMA_VERSION,
            runtimeApiVersion = CURRENT_RULE_MODULE_API_VERSION,
            worldId = request.worldId,
            worldDefinitionPath = "definitions/world.json",
            modules = modules,
        )
        val creation = CharacterCreationProfile(
            schemaVersion = CURRENT_CHARACTER_CREATION_SCHEMA_VERSION,
            id = DefinitionId("$namespace.character-creation"),
            modes = setOf(CharacterCreationMode.FIXED, CharacterCreationMode.NARRATIVE),
            fields = listOf(CharacterFieldRule(componentId, fieldId, value, 0, 10)),
            fixedOptions = listOf(
                CharacterCreationOption(
                    id = DefinitionId("$namespace.character.default"),
                    label = "Default character",
                    values = listOf(CharacterValueAssignment(componentId, fieldId, value)),
                ),
            ),
        )
        val rules = RuleProfile(
            schemaVersion = CURRENT_RULE_PROFILE_SCHEMA_VERSION,
            id = DefinitionId("$namespace.rules"),
            checkProfileIds = listOf(checkId),
            modules = modules,
        )
        return GeneratedWorldDraft(
            manifest = manifest,
            definition = definition,
            characterCreation = creation,
            rules = rules,
            sourceMappings = listOf(SourceMapping("player", listOf(document.chunks.first().id))),
        )
    }
}
