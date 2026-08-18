package io.worldloom.world.packageformat

import io.worldloom.behavior.runtime.BehaviorCodec
import io.worldloom.behavior.runtime.BehaviorDecodeResult
import io.worldloom.behavior.runtime.BehaviorValidationResult
import io.worldloom.behavior.runtime.BehaviorValidator
import io.worldloom.behavior.runtime.BehaviorCommandRegistry
import io.worldloom.behavior.runtime.ValidatedBehavior
import io.worldloom.content.schema.CharacterCreationProfileCodec
import io.worldloom.content.schema.CharacterCreationProfileDecodeResult
import io.worldloom.content.schema.CharacterCreationProfileValidator
import io.worldloom.content.schema.CharacterProfileValidationResult
import io.worldloom.content.schema.ValidatedCharacterCreationProfile
import io.worldloom.definition.CheckResolutionMode
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.rules.module.api.RegisteredWorldModules
import io.worldloom.rules.module.api.RuleCapabilityKind
import io.worldloom.rules.ACTIVITY_MODULE_ID
import io.worldloom.rules.TemporalAdventureDefinition
import io.worldloom.rules.TemporalAdventureDefinitionValidator
import io.worldloom.rules.TemporalDefinitionValidationResult
import io.worldloom.rules.TRAVEL_MODULE_ID
import io.worldloom.rules.WORLD_TIME_MODULE_ID
import io.worldloom.rules.AdventureDefinitionValidationResult
import io.worldloom.rules.AdventureStateDefinition
import io.worldloom.rules.AdventureStateDefinitionValidator
import io.worldloom.rules.CONDITION_MODULE_ID
import io.worldloom.rules.INVENTORY_MODULE_ID
import io.worldloom.rules.PROGRESS_CLOCK_MODULE_ID
import io.worldloom.rules.QUEST_MODULE_ID
import io.worldloom.rules.RELATIONSHIP_MODULE_ID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val PLAYABLE_WORLD_CONTRACT_SCHEMA_V1: String = "worldloom.playable-world/v1"

private val CORE_BEHAVIOR_EVENT_TYPES = setOf(
    DefinitionId("worldloom.event.run-lifecycle.changed"),
    DefinitionId("worldloom.event.player.created"),
    DefinitionId("worldloom.event.scene.entered"),
    DefinitionId("worldloom.event.scene.exited"),
    DefinitionId("worldloom.event.action-outcome.applied"),
    DefinitionId("worldloom.event.schedule.fired"),
    DefinitionId("worldloom.event.adventure-ending.reached"),
    DefinitionId("worldloom.event.npc.public-action"),
)

@Serializable
data class PlayableCharacterEntry(
    val profilePath: String? = null,
    /** Stable player Entity template selected by the world package for profile-based creation. */
    val playerEntityId: String? = null,
    val prebuiltPlayerEntityId: String? = null,
)

@Serializable
data class PlayableScene(
    val id: DefinitionId,
    val label: String,
    val actionIds: List<DefinitionId>,
    val participantEntityIds: List<String> = emptyList(),
    val description: String? = null,
)

@Serializable
enum class PlayableOutcomeKind { SUCCESS, COST, FAILURE }

@Serializable
data class PlayableProgression(
    val nextSceneId: DefinitionId? = null,
    val objectiveIds: List<DefinitionId> = emptyList(),
    val endingId: DefinitionId? = null,
    val retryAllowed: Boolean = false,
)

@Serializable
data class PlayableActionResolution(
    val outcomeId: DefinitionId,
    val kind: PlayableOutcomeKind,
    val progression: PlayableProgression,
)

@Serializable
data class PlayableAction(
    val id: DefinitionId,
    val sceneId: DefinitionId,
    val label: String? = null,
    val checkProfileId: DefinitionId? = null,
    val requiredQuestId: DefinitionId? = null,
    val requiredQuestStageId: DefinitionId? = null,
    val resolutions: List<PlayableActionResolution>,
)

@Serializable
data class PlayableObjective(
    val id: DefinitionId,
    val label: String,
    val presentationId: DefinitionId? = null,
)

@Serializable
data class PlayableEnding(
    val id: DefinitionId,
    val label: String,
    val summary: String? = null,
)

@Serializable
data class PlayableBehaviorReference(
    val id: DefinitionId,
    val path: String,
)

@Serializable
enum class PlayableNpcCapability { SPEAK, ACT }

/** Declarative NPC identity and least-authority perception/tool boundary for one fixed world. */
@Serializable
data class PlayableNpcProfile(
    val id: DefinitionId,
    val entityId: String,
    val displayName: String,
    val identityPrompt: String,
    val wakeEventTypes: List<DefinitionId>,
    val visiblePresentationIds: List<DefinitionId> = emptyList(),
    val goals: List<String> = emptyList(),
    val privateKnowledge: List<String> = emptyList(),
    val capabilities: Set<PlayableNpcCapability> = setOf(PlayableNpcCapability.SPEAK),
    val publicActionIds: List<DefinitionId> = emptyList(),
)

@Serializable
data class PlayableRouteStep(
    val actionId: DefinitionId,
    /** Used only for actions without a CheckProfile. Checked outcomes are derived from the recorded roll. */
    val selectedOutcomeId: DefinitionId? = null,
    /** Exact dice facts for RANDOM checks; replay must reuse these values. */
    val randomValues: List<Int> = emptyList(),
)

@Serializable
data class PlayableRouteFixture(
    val id: DefinitionId,
    val steps: List<PlayableRouteStep>,
    val expectedEndingId: DefinitionId,
)

