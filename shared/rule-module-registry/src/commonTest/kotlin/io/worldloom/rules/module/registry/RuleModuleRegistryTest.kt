package io.worldloom.rules.module.registry

import io.worldloom.definition.BooleanValue
import io.worldloom.definition.DefinitionId
import io.worldloom.rules.module.api.CURRENT_RULE_MODULE_API_VERSION
import io.worldloom.rules.module.api.CURRENT_WORLD_MANIFEST_SCHEMA_VERSION
import io.worldloom.rules.module.api.ModuleVersion
import io.worldloom.rules.module.api.RuleCapabilityKind
import io.worldloom.rules.module.api.WorldManifest
import io.worldloom.rules.module.api.WorldModuleSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleModuleRegistryTest {
    @Test
    fun temporalModulesPublishToolsOnlyWhenDependenciesAreSelected() {
        val selected = manifest(randomModuleSelection()).copy(
            modules = listOf(
                WorldModuleSelection(
                    DefinitionId("worldloom.core.numeric-state"),
                    ModuleVersion(1, 0, 0),
                    mapOf(DefinitionId("worldloom.parameter.direct-adjustment") to BooleanValue(true)),
                ),
                WorldModuleSelection(DefinitionId("worldloom.rules.world-time"), ModuleVersion(1, 0, 0)),
                WorldModuleSelection(DefinitionId("worldloom.rules.activity"), ModuleVersion(1, 0, 0)),
                WorldModuleSelection(DefinitionId("worldloom.rules.travel"), ModuleVersion(1, 0, 0)),
            ),
        )
        val result = assertIs<ModuleRegistrationResult.Success>(StandardRuleModules.registry().register(selected))

        assertEquals(
            setOf(
                "worldloom.tool.numeric.adjust",
                "worldloom.tool.time.advance",
                "worldloom.tool.activity.perform",
                "worldloom.tool.travel.perform",
            ),
            result.modules.capabilities(RuleCapabilityKind.TOOL).map { it.id.value }.toSet(),
        )

        val missingTime = selected.copy(modules = selected.modules.filterNot { it.id.value == "worldloom.rules.world-time" })
        assertTrue(
            assertIs<ModuleRegistrationResult.Failure>(StandardRuleModules.registry().register(missingTime))
                .problems.any { it.code == ModuleRegistrationProblemCode.DEPENDENCY_MISSING },
        )
    }

    @Test
    fun registersOnlyCapabilitiesFromExplicitlySelectedModules() {
        val result = assertIs<ModuleRegistrationResult.Success>(
            StandardRuleModules.registry().register(manifest(randomModuleSelection())),
        )

        assertNotNull(result.modules.capability(DefinitionId("worldloom.schema.numeric-component")))
        assertNotNull(result.modules.capability(DefinitionId("worldloom.schema.random-check-profile")))
        assertNull(result.modules.capability(DefinitionId("worldloom.schema.deterministic-check-profile")))
        assertEquals(
            setOf("worldloom.tool.numeric.adjust", "worldloom.tool.check.resolve"),
            result.modules.capabilities(RuleCapabilityKind.TOOL).map { it.id.value }.toSet(),
        )
    }

    @Test
    fun rejectsMissingDependencyBeforePublishingAnyRegistration() {
        val result = assertIs<ModuleRegistrationResult.Failure>(
            StandardRuleModules.registry().register(
                manifest(
                    WorldModuleSelection(
                        DefinitionId("worldloom.rules.random-check"),
                        ModuleVersion(1, 0, 0),
                    ),
                    includeNumeric = false,
                ),
            ),
        )

        assertTrue(result.problems.any { it.code == ModuleRegistrationProblemCode.DEPENDENCY_MISSING })
    }

    @Test
    fun rejectsUnavailableVersionAndWrongParameterType() {
        val wrongVersion = assertIs<ModuleRegistrationResult.Failure>(
            StandardRuleModules.registry().register(
                manifest(
                    WorldModuleSelection(
                        DefinitionId("worldloom.rules.random-check"),
                        ModuleVersion(2, 0, 0),
                    ),
                ),
            ),
        )
        assertEquals(
            ModuleRegistrationProblemCode.MODULE_VERSION_MISMATCH,
            wrongVersion.problems.first { it.path.endsWith("version") }.code,
        )

        val wrongParameter = assertIs<ModuleRegistrationResult.Failure>(
            StandardRuleModules.registry().register(
                manifest(randomModuleSelection(), directAdjustment = io.worldloom.definition.IntegerValue(1)),
            ),
        )
        assertTrue(wrongParameter.problems.any { it.code == ModuleRegistrationProblemCode.PARAMETER_TYPE_MISMATCH })
    }

    @Test
    fun rejectsUnsafeDefinitionPathAndIncompatibleRuntime() {
        val source = manifest(randomModuleSelection()).copy(
            runtimeApiVersion = CURRENT_RULE_MODULE_API_VERSION + 1,
            worldDefinitionPath = "../world.json",
        )

        val result = assertIs<ModuleRegistrationResult.Failure>(StandardRuleModules.registry().register(source))

        assertTrue(result.problems.any { it.code == ModuleRegistrationProblemCode.INCOMPATIBLE_RUNTIME_API })
        assertTrue(result.problems.any { it.code == ModuleRegistrationProblemCode.INVALID_WORLD_DEFINITION_PATH })
    }

    private fun manifest(
        additionalModule: WorldModuleSelection,
        includeNumeric: Boolean = true,
        directAdjustment: io.worldloom.definition.TypedValue = BooleanValue(true),
    ): WorldManifest = WorldManifest(
        schemaVersion = CURRENT_WORLD_MANIFEST_SCHEMA_VERSION,
        runtimeApiVersion = CURRENT_RULE_MODULE_API_VERSION,
        worldId = DefinitionId("contract.example"),
        worldDefinitionPath = "world.json",
        modules = buildList {
            if (includeNumeric) {
                add(
                    WorldModuleSelection(
                        DefinitionId("worldloom.core.numeric-state"),
                        ModuleVersion(1, 0, 0),
                        mapOf(DefinitionId("worldloom.parameter.direct-adjustment") to directAdjustment),
                    ),
                )
            }
            add(additionalModule)
        },
    )

    private fun randomModuleSelection(): WorldModuleSelection = WorldModuleSelection(
        DefinitionId("worldloom.rules.random-check"),
        ModuleVersion(1, 0, 0),
    )
}
