package io.worldloom.content.schema

import io.worldloom.definition.BooleanValue
import io.worldloom.definition.CheckOutcomeDefinition
import io.worldloom.definition.CheckProfileDefinition
import io.worldloom.definition.CheckResolutionMode
import io.worldloom.definition.ComponentDefinition
import io.worldloom.definition.CURRENT_WORLD_DEFINITION_SCHEMA_VERSION
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.FieldDefinition
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.TextValue
import io.worldloom.definition.ValueType
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.WorldDefinitionValidator
import io.worldloom.rules.module.api.CURRENT_RULE_MODULE_API_VERSION
import io.worldloom.rules.module.api.CURRENT_WORLD_MANIFEST_SCHEMA_VERSION
import io.worldloom.rules.module.api.ModuleVersion
import io.worldloom.rules.module.api.WorldManifest
import io.worldloom.rules.module.api.WorldModuleSelection
import io.worldloom.rules.module.registry.StandardRuleModules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContentProfileTest {
    @Test
    fun allFourCharacterCreationModesProduceValidatedDynamicEntitySeeds() {
        val definition = definition()
        val profile = assertIs<CharacterProfileValidationResult.Valid>(
            CharacterCreationProfileValidator.validate(profile(), definition),
        ).profile
        val fixed = assertIs<CharacterCreationResult.Success>(
            CharacterCreator.create(
                profile,
                CharacterCreationRequest("player.fixed", CharacterCreationMode.FIXED, DefinitionId("test.fixed")),
            ),
        )
        val template = assertIs<CharacterCreationResult.Success>(
            CharacterCreator.create(
                profile,
                CharacterCreationRequest(
                    "player.template",
                    CharacterCreationMode.TEMPLATE,
                    DefinitionId("test.template"),
                    listOf(assignment(IntegerValue(3))),
                ),
            ),
        )
        val pointBuy = assertIs<CharacterCreationResult.Success>(
            CharacterCreator.create(
                profile,
                CharacterCreationRequest(
                    "player.points",
                    CharacterCreationMode.POINT_BUY,
                    values = listOf(assignment(IntegerValue(4))),
                ),
            ),
        )
        val narrative = assertIs<CharacterCreationResult.Success>(
            CharacterCreator.create(
                profile,
                CharacterCreationRequest(
                    "player.narrative",
                    CharacterCreationMode.NARRATIVE,
                    values = listOf(assignment(IntegerValue(2))),
                    narrativeBackground = "A patient station mechanic",
                ),
            ),
        )

        assertEquals(3, value(fixed))
        assertEquals(3, value(template))
        assertEquals(4, value(pointBuy))
        assertEquals(3, pointBuy.pointsSpent)
        assertEquals(2, value(narrative))
    }

    @Test
    fun pointBuyRejectsBudgetOverflowAndNarrativeBoundary() {
        val validated = assertIs<CharacterProfileValidationResult.Valid>(
            CharacterCreationProfileValidator.validate(profile(), definition()),
        ).profile
        val expensive = CharacterCreator.create(
            validated,
            CharacterCreationRequest(
                "player.expensive",
                CharacterCreationMode.POINT_BUY,
                values = listOf(assignment(IntegerValue(5))),
            ),
        )
        val blankNarrative = CharacterCreator.create(
            validated,
            CharacterCreationRequest("player.blank", CharacterCreationMode.NARRATIVE),
        )
        assertIs<CharacterCreationResult.Failure>(expensive)
        assertIs<CharacterCreationResult.Failure>(blankNarrative)
    }

    @Test
    fun ruleProfileMustReferenceExistingChecksAndExactManifestModules() {
        val definition = definition()
        val selection = WorldModuleSelection(
            id = DefinitionId("worldloom.core.numeric-state"),
            version = ModuleVersion(1, 0, 0),
            parameters = mapOf(DefinitionId("worldloom.parameter.direct-adjustment") to BooleanValue(true)),
        )
        val manifest = WorldManifest(
            schemaVersion = CURRENT_WORLD_MANIFEST_SCHEMA_VERSION,
            runtimeApiVersion = CURRENT_RULE_MODULE_API_VERSION,
            worldId = definition.source.id,
            worldDefinitionPath = "world.json",
            modules = listOf(selection),
        )
        val profile = RuleProfile(
            schemaVersion = CURRENT_RULE_PROFILE_SCHEMA_VERSION,
            id = DefinitionId("test.rules"),
            checkProfileIds = listOf(DefinitionId("test.check")),
            modules = listOf(selection),
        )

        assertIs<RuleProfileValidationResult.Valid>(
            RuleProfileValidator.validate(profile, manifest, definition, StandardRuleModules.registry()),
        )
        val invalid = assertIs<RuleProfileValidationResult.Invalid>(
            RuleProfileValidator.validate(
                profile.copy(checkProfileIds = listOf(DefinitionId("missing.check")), modules = emptyList()),
                manifest,
                definition,
                StandardRuleModules.registry(),
            ),
        )
        assertTrue(invalid.problems.any { it.code == RuleProfileProblemCode.CHECK_PROFILE_NOT_FOUND })
        assertTrue(invalid.problems.any { it.code == RuleProfileProblemCode.MODULE_SELECTION_MISMATCH })
    }

    @Test
    fun contentProfilesRoundTripCanonicalJson() {
        val creation = profile()
        val decoded = assertIs<ContentProfileDecodeResult.Success<CharacterCreationProfile>>(
            ContentProfileCodec.decodeCharacterCreation(ContentProfileCodec.encodeCharacterCreation(creation)),
        ).value
        assertEquals(creation, decoded)
    }

    private fun profile() = CharacterCreationProfile(
        schemaVersion = CURRENT_CHARACTER_CREATION_SCHEMA_VERSION,
        id = DefinitionId("test.character-creation"),
        modes = CharacterCreationMode.entries.toSet(),
        fields = listOf(
            CharacterFieldRule(
                componentId = DefinitionId("test.attributes"),
                fieldId = DefinitionId("test.focus"),
                defaultValue = IntegerValue(1),
                minimumInteger = 1,
                maximumInteger = 5,
            ),
            CharacterFieldRule(
                componentId = DefinitionId("test.identity"),
                fieldId = DefinitionId("test.trait"),
                defaultValue = TextValue("unassigned"),
            ),
        ),
        fixedOptions = listOf(
            CharacterCreationOption("test.fixed".id(), "Fixed", listOf(assignment(IntegerValue(3)))),
        ),
        templates = listOf(
            CharacterCreationOption("test.template".id(), "Template", listOf(assignment(IntegerValue(2)))),
        ),
        pointBuyBudget = 3,
    )

    private fun definition(): io.worldloom.definition.ValidatedWorldDefinition {
        val source = WorldDefinition(
            schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
            id = DefinitionId("test.content-world"),
            title = "Content World",
            components = listOf(
                ComponentDefinition(
                    DefinitionId("test.attributes"),
                    listOf(FieldDefinition(DefinitionId("test.focus"), ValueType.INTEGER, minInteger = 0, maxInteger = 10)),
                ),
                ComponentDefinition(
                    DefinitionId("test.identity"),
                    listOf(FieldDefinition(DefinitionId("test.trait"), ValueType.TEXT)),
                ),
            ),
            initialEntities = emptyList(),
            checkProfiles = listOf(
                CheckProfileDefinition(
                    id = DefinitionId("test.check"),
                    label = "Check",
                    mode = CheckResolutionMode.DETERMINISTIC,
                    baseValue = 1,
                    outcomes = listOf(CheckOutcomeDefinition(DefinitionId("test.success"), "Success", 0)),
                ),
            ),
            presentation = emptyList(),
        )
        return assertIs<DefinitionValidationResult.Valid>(WorldDefinitionValidator.validate(source)).definition
    }

    private fun assignment(value: IntegerValue) = CharacterValueAssignment(
        DefinitionId("test.attributes"),
        DefinitionId("test.focus"),
        value,
    )

    private fun value(result: CharacterCreationResult.Success): Long =
        (result.entity.components.first { it.definitionId == DefinitionId("test.attributes") }
            .fields.single { it.id == DefinitionId("test.focus") }.value as IntegerValue).value

    private fun String.id() = DefinitionId(this)
}
