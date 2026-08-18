package io.worldloom.rules.module.registry

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.valueType
import io.worldloom.rules.module.api.CURRENT_RULE_MODULE_API_VERSION
import io.worldloom.rules.module.api.CURRENT_WORLD_MANIFEST_SCHEMA_VERSION
import io.worldloom.rules.module.api.RegisteredRuleModule
import io.worldloom.rules.module.api.RegisteredWorldModules
import io.worldloom.rules.module.api.RuleModule
import io.worldloom.rules.module.api.WorldManifest

enum class ModuleRegistrationProblemCode {
    UNSUPPORTED_MANIFEST_SCHEMA,
    INCOMPATIBLE_RUNTIME_API,
    INVALID_WORLD_DEFINITION_PATH,
    DUPLICATE_MODULE_SELECTION,
    MODULE_NOT_FOUND,
    MODULE_VERSION_MISMATCH,
    MODULE_RUNTIME_INCOMPATIBLE,
    DEPENDENCY_MISSING,
    DEPENDENCY_VERSION_MISMATCH,
    DUPLICATE_PARAMETER,
    UNKNOWN_PARAMETER,
    MISSING_PARAMETER,
    PARAMETER_TYPE_MISMATCH,
    DUPLICATE_CAPABILITY,
}

data class ModuleRegistrationProblem(
    val code: ModuleRegistrationProblemCode,
    val path: String,
    val message: String,
)

sealed interface ModuleRegistrationResult {
    data class Success(val modules: RegisteredWorldModules) : ModuleRegistrationResult

    data class Failure(val problems: List<ModuleRegistrationProblem>) : ModuleRegistrationResult
}