/** Minimum topic-neutral content graph required before a package may claim to be playable. */
@Serializable
data class PlayableWorldContract(
    val schema: String,
    val contentVersion: Int = 1,
    val estimatedPlayMinutes: Int? = null,
    val catalogPriority: Int = 0,
    val character: PlayableCharacterEntry,
    val initialSceneId: DefinitionId,
    val requiredModuleIds: List<DefinitionId>,
    val scenes: List<PlayableScene>,
    val actions: List<PlayableAction>,
    val objectives: List<PlayableObjective>,
    val endings: List<PlayableEnding>,
    val presentationIds: List<DefinitionId>,
    val temporal: TemporalAdventureDefinition? = null,
    val adventureState: AdventureStateDefinition? = null,
    val behaviors: List<PlayableBehaviorReference> = emptyList(),
    val npcs: List<PlayableNpcProfile> = emptyList(),
    val goldenRoutes: List<PlayableRouteFixture>,
)

sealed interface PlayableWorldContractDecodeResult {
    data class Success(val contract: PlayableWorldContract) : PlayableWorldContractDecodeResult
    data class Failure(val message: String) : PlayableWorldContractDecodeResult
}

@OptIn(ExperimentalSerializationApi::class)
object PlayableWorldContractCodec {
    private val json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    fun decode(source: String): PlayableWorldContractDecodeResult = try {
        PlayableWorldContractDecodeResult.Success(json.decodeFromString<PlayableWorldContract>(source))
    } catch (error: SerializationException) {
        PlayableWorldContractDecodeResult.Failure(error.message ?: "Playable world contract JSON is invalid")
    } catch (error: IllegalArgumentException) {
        PlayableWorldContractDecodeResult.Failure(error.message ?: "Playable world contract contains an invalid value")
    }

    fun encode(contract: PlayableWorldContract): String = json.encodeToString(contract)
}

enum class PlayableWorldProblemCode {
    UNSUPPORTED_SCHEMA,
    CONTENT_METADATA_INVALID,
    MISSING_CHARACTER_ENTRY,
    AMBIGUOUS_CHARACTER_ENTRY,
    CHARACTER_PROFILE_MISSING,
    CHARACTER_PROFILE_INVALID,
    PREBUILT_PLAYER_UNKNOWN,
    PLAYER_ENTITY_MISSING,
    PLAYER_ENTITY_UNKNOWN,
    REQUIRED_MODULE_MISSING,
    DUPLICATE_ID,
    BLANK_LABEL,
    INITIAL_SCENE_UNKNOWN,
    SCENE_ACTION_UNKNOWN,
    SCENE_PARTICIPANT_UNKNOWN,
    ACTION_SCENE_UNKNOWN,
    ACTION_NOT_AVAILABLE_IN_SCENE,
    ACTION_CHECK_UNKNOWN,
    ACTION_REQUIREMENT_INVALID,
    ACTION_OUTCOME_MISMATCH,
    ACTION_FAILURE_MISSING,
    PROGRESSION_EMPTY,
    PROGRESSION_AMBIGUOUS,
    PROGRESSION_SCENE_UNKNOWN,
    PROGRESSION_OBJECTIVE_UNKNOWN,
    PROGRESSION_ENDING_UNKNOWN,
    PRESENTATION_UNKNOWN,
    BEHAVIOR_MISSING,
    BEHAVIOR_INVALID,
    BEHAVIOR_ID_MISMATCH,
    NPC_INVALID,
    DEAD_END_SCENE,
    ENDING_UNREACHABLE,
    ROUTE_EMPTY,
    ROUTE_ACTION_UNAVAILABLE,
    ROUTE_OUTCOME_UNKNOWN,
    ROUTE_RANDOM_RECORD_INVALID,
    ROUTE_ENDING_MISMATCH,
    TEMPORAL_INVALID,
    ADVENTURE_STATE_INVALID,
}

data class PlayableWorldProblem(
    val code: PlayableWorldProblemCode,
    val path: String,
    val message: String,
)

data class PlayableRouteTraceEntry(
    val stepIndex: Int,
    val actionId: DefinitionId,
    val outcomeId: DefinitionId,
    val fromSceneId: DefinitionId,
    val nextSceneId: DefinitionId?,
    val objectiveIds: List<DefinitionId>,
    val endingId: DefinitionId?,
)

sealed interface PlayableRouteSimulationResult {
    data class Complete(
        val routeId: DefinitionId,
        val endingId: DefinitionId,
        val completedObjectiveIds: Set<DefinitionId>,
        val trace: List<PlayableRouteTraceEntry>,
    ) : PlayableRouteSimulationResult

    data class Failure(val problem: PlayableWorldProblem) : PlayableRouteSimulationResult
}

@ConsistentCopyVisibility
data class ValidatedPlayableWorldContract internal constructor(
    val source: PlayableWorldContract,
    val characterProfile: ValidatedCharacterCreationProfile?,
    val behaviors: List<ValidatedBehavior>,
    private val definition: ValidatedWorldDefinition,
    private val scenesById: Map<DefinitionId, PlayableScene>,
    private val actionsById: Map<DefinitionId, PlayableAction>,
    private val objectivesById: Map<DefinitionId, PlayableObjective>,
    private val endingsById: Map<DefinitionId, PlayableEnding>,
    private val routesById: Map<DefinitionId, PlayableRouteFixture>,
) {
    fun scene(id: DefinitionId): PlayableScene? = scenesById[id]

    fun action(id: DefinitionId): PlayableAction? = actionsById[id]

    fun ending(id: DefinitionId): PlayableEnding? = endingsById[id]

    fun route(id: DefinitionId): PlayableRouteFixture? = routesById[id]

    fun simulate(routeId: DefinitionId): PlayableRouteSimulationResult {
        val route = routesById[routeId] ?: return failure(
            PlayableWorldProblemCode.ROUTE_ENDING_MISMATCH,
            "goldenRoutes",
            "Unknown golden route: $routeId",
        )
        return simulateRoute(
            route,
            source.initialSceneId,
            definition,
            scenesById,
            actionsById,
            objectivesById,
            endingsById,
        )
    }
}

sealed interface PlayableWorldValidationResult {
    data class Valid(val contract: ValidatedPlayableWorldContract) : PlayableWorldValidationResult
    data class Invalid(val problems: List<PlayableWorldProblem>) : PlayableWorldValidationResult
}

