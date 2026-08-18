package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.rules.module.api.RegisteredWorldModules
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.ActorId
import io.worldloom.world.EntityId
import io.worldloom.world.NpcPublicActionKind
import io.worldloom.world.RunId
import io.worldloom.world.RunLifecycle
import io.worldloom.rules.AdventureStateDefinition
import io.worldloom.rules.AdventureStatePresentation
import io.worldloom.rules.InventoryOperation
import io.worldloom.rules.QuestStatus
import kotlinx.coroutines.flow.StateFlow

data class PresentedField(
    val presentationId: DefinitionId,
    val label: String,
    val value: Long,
    val adjustmentStep: Long,
)

data class PresentedEvent(
    val sequence: Long,
    val summary: String,
    val eventId: String? = null,
    val eventType: String = "worldloom.event.unknown",
    val causationId: String? = null,
    val correlationId: String? = null,
    val randomRecord: PresentedRandomRecord? = null,
)

data class PresentedRandomRecord(
    val recordId: String,
    val results: List<Int>,
    val total: Long,
    val outcomeId: DefinitionId,
)

data class PresentedCheck(
    val presentationId: DefinitionId,
    val label: String,
)

data class GamePresentation(
    val worldId: DefinitionId,
    val title: String,
    val lastSequence: Long,
    val fields: List<PresentedField>,
    val checks: List<PresentedCheck>,
    val timeline: List<PresentedEvent>,
    val scene: PresentedScene? = null,
    val completedObjectiveIds: Set<DefinitionId> = emptySet(),
    val endingId: DefinitionId? = null,
    val worldTimeMinutes: Long? = null,
    val activities: List<PresentedActivity> = emptyList(),
    val travelRoutes: List<PresentedTravelRoute> = emptyList(),
    val adventureState: AdventureStatePresentation? = null,
    val endingSummary: String? = null,
    val timelineTotalCount: Int = timeline.size,
    val timelineTruncated: Boolean = false,
)

data class TimelinePage(
    val events: List<PresentedEvent>,
    val totalCount: Int,
    val hasEarlier: Boolean,
)

sealed interface TimelinePageResult {
    data class Success(val page: TimelinePage) : TimelinePageResult

    data class Failure(val message: String) : TimelinePageResult
}

data class PublicReplayDocument(
    val schema: String = "worldloom.public-replay/v1",
    val runId: RunId,
    val worldId: DefinitionId,
    val lastSequence: Long,
    val events: List<PresentedEvent>,
)

sealed interface PublicReplayResult {
    data class Verified(val document: PublicReplayDocument) : PublicReplayResult

    data class Failure(val message: String) : PublicReplayResult
}

interface ReplayInspector {
    suspend fun timelinePage(beforeSequenceExclusive: Long? = null, limit: Int = 100): TimelinePageResult

    suspend fun exportVerifiedPublicReplay(): PublicReplayResult
}

data class PresentedScene(
    val id: DefinitionId,
    val label: String,
    val participantIds: List<EntityId>,
    val actions: List<PresentedAction>,
    val description: String? = null,
    val addressableNpcs: List<PresentedNpc> = emptyList(),
)

data class PresentedNpc(val id: DefinitionId, val entityId: EntityId, val displayName: String)

data class PresentedAction(val id: DefinitionId, val label: String)

data class PresentedActivity(val id: DefinitionId, val label: String, val durationMinutes: Long)

data class PresentedTravelRoute(
    val id: DefinitionId,
    val label: String,
    val destinationSceneId: DefinitionId,
    val durationMinutes: Long,
)

enum class SessionErrorCode {
    WORLD_NOT_FOUND,
    INVALID_WORLD_DEFINITION,
    SESSION_NOT_LOADED,
    PRESENTATION_BINDING_NOT_FOUND,
    COMMAND_REJECTED,
    EVENT_REJECTED,
    EVENT_STORE_REJECTED,
    REPLAY_REJECTED,
    CHECK_REJECTED,
    CHARACTER_CREATION_REJECTED,
    PERSISTENCE_REJECTED,
    BEHAVIOR_PAUSED,
}