class RuleModuleRegistry(
    modules: List<RuleModule>,
    private val runtimeApiVersion: Int = CURRENT_RULE_MODULE_API_VERSION,
) {
    private val modulesById = modules.groupBy { it.descriptor.id }

    fun register(manifest: WorldManifest): ModuleRegistrationResult {
        val problems = mutableListOf<ModuleRegistrationProblem>()
        validateManifestHeader(manifest, problems)

        val selected = linkedMapOf<DefinitionId, RegisteredRuleModule>()
        manifest.modules.forEachIndexed { index, selection ->
            val path = "modules[$index]"
            if (selected.containsKey(selection.id)) {
                problems += problem(
                    ModuleRegistrationProblemCode.DUPLICATE_MODULE_SELECTION,
                    "$path.id",
                    "Module is selected more than once: ${selection.id}",
                )
                return@forEachIndexed
            }

            val candidates = modulesById[selection.id]
            if (candidates == null) {
                problems += problem(
                    ModuleRegistrationProblemCode.MODULE_NOT_FOUND,
                    "$path.id",
                    "Module is not installed: ${selection.id}",
                )
                return@forEachIndexed
            }
            val module = candidates.firstOrNull { it.descriptor.version == selection.version }
            if (module == null) {
                problems += problem(
                    ModuleRegistrationProblemCode.MODULE_VERSION_MISMATCH,
                    "$path.version",
                    "Module ${selection.id} version ${selection.version} is not installed",
                )
                return@forEachIndexed
            }
            if (!module.descriptor.supportsRuntime(runtimeApiVersion)) {
                problems += problem(
                    ModuleRegistrationProblemCode.MODULE_RUNTIME_INCOMPATIBLE,
                    "$path.version",
                    "Module ${selection.id} does not support Runtime API $runtimeApiVersion",
                )
            }
            validateParameters(module, selection.parameters, path, problems)
            selected[selection.id] = RegisteredRuleModule(module.descriptor, selection.parameters)
        }

        validateDependencies(selected, problems)
        validateCapabilityOwnership(selected.values.toList(), problems)
        return if (problems.isEmpty()) {
            ModuleRegistrationResult.Success(RegisteredWorldModules(selected.values.toList()))
        } else {
            ModuleRegistrationResult.Failure(problems.toList())
        }
    }

    private fun validateManifestHeader(
        manifest: WorldManifest,
        problems: MutableList<ModuleRegistrationProblem>,
    ) {
        if (manifest.schemaVersion != CURRENT_WORLD_MANIFEST_SCHEMA_VERSION) {
            problems += problem(
                ModuleRegistrationProblemCode.UNSUPPORTED_MANIFEST_SCHEMA,
                "schemaVersion",
                "Unsupported world manifest schema version: ${manifest.schemaVersion}",
            )
        }
        if (manifest.runtimeApiVersion != runtimeApiVersion) {
            problems += problem(
                ModuleRegistrationProblemCode.INCOMPATIBLE_RUNTIME_API,
                "runtimeApiVersion",
                "World requires Runtime API ${manifest.runtimeApiVersion}, current API is $runtimeApiVersion",
            )
        }
        val path = manifest.worldDefinitionPath
        val hasUnsafeSegment = path.split('/').any { it == ".." || it.isBlank() }
        if (path.startsWith('/') || '\\' in path || hasUnsafeSegment || !path.endsWith(".json")) {
            problems += problem(
                ModuleRegistrationProblemCode.INVALID_WORLD_DEFINITION_PATH,
                "worldDefinitionPath",
                "World definition path must be a safe relative JSON path",
            )
        }
    }

    private fun validateParameters(
        module: RuleModule,
        supplied: Map<DefinitionId, io.worldloom.definition.TypedValue>,
        path: String,
        problems: MutableList<ModuleRegistrationProblem>,
    ) {
        val definitions = module.descriptor.parameters.associateBy { it.id }
        supplied.forEach { (id, value) ->
            val definition = definitions[id]
            if (definition == null) {
                problems += problem(
                    ModuleRegistrationProblemCode.UNKNOWN_PARAMETER,
                    "$path.parameters.$id",
                    "Unknown parameter for ${module.descriptor.id}: $id",
                )
            } else if (definition.valueType != value.valueType()) {
                problems += problem(
                    ModuleRegistrationProblemCode.PARAMETER_TYPE_MISMATCH,
                    "$path.parameters.$id",
                    "Expected ${definition.valueType}, found ${value.valueType()}",
                )
            }
        }
        definitions.values.filter { it.required && it.id !in supplied }.forEach { definition ->
            problems += problem(
                ModuleRegistrationProblemCode.MISSING_PARAMETER,
                "$path.parameters",
                "Missing required parameter: ${definition.id}",
            )
        }
    }

    private fun validateDependencies(
        selected: Map<DefinitionId, RegisteredRuleModule>,
        problems: MutableList<ModuleRegistrationProblem>,
    ) {
        selected.values.forEach { module ->
            module.descriptor.dependencies.forEach { dependency ->
                val resolved = selected[dependency.moduleId]
                if (resolved == null) {
                    problems += problem(
                        ModuleRegistrationProblemCode.DEPENDENCY_MISSING,
                        "modules.${module.descriptor.id}.dependencies",
                        "Module ${module.descriptor.id} requires ${dependency.moduleId}",
                    )
                } else if (!dependency.accepts(resolved.descriptor.version)) {
                    problems += problem(
                        ModuleRegistrationProblemCode.DEPENDENCY_VERSION_MISMATCH,
                        "modules.${module.descriptor.id}.dependencies",
                        "Module ${module.descriptor.id} does not accept ${dependency.moduleId} " +
                            "version ${resolved.descriptor.version}",
                    )
                }
            }
        }
    }

    private fun validateCapabilityOwnership(
        modules: List<RegisteredRuleModule>,
        problems: MutableList<ModuleRegistrationProblem>,
    ) {
        val owners = mutableMapOf<DefinitionId, DefinitionId>()
        modules.forEach { module ->
            module.descriptor.capabilities.forEach { capability ->
                val previous = owners.putIfAbsent(capability.id, module.descriptor.id)
                if (previous != null) {
                    problems += problem(
                        ModuleRegistrationProblemCode.DUPLICATE_CAPABILITY,
                        "modules.${module.descriptor.id}.capabilities",
                        "Capability ${capability.id} is already owned by $previous",
                    )
                }
            }
        }
    }

    private fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? {
        val previous = this[key]
        if (previous == null) this[key] = value
        return previous
    }

    private fun problem(
        code: ModuleRegistrationProblemCode,
        path: String,
        message: String,
    ): ModuleRegistrationProblem = ModuleRegistrationProblem(code, path, message)
}