object PlayableWorldValidator {
    fun validate(
        contract: PlayableWorldContract,
        definition: ValidatedWorldDefinition,
        modules: RegisteredWorldModules,
        entries: Map<String, ByteArray>,
    ): PlayableWorldValidationResult {
        val problems = mutableListOf<PlayableWorldProblem>()
        if (contract.schema != PLAYABLE_WORLD_CONTRACT_SCHEMA_V1) {
            problems += problem(
                PlayableWorldProblemCode.UNSUPPORTED_SCHEMA,
                "schema",
                "Unsupported playable world schema: ${contract.schema}",
            )
        }
        if (contract.contentVersion <= 0 ||
            (contract.estimatedPlayMinutes != null && contract.estimatedPlayMinutes !in 15..240) ||
            contract.catalogPriority !in -1_000..1_000
        ) problems += problem(
            PlayableWorldProblemCode.CONTENT_METADATA_INVALID,
            "contentVersion",
            "Content version must be positive, estimated play time 15 to 240 minutes, and catalog priority -1000 to 1000",
        )

        val characterProfile = validateCharacter(contract.character, definition, entries, problems)
        validateRequiredModules(contract, modules, problems)

        val scenes = index(contract.scenes, PlayableScene::id, "scenes", problems)
        val actions = index(contract.actions, PlayableAction::id, "actions", problems)
        val objectives = index(contract.objectives, PlayableObjective::id, "objectives", problems)
        val endings = index(contract.endings, PlayableEnding::id, "endings", problems)
        val routes = index(contract.goldenRoutes, PlayableRouteFixture::id, "goldenRoutes", problems)

        validateLabels(contract, problems)
        if (contract.initialSceneId !in scenes) {
            problems += problem(
                PlayableWorldProblemCode.INITIAL_SCENE_UNKNOWN,
                "initialSceneId",
                "Initial scene is not declared: ${contract.initialSceneId}",
            )
        }
        validateScenes(contract, definition, scenes, actions, problems)
        validateNpcs(contract, definition, scenes, modules, problems)
        validateTemporal(contract, definition, scenes.keys, problems)
        validateAdventureState(contract, definition, problems)
        validateActions(contract, definition, scenes, actions, objectives, endings, problems)
        validatePresentation(contract, definition, problems)
        val behaviors = validateBehaviors(contract, definition, modules, entries, problems)
        validateGraph(contract, scenes, actions, endings, problems)

        val candidate = ValidatedPlayableWorldContract(
            contract,
            characterProfile,
            behaviors,
            definition,
            scenes,
            actions,
            objectives,
            endings,
            routes,
        )
        contract.goldenRoutes.forEachIndexed { index, route ->
            if (route.steps.isEmpty()) {
                problems += problem(
                    PlayableWorldProblemCode.ROUTE_EMPTY,
                    "goldenRoutes[$index].steps",
                    "Golden route must contain at least one action",
                )
            } else {
                val simulation = candidate.simulate(route.id)
                if (simulation is PlayableRouteSimulationResult.Failure) {
                    problems += simulation.problem.copy(path = "goldenRoutes[$index].${simulation.problem.path}")
                }
            }
        }

        return if (problems.isEmpty()) {
            PlayableWorldValidationResult.Valid(candidate)
        } else {
            PlayableWorldValidationResult.Invalid(problems.distinct())
        }
    }

    private fun validateNpcs(
        contract: PlayableWorldContract,
        definition: ValidatedWorldDefinition,
        scenes: Map<DefinitionId, PlayableScene>,
        modules: RegisteredWorldModules,
        problems: MutableList<PlayableWorldProblem>,
    ) {
        duplicateIds(contract.npcs.map(PlayableNpcProfile::id), "npcs", problems)
        val entityIds = definition.source.initialEntities.map { it.entityId }.toSet()
        val presentationIds = definition.source.presentation.map { it.id }.toSet()
        val allowedEvents = allowedEventTypes(modules)
        val seenEntities = mutableSetOf<String>()
        contract.npcs.forEachIndexed { index, npc ->
            val path = "npcs[$index]"
            if (!seenEntities.add(npc.entityId)) problems += problem(
                PlayableWorldProblemCode.NPC_INVALID,
                "$path.entityId",
                "NPC Entity is configured more than once: ${npc.entityId}",
            )
            if (npc.entityId !in entityIds) problems += problem(
                PlayableWorldProblemCode.NPC_INVALID,
                "$path.entityId",
                "NPC Entity is not initialized: ${npc.entityId}",
            )
            if (npc.displayName.isBlank() || npc.identityPrompt.isBlank()) problems += problem(
                PlayableWorldProblemCode.NPC_INVALID,
                path,
                "NPC display name and identity prompt must not be blank",
            )
            if (npc.identityPrompt.length > 2_000 || npc.privateKnowledge.any { it.isBlank() || it.length > 1_000 } ||
                npc.goals.any { it.isBlank() || it.length > 500 }
            ) problems += problem(
                PlayableWorldProblemCode.NPC_INVALID,
                path,
                "NPC prompt, knowledge or goal text exceeds its validated boundary",
            )
            if (npc.wakeEventTypes.isEmpty() || npc.wakeEventTypes.distinct().size != npc.wakeEventTypes.size ||
                npc.wakeEventTypes.any { it !in allowedEvents }
            ) problems += problem(
                PlayableWorldProblemCode.NPC_INVALID,
                "$path.wakeEventTypes",
                "NPC wake events must be unique registered Event capabilities",
            )
            if (npc.visiblePresentationIds.distinct().size != npc.visiblePresentationIds.size ||
                npc.visiblePresentationIds.any { it !in presentationIds }
            ) problems += problem(
                PlayableWorldProblemCode.NPC_INVALID,
                "$path.visiblePresentationIds",
                "NPC perception references an unknown PresentationDefinition",
            )
            val appearsInScene = scenes.values.any { scene -> npc.entityId in scene.participantEntityIds }
            if (!appearsInScene) problems += problem(
                PlayableWorldProblemCode.NPC_INVALID,
                "$path.entityId",
                "NPC must participate in at least one scene",
            )
            if ((PlayableNpcCapability.ACT in npc.capabilities) != npc.publicActionIds.isNotEmpty() ||
                npc.publicActionIds.distinct().size != npc.publicActionIds.size
            ) problems += problem(
                PlayableWorldProblemCode.NPC_INVALID,
                "$path.publicActionIds",
                "NPC ACT capability requires a unique non-empty public action whitelist",
            )
        }
    }