data class SessionError(
    val code: SessionErrorCode,
    val message: String,
    val path: String? = null,
)

sealed interface GameSessionUiState {
    data object Idle : GameSessionUiState

    data class Loading(val worldId: DefinitionId) : GameSessionUiState

    data class Ready(
        val presentation: GamePresentation,
        val notice: SessionError? = null,
    ) : GameSessionUiState

    data class CharacterCreation(val presentation: CharacterCreationPresentation) : GameSessionUiState

    data class Ended(
        val worldId: DefinitionId,
        val lifecycle: RunLifecycle,
        val presentation: GamePresentation,
        val notice: SessionError? = null,
    ) : GameSessionUiState

    data class Failed(val error: SessionError) : GameSessionUiState
}

sealed interface GameSessionAction {
    data class AdjustPresentedField(val presentationId: DefinitionId) : GameSessionAction

    data class ResolvePresentedCheck(val presentationId: DefinitionId) : GameSessionAction

    data class PerformAvailableAction(val actionId: DefinitionId) : GameSessionAction

    data class AdvanceWorldTime(val deltaMinutes: Long) : GameSessionAction

    data class PerformActivity(val activityId: DefinitionId) : GameSessionAction

    data class Travel(val routeId: DefinitionId) : GameSessionAction

}

/** Typed application boundary used by UI and Agent tools before commands enter the world engine. */
sealed interface GameSessionCommand {
    data class AdjustNumericComponent(
        val entityId: EntityId,
        val componentId: DefinitionId,
        val fieldId: DefinitionId,
        val delta: Long,
    ) : GameSessionCommand

    data class ResolveCheck(
        val profileId: DefinitionId,
        val modifier: Long = 0,
    ) : GameSessionCommand

    data class PerformAvailableAction(
        val actionId: DefinitionId,
        val selectedOutcomeId: DefinitionId? = null,
    ) : GameSessionCommand

    data class AdvanceWorldTime(
        val deltaMinutes: Long,
        val reasonId: DefinitionId = DefinitionId("worldloom.reason.wait"),
    ) : GameSessionCommand

    data class PerformActivity(
        val activityId: DefinitionId,
        val selectedOutcomeId: DefinitionId? = null,
        val interrupted: Boolean = false,
    ) : GameSessionCommand

    data class Travel(
        val routeId: DefinitionId,
        val selectedOutcomeId: DefinitionId? = null,
    ) : GameSessionCommand

    data class ChangeInventory(
        val itemId: DefinitionId,
        val quantity: Long,
        val operation: InventoryOperation,
    ) : GameSessionCommand

    data class UpdateCondition(
        val conditionId: DefinitionId,
        val stackDelta: Long = 0,
        val elapsedMinutes: Long = 0,
    ) : GameSessionCommand

    data class AdjustRelationship(val relationshipId: DefinitionId, val delta: Long) : GameSessionCommand

    data class AdvanceQuest(
        val questId: DefinitionId,
        val stageId: DefinitionId,
        val status: QuestStatus,
    ) : GameSessionCommand

    data class AdvanceProgressClock(val clockId: DefinitionId, val delta: Long) : GameSessionCommand

    data class PublishNpcAction(
        val kind: NpcPublicActionKind,
        val actionId: DefinitionId? = null,
        val content: String,
    ) : GameSessionCommand

    data class AddressNpc(
        val npcId: DefinitionId,
        val content: String,
        val idempotencyKey: String,
    ) : GameSessionCommand
}

data class SessionCommandContext(
    val runId: RunId,
    val modules: RegisteredWorldModules,
    val adjustmentTargets: List<SessionAdjustmentTarget>,
    val checkProfileIds: List<DefinitionId>,
    val lastSequence: Long = 0,
    val currentSceneId: DefinitionId? = null,
    val currentSceneParticipantIds: Set<EntityId> = emptySet(),
    val availableActions: List<SessionAvailableAction> = emptyList(),
    val worldTimeMinutes: Long? = null,
    val availableActivities: List<SessionAvailableActivity> = emptyList(),
    val availableTravelRoutes: List<SessionAvailableTravelRoute> = emptyList(),
    val adventureStateDefinition: AdventureStateDefinition? = null,
    val npcProfiles: List<SessionNpcProfile> = emptyList(),
)

