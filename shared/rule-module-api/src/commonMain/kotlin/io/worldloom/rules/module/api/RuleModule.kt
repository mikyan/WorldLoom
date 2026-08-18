package io.worldloom.rules.module.api

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.TypedValue
import io.worldloom.definition.ValueType

enum class RuleCapabilityKind {
    SCHEMA,
    COMMAND,
    EVENT,
    TOOL,
    PROJECTION,
}

data class RuleCapability(
    val id: DefinitionId,
    val kind: RuleCapabilityKind,
)

data class ModuleParameterDefinition(
    val id: DefinitionId,
    val valueType: ValueType,
    val required: Boolean = true,
)

data class ModuleDependency(
    val moduleId: DefinitionId,
    val minimumVersion: ModuleVersion,
    val maximumVersionExclusive: ModuleVersion? = null,
) {
    fun accepts(version: ModuleVersion): Boolean =
        version >= minimumVersion && (maximumVersionExclusive == null || version < maximumVersionExclusive)
}

data class RuleModuleDescriptor(
    val id: DefinitionId,
    val version: ModuleVersion,
    val minimumRuntimeApiVersion: Int,
    val maximumRuntimeApiVersion: Int,
    val dependencies: List<ModuleDependency> = emptyList(),
    val parameters: List<ModuleParameterDefinition> = emptyList(),
    val capabilities: List<RuleCapability> = emptyList(),
) {
    init {
        require(minimumRuntimeApiVersion > 0) { "minimumRuntimeApiVersion must be positive" }
        require(maximumRuntimeApiVersion >= minimumRuntimeApiVersion) {
            "maximumRuntimeApiVersion must not be lower than minimumRuntimeApiVersion"
        }
    }

    fun supportsRuntime(apiVersion: Int): Boolean = apiVersion in minimumRuntimeApiVersion..maximumRuntimeApiVersion
}

/** A trusted Kotlin implementation bundled with the Runtime, never supplied by a world package. */
interface RuleModule {
    val descriptor: RuleModuleDescriptor
}

data class RegisteredRuleModule(
    val descriptor: RuleModuleDescriptor,
    val parameters: Map<DefinitionId, TypedValue>,
)

/** Immutable result of resolving one manifest against the Runtime's trusted module inventory. */
class RegisteredWorldModules(
    modules: List<RegisteredRuleModule>,
) {
    val modules: List<RegisteredRuleModule> = modules.toList()
    val capabilities: List<RuleCapability> = modules
        .flatMap { it.descriptor.capabilities }
        .sortedWith(compareBy(RuleCapability::kind, { it.id.value }))

    private val modulesById = this.modules.associateBy { it.descriptor.id }
    private val capabilitiesById = capabilities.associateBy(RuleCapability::id)

    fun module(id: DefinitionId): RegisteredRuleModule? = modulesById[id]

    fun capability(id: DefinitionId): RuleCapability? = capabilitiesById[id]

    fun capabilities(kind: RuleCapabilityKind): List<RuleCapability> = capabilities.filter { it.kind == kind }
}