    private fun validateCharacter(
        character: PlayableCharacterEntry,
        definition: ValidatedWorldDefinition,
        entries: Map<String, ByteArray>,
        problems: MutableList<PlayableWorldProblem>,
    ): ValidatedCharacterCreationProfile? {
        var validatedProfile: ValidatedCharacterCreationProfile? = null
        if (character.profilePath == null && character.prebuiltPlayerEntityId == null) {
            problems += problem(
                PlayableWorldProblemCode.MISSING_CHARACTER_ENTRY,
                "character",
                "A character profile or prebuilt player is required",
            )
            return null
        }
        if (character.profilePath != null && character.prebuiltPlayerEntityId != null) {
            problems += problem(
                PlayableWorldProblemCode.AMBIGUOUS_CHARACTER_ENTRY,
                "character",
                "Character entry must use either a profile or a prebuilt player, not both",
            )
        }
        character.prebuiltPlayerEntityId?.let { entityId ->
            if (definition.source.initialEntities.none { it.entityId == entityId }) {
                problems += problem(
                    PlayableWorldProblemCode.PREBUILT_PLAYER_UNKNOWN,
                    "character.prebuiltPlayerEntityId",
                    "Prebuilt player entity is not initialized: $entityId",
                )
            }
        }
        if (character.profilePath != null && character.playerEntityId == null) {
            problems += problem(
                PlayableWorldProblemCode.PLAYER_ENTITY_MISSING,
                "character.playerEntityId",
                "Profile-based character creation requires a stable player Entity ID",
            )
        }
        character.playerEntityId?.let { entityId ->
            if (definition.source.initialEntities.none { it.entityId == entityId }) {
                problems += problem(
                    PlayableWorldProblemCode.PLAYER_ENTITY_UNKNOWN,
                    "character.playerEntityId",
                    "Player Entity template is not initialized: $entityId",
                )
            }
        }
        character.profilePath?.let { path ->
            val bytes = entries[path]
            if (bytes == null) {
                problems += problem(
                    PlayableWorldProblemCode.CHARACTER_PROFILE_MISSING,
                    "character.profilePath",
                    "Character profile entry is missing: $path",
                )
                return@let
            }
            when (val decoded = CharacterCreationProfileCodec.decode(bytes.decodeToString())) {
                is CharacterCreationProfileDecodeResult.Failure -> problems += problem(
                    PlayableWorldProblemCode.CHARACTER_PROFILE_INVALID,
                    "character.profilePath",
                    decoded.message,
                )

                is CharacterCreationProfileDecodeResult.Success -> when (
                    val validated = CharacterCreationProfileValidator.validate(decoded.profile, definition)
                ) {
                    is CharacterProfileValidationResult.Valid -> validatedProfile = validated.profile
                    is CharacterProfileValidationResult.Invalid -> validated.problems.forEach { profileProblem ->
                        problems += problem(
                            PlayableWorldProblemCode.CHARACTER_PROFILE_INVALID,
                            "character.profilePath:${profileProblem.path}",
                            profileProblem.message,
                        )
                    }
                }
            }
        }
        return validatedProfile
    }

    private fun validateRequiredModules(
        contract: PlayableWorldContract,
        modules: RegisteredWorldModules,
        problems: MutableList<PlayableWorldProblem>,
    ) {
        contract.requiredModuleIds.forEachIndexed { index, moduleId ->
            if (modules.module(moduleId) == null) {
                problems += problem(
                    PlayableWorldProblemCode.REQUIRED_MODULE_MISSING,
                    "requiredModuleIds[$index]",
                    "Required module is not enabled: $moduleId",
                )
            }
        }
        duplicateIds(contract.requiredModuleIds, "requiredModuleIds", problems)
        contract.temporal?.let { temporal ->
            val required = buildList {
                add(WORLD_TIME_MODULE_ID)
                if (temporal.activities.isNotEmpty()) add(ACTIVITY_MODULE_ID)
                if (temporal.routes.isNotEmpty()) add(TRAVEL_MODULE_ID)
            }
            required.filterNot(contract.requiredModuleIds::contains).forEach { moduleId ->
                problems += problem(
                    PlayableWorldProblemCode.REQUIRED_MODULE_MISSING,
                    "requiredModuleIds",
                    "Temporal configuration must declare module: $moduleId",
                )
            }
        }
        contract.adventureState?.let { adventure ->
            val required = buildList {
                if (adventure.inventory != null) add(INVENTORY_MODULE_ID)
                if (adventure.conditions.isNotEmpty()) add(CONDITION_MODULE_ID)
                if (adventure.relationships.isNotEmpty()) add(RELATIONSHIP_MODULE_ID)
                if (adventure.quests.isNotEmpty()) add(QUEST_MODULE_ID)
                if (adventure.clocks.isNotEmpty()) add(PROGRESS_CLOCK_MODULE_ID)
            }
            required.filterNot(contract.requiredModuleIds::contains).forEach { moduleId ->
                problems += problem(
                    PlayableWorldProblemCode.REQUIRED_MODULE_MISSING,
                    "requiredModuleIds",
                    "Adventure-state configuration must declare module: $moduleId",
                )
            }
        }
    }

