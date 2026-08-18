package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.rules.module.api.RegisteredWorldModules
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.EntityId
import io.worldloom.world.RunId
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
    PERSISTENCE_REJECTED,
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

    data class Failed(val error: SessionError) : GameSessionUiState
}

sealed interface GameSessionAction {
    data class AdjustPresentedField(val presentationId: DefinitionId) : GameSessionAction

    data class ResolvePresentedCheck(val presentationId: DefinitionId) : GameSessionAction
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
}

data class SessionCommandContext(
    val runId: RunId,
    val modules: RegisteredWorldModules,
    val adjustmentTargets: List<SessionAdjustmentTarget>,
    val checkProfileIds: List<DefinitionId>,
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
    val state: StateFlow<GameSessionUiState>

    suspend fun load(worldId: DefinitionId): LoadResult

    suspend fun resume(worldId: DefinitionId, runId: RunId): LoadResult

    suspend fun perform(action: GameSessionAction): ActionResult

    suspend fun execute(
        command: GameSessionCommand,
        authorization: CommandAuthorization,
    ): ActionResult

    suspend fun commandContext(): SessionCommandContext?

    suspend fun replay(): SessionReplayResult
}