data class SessionNpcProfile(
    val id: DefinitionId,
    val entityId: EntityId,
    val actorId: ActorId,
    val displayName: String,
    val identityPrompt: String,
    val wakeEventTypes: Set<DefinitionId>,
    val visiblePresentationIds: Set<DefinitionId>,
    val goals: List<String>,
    val privateKnowledge: List<String>,
    val canSpeak: Boolean,
    val publicActionIds: Set<DefinitionId>,
)

data class SessionCommittedEvent(
    val eventId: String,
    val sequence: Long,
    val eventType: DefinitionId,
    val sceneId: DefinitionId?,
    val participantIds: Set<EntityId>,
    val targetNpcId: DefinitionId? = null,
    val publicInput: String? = null,
)

/** Explicitly public NPC Tool output; private model text and memory are never represented here. */
data class SessionNpcPublicAction(
    val eventId: String,
    val sequence: Long,
    val entityId: EntityId,
    val displayName: String,
    val kind: NpcPublicActionKind,
    val actionId: DefinitionId?,
    val content: String,
)

data class SessionAvailableAction(
    val actionId: DefinitionId,
    val label: String,
    val outcomeIds: List<DefinitionId>,
    val requiresCheck: Boolean,
)

data class SessionAvailableActivity(
    val activityId: DefinitionId,
    val label: String,
    val durationMinutes: Long,
    val outcomeIds: List<DefinitionId>,
    val requiresCheck: Boolean,
    val interruptionOutcomeId: DefinitionId? = null,
)

data class SessionAvailableTravelRoute(
    val routeId: DefinitionId,
    val label: String,
    val destinationSceneId: DefinitionId,
    val durationMinutes: Long,
    val outcomeIds: List<DefinitionId>,
    val requiresCheck: Boolean,
)

data class SessionAdjustmentTarget(
    val entityId: EntityId,
    val componentId: DefinitionId,
    val fieldId: DefinitionId,
)

sealed interface LoadResult {
    data object Success : LoadResult

    data class Failure(val error: SessionError) : LoadResult
}

sealed interface ActionResult {
    data object Success : ActionResult

    data class Failure(val error: SessionError) : ActionResult
}

sealed interface SessionReplayResult {
    data object Success : SessionReplayResult

    data class Failure(val error: SessionError) : SessionReplayResult
}

interface GameSession {
    val availableWorlds: List<WorldCatalogEntry>

    val currentRunId: RunId?
        get() = null
    val state: StateFlow<GameSessionUiState>

    suspend fun load(worldId: DefinitionId): LoadResult

    suspend fun resume(worldId: DefinitionId, runId: RunId): LoadResult

    suspend fun updateCharacter(request: io.worldloom.content.schema.CharacterCreationRequest): ActionResult =
        ActionResult.Failure(SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Character creation is unavailable"))

    suspend fun confirmCharacter(): ActionResult =
        ActionResult.Failure(SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Character creation is unavailable"))

    suspend fun abandonCharacter(): ActionResult =
        ActionResult.Failure(SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Character creation is unavailable"))

    suspend fun perform(action: GameSessionAction): ActionResult

    suspend fun execute(
        command: GameSessionCommand,
        authorization: CommandAuthorization,
    ): ActionResult

    suspend fun commandContext(): SessionCommandContext?

    suspend fun committedEvents(afterSequence: Long, throughSequence: Long = Long.MAX_VALUE): List<SessionCommittedEvent> =
        emptyList()

    suspend fun publicNpcActions(afterSequence: Long, throughSequence: Long = Long.MAX_VALUE): List<SessionNpcPublicAction> =
        emptyList()

    suspend fun replay(): SessionReplayResult
}