    private fun validateTemporal(
        contract: PlayableWorldContract,
        definition: ValidatedWorldDefinition,
        sceneIds: Set<DefinitionId>,
        problems: MutableList<PlayableWorldProblem>,
    ) {
        val temporal = contract.temporal ?: return
        when (val validation = TemporalAdventureDefinitionValidator.validate(temporal, definition, sceneIds)) {
            TemporalDefinitionValidationResult.Valid -> Unit
            is TemporalDefinitionValidationResult.Invalid -> validation.problems.forEach { temporalProblem ->
                problems += problem(
                    PlayableWorldProblemCode.TEMPORAL_INVALID,
                    "temporal.${temporalProblem.path}",
                    temporalProblem.message,
                )
            }
        }
    }

    private fun validateAdventureState(
        contract: PlayableWorldContract,
        definition: ValidatedWorldDefinition,
        problems: MutableList<PlayableWorldProblem>,
    ) {
        val adventure = contract.adventureState ?: return
        when (
            val validation = AdventureStateDefinitionValidator.validate(
                adventure,
                definition,
                contract.endings.map(PlayableEnding::id).toSet(),
            )
        ) {
            AdventureDefinitionValidationResult.Valid -> Unit
            is AdventureDefinitionValidationResult.Invalid -> validation.problems.forEach { adventureProblem ->
                problems += problem(
                    PlayableWorldProblemCode.ADVENTURE_STATE_INVALID,
                    "adventureState.${adventureProblem.path}",
                    adventureProblem.message,
                )
            }
        }
    }

    private fun validateLabels(
        contract: PlayableWorldContract,
        problems: MutableList<PlayableWorldProblem>,
    ) {
        contract.scenes.forEachIndexed { index, scene ->
            if (scene.label.isBlank()) problems += blankLabel("scenes[$index].label")
            if (scene.description?.let { it.isBlank() || it.length > 2_000 } == true) {
                problems += problem(
                    PlayableWorldProblemCode.BLANK_LABEL,
                    "scenes[$index].description",
                    "Scene description must contain 1 to 2000 characters",
                )
            }
        }
        contract.objectives.forEachIndexed { index, objective ->
            if (objective.label.isBlank()) problems += blankLabel("objectives[$index].label")
        }
        contract.endings.forEachIndexed { index, ending ->
            if (ending.label.isBlank()) problems += blankLabel("endings[$index].label")
            if (ending.summary?.let { it.isBlank() || it.length > 2_000 } == true) {
                problems += problem(
                    PlayableWorldProblemCode.BLANK_LABEL,
                    "endings[$index].summary",
                    "Ending summary must contain 1 to 2000 characters",
                )
            }
        }
    }

    private fun validateScenes(
        contract: PlayableWorldContract,
        definition: ValidatedWorldDefinition,
        scenes: Map<DefinitionId, PlayableScene>,
        actions: Map<DefinitionId, PlayableAction>,
        problems: MutableList<PlayableWorldProblem>,
    ) {
        contract.scenes.forEachIndexed { sceneIndex, scene ->
            duplicateIds(scene.actionIds, "scenes[$sceneIndex].actionIds", problems)
            if (scene.participantEntityIds.distinct().size != scene.participantEntityIds.size) {
                problems += problem(
                    PlayableWorldProblemCode.DUPLICATE_ID,
                    "scenes[$sceneIndex].participantEntityIds",
                    "Scene participant IDs must be unique",
                )
            }
            scene.participantEntityIds.forEachIndexed { participantIndex, entityId ->
                if (definition.source.initialEntities.none { it.entityId == entityId }) {
                    problems += problem(
                        PlayableWorldProblemCode.SCENE_PARTICIPANT_UNKNOWN,
                        "scenes[$sceneIndex].participantEntityIds[$participantIndex]",
                        "Scene participant Entity is not initialized: $entityId",
                    )
                }
            }
            scene.actionIds.forEachIndexed { actionIndex, actionId ->
                val action = actions[actionId]
                if (action == null) {
                    problems += problem(
                        PlayableWorldProblemCode.SCENE_ACTION_UNKNOWN,
                        "scenes[$sceneIndex].actionIds[$actionIndex]",
                        "Scene action is not declared: $actionId",
                    )
                } else if (action.sceneId != scene.id) {
                    problems += problem(
                        PlayableWorldProblemCode.ACTION_NOT_AVAILABLE_IN_SCENE,
                        "scenes[$sceneIndex].actionIds[$actionIndex]",
                        "Action $actionId belongs to ${action.sceneId}, not ${scene.id}",
                    )
                }
            }
        }
        contract.actions.forEachIndexed { index, action ->
            val scene = scenes[action.sceneId]
            if (scene == null) {
                problems += problem(
                    PlayableWorldProblemCode.ACTION_SCENE_UNKNOWN,
                    "actions[$index].sceneId",
                    "Action scene is not declared: ${action.sceneId}",
                )
            } else if (action.id !in scene.actionIds) {
                problems += problem(
                    PlayableWorldProblemCode.ACTION_NOT_AVAILABLE_IN_SCENE,
                    "actions[$index].id",
                    "Action ${action.id} is not exposed by scene ${scene.id}",
                )
            }
        }
    }

