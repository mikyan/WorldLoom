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
private val WORLD_TIME_ID = DefinitionId("worldloom.rules.world-time")

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

    val worldTime: RuleModule = descriptorModule(
        id = WORLD_TIME_ID,
        capabilities = listOf(
            capability("worldloom.schema.world-time", RuleCapabilityKind.SCHEMA),
            capability("worldloom.command.time.advance", RuleCapabilityKind.COMMAND),
            capability("worldloom.event.time-advanced", RuleCapabilityKind.EVENT),
            capability("worldloom.tool.time.advance", RuleCapabilityKind.TOOL),
            capability("worldloom.projection.world-time", RuleCapabilityKind.PROJECTION),
        ),
    )

    val activity: RuleModule = descriptorModule(
        id = DefinitionId("worldloom.rules.activity"),
        dependencies = listOf(
            ModuleDependency(WORLD_TIME_ID, VERSION_1, VERSION_2),
            ModuleDependency(NUMERIC_STATE_ID, VERSION_1, VERSION_2),
        ),
        capabilities = listOf(
            capability("worldloom.schema.activity", RuleCapabilityKind.SCHEMA),
            capability("worldloom.command.activity.perform", RuleCapabilityKind.COMMAND),
            capability("worldloom.event.activity-completed", RuleCapabilityKind.EVENT),
            capability("worldloom.tool.activity.perform", RuleCapabilityKind.TOOL),
            capability("worldloom.projection.activity", RuleCapabilityKind.PROJECTION),
        ),
    )

    val travel: RuleModule = descriptorModule(
        id = DefinitionId("worldloom.rules.travel"),
        dependencies = listOf(ModuleDependency(WORLD_TIME_ID, VERSION_1, VERSION_2)),
        capabilities = listOf(
            capability("worldloom.schema.travel-route", RuleCapabilityKind.SCHEMA),
            capability("worldloom.command.travel.perform", RuleCapabilityKind.COMMAND),
            capability("worldloom.event.travel-completed", RuleCapabilityKind.EVENT),
            capability("worldloom.tool.travel.perform", RuleCapabilityKind.TOOL),
            capability("worldloom.projection.travel-route", RuleCapabilityKind.PROJECTION),
        ),
    )

    val inventory: RuleModule = descriptorModule(
        id = DefinitionId("worldloom.rules.inventory"),
        capabilities = listOf(
            capability("worldloom.schema.inventory", RuleCapabilityKind.SCHEMA),
            capability("worldloom.command.inventory.change", RuleCapabilityKind.COMMAND),
            capability("worldloom.event.inventory.changed", RuleCapabilityKind.EVENT),
            capability("worldloom.tool.inventory.change", RuleCapabilityKind.TOOL),
            capability("worldloom.projection.inventory", RuleCapabilityKind.PROJECTION),
        ),
    )

    val condition: RuleModule = descriptorModule(
        id = DefinitionId("worldloom.rules.condition"),
        dependencies = listOf(ModuleDependency(WORLD_TIME_ID, VERSION_1, VERSION_2)),
        capabilities = listOf(
            capability("worldloom.schema.condition", RuleCapabilityKind.SCHEMA),
            capability("worldloom.command.condition.update", RuleCapabilityKind.COMMAND),
            capability("worldloom.event.condition.updated", RuleCapabilityKind.EVENT),
            capability("worldloom.tool.condition.update", RuleCapabilityKind.TOOL),
            capability("worldloom.projection.condition", RuleCapabilityKind.PROJECTION),
        ),
    )

    val relationship: RuleModule = descriptorModule(
        id = DefinitionId("worldloom.rules.relationship"),
        capabilities = listOf(
            capability("worldloom.schema.relationship", RuleCapabilityKind.SCHEMA),
            capability("worldloom.command.relationship.adjust", RuleCapabilityKind.COMMAND),
            capability("worldloom.event.relationship.adjusted", RuleCapabilityKind.EVENT),
            capability("worldloom.tool.relationship.adjust", RuleCapabilityKind.TOOL),
            capability("worldloom.projection.relationship", RuleCapabilityKind.PROJECTION),
        ),
    )

    val quest: RuleModule = descriptorModule(
        id = DefinitionId("worldloom.rules.quest"),
        capabilities = listOf(
            capability("worldloom.schema.quest", RuleCapabilityKind.SCHEMA),
            capability("worldloom.command.quest.advance", RuleCapabilityKind.COMMAND),
            capability("worldloom.event.quest.advanced", RuleCapabilityKind.EVENT),
            capability("worldloom.tool.quest.advance", RuleCapabilityKind.TOOL),
            capability("worldloom.projection.quest", RuleCapabilityKind.PROJECTION),
        ),
    )

    val progressClock: RuleModule = descriptorModule(
        id = DefinitionId("worldloom.rules.progress-clock"),
        capabilities = listOf(
            capability("worldloom.schema.progress-clock", RuleCapabilityKind.SCHEMA),
            capability("worldloom.command.progress-clock.advance", RuleCapabilityKind.COMMAND),
            capability("worldloom.event.progress-clock.advanced", RuleCapabilityKind.EVENT),
            capability("worldloom.tool.progress-clock.advance", RuleCapabilityKind.TOOL),
            capability("worldloom.projection.progress-clock", RuleCapabilityKind.PROJECTION),
        ),
    )

    val all: List<RuleModule> = listOf(
        numericState,
        randomCheck,
        deterministicCheck,
        worldTime,
        activity,
        travel,
        inventory,
        condition,
        relationship,
        quest,
        progressClock,
    )

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
