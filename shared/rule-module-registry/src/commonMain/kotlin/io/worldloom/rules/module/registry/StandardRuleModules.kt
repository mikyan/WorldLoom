package io.worldloom.rules.module.registry

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.ValueType
import io.worldloom.rules.module.api.CURRENT_RULE_MODULE_API_VERSION
import io.worldloom.rules.module.api.ModuleDependency
import io.worldloom.rules.module.api.ModuleParameterDefinition
import io.worldloom.rules.module.api.ModuleVersion
import io.worldloom.rules.module.api.RuleCapability
import io.worldloom.rules.module.api.RuleCapabilityKind
import io.worldloom.rules.module.api.RuleModule
import io.worldloom.rules.module.api.RuleModuleDescriptor

private val VERSION_1 = ModuleVersion(1, 0, 0)
private val VERSION_2 = ModuleVersion(2, 0, 0)
private val NUMERIC_STATE_ID = DefinitionId("worldloom.core.numeric-state")

object StandardRuleModules {
    val numericState: RuleModule = descriptorModule(
        id = NUMERIC_STATE_ID,
        parameters = listOf(
            ModuleParameterDefinition(
                DefinitionId("worldloom.parameter.direct-adjustment"),
                ValueType.BOOLEAN,
            ),
        ),
        capabilities = listOf(
            capability("worldloom.schema.numeric-component", RuleCapabilityKind.SCHEMA),
            capability("worldloom.command.numeric-adjust", RuleCapabilityKind.COMMAND),
            capability("worldloom.event.numeric-adjusted", RuleCapabilityKind.EVENT),
            capability("worldloom.tool.numeric.adjust", RuleCapabilityKind.TOOL),
            capability("worldloom.projection.numeric-field", RuleCapabilityKind.PROJECTION),
        ),
    )

    val randomCheck: RuleModule = descriptorModule(
        id = DefinitionId("worldloom.rules.random-check"),
        dependencies = listOf(ModuleDependency(NUMERIC_STATE_ID, VERSION_1, VERSION_2)),
        capabilities = listOf(
            capability("worldloom.schema.random-check-profile", RuleCapabilityKind.SCHEMA),
            capability("worldloom.command.resolve-check", RuleCapabilityKind.COMMAND),
            capability("worldloom.event.check-resolved", RuleCapabilityKind.EVENT),
            capability("worldloom.tool.check.resolve", RuleCapabilityKind.TOOL),
            capability("worldloom.projection.check-record", RuleCapabilityKind.PROJECTION),
        ),
    )

    val deterministicCheck: RuleModule = descriptorModule(
        id = DefinitionId("worldloom.rules.deterministic-check"),
        dependencies = listOf(ModuleDependency(NUMERIC_STATE_ID, VERSION_1, VERSION_2)),
        capabilities = listOf(
            capability("worldloom.schema.deterministic-check-profile", RuleCapabilityKind.SCHEMA),
            capability("worldloom.command.resolve-check", RuleCapabilityKind.COMMAND),
            capability("worldloom.event.check-resolved", RuleCapabilityKind.EVENT),
            capability("worldloom.tool.check.resolve", RuleCapabilityKind.TOOL),
            capability("worldloom.projection.check-record", RuleCapabilityKind.PROJECTION),
        ),
    )

    val all: List<RuleModule> = listOf(numericState, randomCheck, deterministicCheck)

    fun registry(): RuleModuleRegistry = RuleModuleRegistry(all)

    private fun descriptorModule(
        id: DefinitionId,
        dependencies: List<ModuleDependency> = emptyList(),
        parameters: List<ModuleParameterDefinition> = emptyList(),
        capabilities: List<RuleCapability> = emptyList(),
    ): RuleModule = object : RuleModule {
        override val descriptor: RuleModuleDescriptor = RuleModuleDescriptor(
            id = id,
            version = VERSION_1,
            minimumRuntimeApiVersion = CURRENT_RULE_MODULE_API_VERSION,
            maximumRuntimeApiVersion = CURRENT_RULE_MODULE_API_VERSION,
            dependencies = dependencies,
            parameters = parameters,
            capabilities = capabilities,
        )
    }

    private fun capability(
        id: String,
        kind: RuleCapabilityKind,
    ): RuleCapability = RuleCapability(DefinitionId(id), kind)
}