    private fun validateActions(
        contract: PlayableWorldContract,
        definition: ValidatedWorldDefinition,
        scenes: Map<DefinitionId, PlayableScene>,
        actions: Map<DefinitionId, PlayableAction>,
        objectives: Map<DefinitionId, PlayableObjective>,
        endings: Map<DefinitionId, PlayableEnding>,
        problems: MutableList<PlayableWorldProblem>,
    ) {
        contract.actions.forEachIndexed { actionIndex, action ->
            val path = "actions[$actionIndex]"
            val outcomeIds = action.resolutions.map(PlayableActionResolution::outcomeId)
            duplicateIds(outcomeIds, "$path.resolutions", problems)
            val check = action.checkProfileId?.let(definition::checkProfile)
            if (action.checkProfileId != null && check == null) {
                problems += problem(
                    PlayableWorldProblemCode.ACTION_CHECK_UNKNOWN,
                    "$path.checkProfileId",
                    "Action CheckProfile is not declared: ${action.checkProfileId}",
                )
            }
            val requiredQuest = action.requiredQuestId?.let { questId ->
                contract.adventureState?.quests?.firstOrNull { it.id == questId }
            }
            if ((action.requiredQuestId == null) != (action.requiredQuestStageId == null) ||
                (action.requiredQuestId != null && requiredQuest == null) ||
                (action.requiredQuestStageId != null && requiredQuest?.stages?.none { it.id == action.requiredQuestStageId } != false)
            ) {
                problems += problem(
                    PlayableWorldProblemCode.ACTION_REQUIREMENT_INVALID,
                    "$path.requiredQuestStageId",
                    "Action quest requirement must reference one configured quest stage",
                )
            }
            if (check != null) {
                val expected = check.outcomes.map { it.id }.toSet()
                val actual = outcomeIds.toSet()
                if (expected != actual) {
                    problems += problem(
                        PlayableWorldProblemCode.ACTION_OUTCOME_MISMATCH,
                        "$path.resolutions",
                        "Action outcomes must exactly match CheckProfile ${check.id}",
                    )
                }
            }
            if (action.resolutions.none { it.kind == PlayableOutcomeKind.FAILURE }) {
                problems += problem(
                    PlayableWorldProblemCode.ACTION_FAILURE_MISSING,
                    "$path.resolutions",
                    "Player-facing action must define an explicit failure progression",
                )
            }
            action.resolutions.forEachIndexed { resolutionIndex, resolution ->
                validateProgression(
                    resolution.progression,
                    "$path.resolutions[$resolutionIndex].progression",
                    scenes,
                    objectives,
                    endings,
                    problems,
                )
            }
        }
        if (actions.isEmpty()) {
            problems += problem(
                PlayableWorldProblemCode.DEAD_END_SCENE,
                "actions",
                "Playable world must declare at least one action",
            )
        }
    }

    private fun validateProgression(
        progression: PlayableProgression,
        path: String,
        scenes: Map<DefinitionId, PlayableScene>,
        objectives: Map<DefinitionId, PlayableObjective>,
        endings: Map<DefinitionId, PlayableEnding>,
        problems: MutableList<PlayableWorldProblem>,
    ) {
        val hasProgress = progression.nextSceneId != null || progression.objectiveIds.isNotEmpty() ||
            progression.endingId != null || progression.retryAllowed
        if (!hasProgress) {
            problems += problem(
                PlayableWorldProblemCode.PROGRESSION_EMPTY,
                path,
                "Outcome must advance, impose visible progress, allow retry, or end the Run",
            )
        }
        if (progression.nextSceneId != null && progression.endingId != null) {
            problems += problem(
                PlayableWorldProblemCode.PROGRESSION_AMBIGUOUS,
                path,
                "Outcome cannot enter another scene and a terminal ending at the same time",
            )
        }
        progression.nextSceneId?.let { sceneId ->
            if (sceneId !in scenes) problems += problem(
                PlayableWorldProblemCode.PROGRESSION_SCENE_UNKNOWN,
                "$path.nextSceneId",
                "Progression scene is not declared: $sceneId",
            )
        }
        duplicateIds(progression.objectiveIds, "$path.objectiveIds", problems)
        progression.objectiveIds.forEachIndexed { index, objectiveId ->
            if (objectiveId !in objectives) problems += problem(
                PlayableWorldProblemCode.PROGRESSION_OBJECTIVE_UNKNOWN,
                "$path.objectiveIds[$index]",
                "Progression objective is not declared: $objectiveId",
            )
        }
        progression.endingId?.let { endingId ->
            if (endingId !in endings) problems += problem(
                PlayableWorldProblemCode.PROGRESSION_ENDING_UNKNOWN,
                "$path.endingId",
                "Progression ending is not declared: $endingId",
            )
        }
    }

    private fun validatePresentation(
        contract: PlayableWorldContract,
        definition: ValidatedWorldDefinition,
        problems: MutableList<PlayableWorldProblem>,
    ) {
        val available = (definition.source.presentation.map { it.id } +
            definition.source.presentationChecks.map { it.id }).toSet()
        duplicateIds(contract.presentationIds, "presentationIds", problems)
        contract.presentationIds.forEachIndexed { index, presentationId ->
            if (presentationId !in available) problems += problem(
                PlayableWorldProblemCode.PRESENTATION_UNKNOWN,
                "presentationIds[$index]",
                "Presentation binding is not declared: $presentationId",
            )
        }
        contract.objectives.forEachIndexed { index, objective ->
            objective.presentationId?.let { presentationId ->
                if (presentationId !in available) problems += problem(
                    PlayableWorldProblemCode.PRESENTATION_UNKNOWN,
                    "objectives[$index].presentationId",
                    "Objective presentation is not declared: $presentationId",
                )
            }
        }
    }

    private fun validateBehaviors(
        contract: PlayableWorldContract,
        definition: ValidatedWorldDefinition,
        modules: RegisteredWorldModules,
        entries: Map<String, ByteArray>,
        problems: MutableList<PlayableWorldProblem>,
    ): List<ValidatedBehavior> {
        val validatedBehaviors = mutableListOf<ValidatedBehavior>()
        val allowedEventTypes = allowedEventTypes(modules)
        val commands = BehaviorCommandRegistry.forWorld(modules)
        duplicateIds(contract.behaviors.map(PlayableBehaviorReference::id), "behaviors", problems)
        contract.behaviors.forEachIndexed { index, reference ->
            val path = "behaviors[$index]"
            val bytes = entries[reference.path]
            if (bytes == null) {
                problems += problem(
                    PlayableWorldProblemCode.BEHAVIOR_MISSING,
                    "$path.path",
                    "Behavior entry is missing: ${reference.path}",
                )
                return@forEachIndexed
            }
            when (val decoded = BehaviorCodec.decode(bytes.decodeToString())) {
                is BehaviorDecodeResult.Failure -> problems += problem(
                    PlayableWorldProblemCode.BEHAVIOR_INVALID,
                    "$path.path",
                    decoded.message,
                )

                is BehaviorDecodeResult.Success -> {
                    if (decoded.behavior.id != reference.id) {
                        problems += problem(
                            PlayableWorldProblemCode.BEHAVIOR_ID_MISMATCH,
                            "$path.id",
                            "Behavior entry declares ${decoded.behavior.id}, expected ${reference.id}",
                        )
                    }
                    when (
                        val validated = BehaviorValidator.validate(
                            decoded.behavior,
                            definition,
                            emptyMap(),
                            commands,
                            allowedEventTypes,
                        )
                    ) {
                        is BehaviorValidationResult.Valid -> validatedBehaviors += validated.behavior
                        is BehaviorValidationResult.Invalid -> validated.problems.forEach { behaviorProblem ->
                            problems += problem(
                                PlayableWorldProblemCode.BEHAVIOR_INVALID,
                                "$path.path:${behaviorProblem.path}",
                                behaviorProblem.message,
                            )
                        }
                    }
                }
            }
        }
        return validatedBehaviors.sortedWith(
            compareByDescending<ValidatedBehavior> { it.source.policy.priority }.thenBy { it.source.id.value },
        )
    }

    private fun allowedEventTypes(modules: RegisteredWorldModules): Set<DefinitionId> =
        modules.capabilities(RuleCapabilityKind.EVENT).mapTo(mutableSetOf()) { it.id }.apply {
            addAll(CORE_BEHAVIOR_EVENT_TYPES)
        }

    private fun validateGraph(
        contract: PlayableWorldContract,
        scenes: Map<DefinitionId, PlayableScene>,
        actions: Map<DefinitionId, PlayableAction>,
        endings: Map<DefinitionId, PlayableEnding>,
        problems: MutableList<PlayableWorldProblem>,
    ) {
        if (contract.initialSceneId !in scenes) return
        val reachableScenes = linkedSetOf(contract.initialSceneId)
        val reachableEndings = linkedSetOf<DefinitionId>()
        val queue = ArrayDeque<DefinitionId>()
        queue.add(contract.initialSceneId)
        while (queue.isNotEmpty()) {
            val sceneId = queue.removeFirst()
            val scene = scenes.getValue(sceneId)
            if (scene.actionIds.isEmpty()) {
                problems += problem(
                    PlayableWorldProblemCode.DEAD_END_SCENE,
                    "scenes[${contract.scenes.indexOf(scene)}].actionIds",
                    "Reachable scene has no available action: $sceneId",
                )
            }
            scene.actionIds.mapNotNull(actions::get).flatMap(PlayableAction::resolutions).forEach { resolution ->
                resolution.progression.endingId?.let(reachableEndings::add)
                resolution.progression.nextSceneId?.let { next ->
                    if (next in scenes && reachableScenes.add(next)) queue.add(next)
                }
            }
        }
        endings.keys.filterNot(reachableEndings::contains).forEach { endingId ->
            problems += problem(
                PlayableWorldProblemCode.ENDING_UNREACHABLE,
                "endings[${contract.endings.indexOf(endings.getValue(endingId))}]",
                "Ending is unreachable from the initial scene: $endingId",
            )
        }
    }

    private fun <T> index(
        values: List<T>,
        id: (T) -> DefinitionId,
        path: String,
        problems: MutableList<PlayableWorldProblem>,
    ): Map<DefinitionId, T> {
        val result = linkedMapOf<DefinitionId, T>()
        values.forEachIndexed { index, value ->
            val definitionId = id(value)
            if (result.put(definitionId, value) != null) {
                problems += problem(
                    PlayableWorldProblemCode.DUPLICATE_ID,
                    "$path[$index].id",
                    "Duplicate contract ID: $definitionId",
                )
            }
        }
        return result
    }

    private fun duplicateIds(
        values: List<DefinitionId>,
        path: String,
        problems: MutableList<PlayableWorldProblem>,
    ) {
        val seen = mutableSetOf<DefinitionId>()
        values.forEachIndexed { index, value ->
            if (!seen.add(value)) problems += problem(
                PlayableWorldProblemCode.DUPLICATE_ID,
                "$path[$index]",
                "Duplicate reference: $value",
            )
        }
    }

    private fun blankLabel(path: String) = problem(
        PlayableWorldProblemCode.BLANK_LABEL,
        path,
        "Player-facing label must not be blank",
    )
}

private fun simulateRoute(
    route: PlayableRouteFixture,
    initialSceneId: DefinitionId,
    definition: ValidatedWorldDefinition,
    scenes: Map<DefinitionId, PlayableScene>,
    actions: Map<DefinitionId, PlayableAction>,
    objectives: Map<DefinitionId, PlayableObjective>,
    endings: Map<DefinitionId, PlayableEnding>,
): PlayableRouteSimulationResult {
    if (initialSceneId !in scenes) {
        return failure(PlayableWorldProblemCode.INITIAL_SCENE_UNKNOWN, "steps", "Route has no initial scene")
    }
    var currentSceneId = initialSceneId
    val completedObjectives = linkedSetOf<DefinitionId>()
    val trace = mutableListOf<PlayableRouteTraceEntry>()
    var endingId: DefinitionId? = null

    route.steps.forEachIndexed { stepIndex, step ->
        if (endingId != null) {
            return failure(
                PlayableWorldProblemCode.ROUTE_ACTION_UNAVAILABLE,
                "steps[$stepIndex]",
                "Route continues after terminal ending $endingId",
            )
        }
        val action = actions[step.actionId] ?: return failure(
            PlayableWorldProblemCode.ROUTE_ACTION_UNAVAILABLE,
            "steps[$stepIndex].actionId",
            "Route action is not declared: ${step.actionId}",
        )
        val scene = scenes[currentSceneId] ?: return failure(
            PlayableWorldProblemCode.ROUTE_ACTION_UNAVAILABLE,
            "steps[$stepIndex]",
            "Current route scene is not declared: $currentSceneId",
        )
        if (action.sceneId != currentSceneId || action.id !in scene.actionIds) {
            return failure(
                PlayableWorldProblemCode.ROUTE_ACTION_UNAVAILABLE,
                "steps[$stepIndex].actionId",
                "Action ${action.id} is unavailable in scene $currentSceneId",
            )
        }
        val outcomeId = resolveOutcome(step, action, definition, stepIndex)
        if (outcomeId is RouteOutcomeResolution.Failure) return PlayableRouteSimulationResult.Failure(outcomeId.problem)
        val resolvedOutcomeId = (outcomeId as RouteOutcomeResolution.Success).outcomeId
        val resolution = action.resolutions.firstOrNull { it.outcomeId == resolvedOutcomeId }
            ?: return failure(
                PlayableWorldProblemCode.ROUTE_OUTCOME_UNKNOWN,
                "steps[$stepIndex]",
                "Action ${action.id} has no resolution for $resolvedOutcomeId",
            )
        if (resolution.progression.objectiveIds.any { it !in objectives }) {
            return failure(
                PlayableWorldProblemCode.PROGRESSION_OBJECTIVE_UNKNOWN,
                "steps[$stepIndex]",
                "Route progression references an unknown objective",
            )
        }
        if (resolution.progression.endingId != null && resolution.progression.endingId !in endings) {
            return failure(
                PlayableWorldProblemCode.PROGRESSION_ENDING_UNKNOWN,
                "steps[$stepIndex]",
                "Route progression references an unknown ending",
            )
        }
        completedObjectives += resolution.progression.objectiveIds
        trace += PlayableRouteTraceEntry(
            stepIndex = stepIndex,
            actionId = action.id,
            outcomeId = resolvedOutcomeId,
            fromSceneId = currentSceneId,
            nextSceneId = resolution.progression.nextSceneId,
            objectiveIds = resolution.progression.objectiveIds,
            endingId = resolution.progression.endingId,
        )
        resolution.progression.nextSceneId?.let { currentSceneId = it }
        endingId = resolution.progression.endingId
    }

    val finalEnding = endingId ?: return failure(
        PlayableWorldProblemCode.ROUTE_ENDING_MISMATCH,
        "expectedEndingId",
        "Route does not reach a terminal ending",
    )
    if (finalEnding != route.expectedEndingId) {
        return failure(
            PlayableWorldProblemCode.ROUTE_ENDING_MISMATCH,
            "expectedEndingId",
            "Route reached $finalEnding, expected ${route.expectedEndingId}",
        )
    }
    return PlayableRouteSimulationResult.Complete(route.id, finalEnding, completedObjectives, trace)
}

private sealed interface RouteOutcomeResolution {
    data class Success(val outcomeId: DefinitionId) : RouteOutcomeResolution
    data class Failure(val problem: PlayableWorldProblem) : RouteOutcomeResolution
}

private fun resolveOutcome(
    step: PlayableRouteStep,
    action: PlayableAction,
    definition: ValidatedWorldDefinition,
    stepIndex: Int,
): RouteOutcomeResolution {
    val check = action.checkProfileId?.let(definition::checkProfile)
    if (check == null) {
        val selected = step.selectedOutcomeId ?: return RouteOutcomeResolution.Failure(
            problem(
                PlayableWorldProblemCode.ROUTE_OUTCOME_UNKNOWN,
                "steps[$stepIndex].selectedOutcomeId",
                "Unchecked action requires a selected outcome",
            ),
        )
        if (step.randomValues.isNotEmpty()) {
            return RouteOutcomeResolution.Failure(
                problem(
                    PlayableWorldProblemCode.ROUTE_RANDOM_RECORD_INVALID,
                    "steps[$stepIndex].randomValues",
                    "Unchecked action must not contain random values",
                ),
            )
        }
        return RouteOutcomeResolution.Success(selected)
    }

    val total = when (check.mode) {
        CheckResolutionMode.DETERMINISTIC -> {
            if (step.randomValues.isNotEmpty()) {
                return RouteOutcomeResolution.Failure(
                    problem(
                        PlayableWorldProblemCode.ROUTE_RANDOM_RECORD_INVALID,
                        "steps[$stepIndex].randomValues",
                        "Deterministic check must not contain random values",
                    ),
                )
            }
            check.baseValue
        }

        CheckResolutionMode.RANDOM -> {
            val dice = check.dice ?: return RouteOutcomeResolution.Failure(
                problem(
                    PlayableWorldProblemCode.ROUTE_RANDOM_RECORD_INVALID,
                    "steps[$stepIndex].randomValues",
                    "Random check has no valid dice expression",
                ),
            )
            if (step.randomValues.size != dice.count || step.randomValues.any { it !in 1..dice.sides }) {
                return RouteOutcomeResolution.Failure(
                    problem(
                        PlayableWorldProblemCode.ROUTE_RANDOM_RECORD_INVALID,
                        "steps[$stepIndex].randomValues",
                        "Route must record exactly ${dice.count} values in 1..${dice.sides}",
                    ),
                )
            }
            check.baseValue + step.randomValues.sum()
        }
    }
    val outcome = check.outcomes.sortedByDescending { it.minimumTotal }.firstOrNull { total >= it.minimumTotal }
        ?: return RouteOutcomeResolution.Failure(
            problem(
                PlayableWorldProblemCode.ROUTE_OUTCOME_UNKNOWN,
                "steps[$stepIndex]",
                "Recorded total $total resolves to no outcome in ${check.id}",
            ),
        )
    return RouteOutcomeResolution.Success(outcome.id)
}

private fun failure(
    code: PlayableWorldProblemCode,
    path: String,
    message: String,
): PlayableRouteSimulationResult.Failure = PlayableRouteSimulationResult.Failure(problem(code, path, message))

private fun problem(code: PlayableWorldProblemCode, path: String, message: String) =
    PlayableWorldProblem(code, path, message)
