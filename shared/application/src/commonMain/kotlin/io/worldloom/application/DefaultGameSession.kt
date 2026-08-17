package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.definition.WorldDefinitionValidator
import io.worldloom.world.ActorId
import io.worldloom.world.AdjustNumericComponentCommand
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandEnvelope
import io.worldloom.world.CommandPermission
import io.worldloom.world.CommandValidationResult
import io.worldloom.world.CommandValidator
import io.worldloom.world.CURRENT_COMMAND_SCHEMA_VERSION
import io.worldloom.world.EventAppendResult
import io.worldloom.world.EventEnvelope
import io.worldloom.world.EventReplayer
import io.worldloom.world.EventStore
import io.worldloom.world.GameState
import io.worldloom.world.InMemoryEventStore
import io.worldloom.world.InitialGameStateFactory
import io.worldloom.world.ReplayResult
import io.worldloom.world.StateReducer
import io.worldloom.world.StateReductionResult
import io.worldloom.world.WorldEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DefaultGameSession(
    private val catalog: WorldCatalog,
    private val eventStore: EventStore = InMemoryEventStore(),
    private val idSource: SessionIdSource = SequentialSessionIdSource(),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GameSession {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<GameSessionUiState>(GameSessionUiState.Idle)
    private var loaded: LoadedSession? = null

    override val availableWorlds: List<WorldCatalogEntry> = catalog.entries
    override val state: StateFlow<GameSessionUiState> = mutableState.asStateFlow()

    override suspend fun load(worldId: DefinitionId): LoadResult = mutex.withLock {
        mutableState.value = GameSessionUiState.Loading(worldId)
        withContext(workerDispatcher) {
            val source = catalog.load(worldId)
                ?: return@withContext failLoad(
                    SessionError(SessionErrorCode.WORLD_NOT_FOUND, "World is not available: $worldId"),
                )
            val definition = when (val validation = WorldDefinitionValidator.validate(source)) {
                is DefinitionValidationResult.Valid -> validation.definition
                is DefinitionValidationResult.Invalid -> {
                    val first = validation.problems.first()
                    return@withContext failLoad(
                        SessionError(
                            code = SessionErrorCode.INVALID_WORLD_DEFINITION,
                            message = first.message,
                            path = first.path,
                        ),
                    )
                }
            }
            val initialState = InitialGameStateFactory.create(definition, idSource.nextRunId())
            val presentation = when (val mapping = PresentationMapper.map(definition, initialState, emptyList())) {
                is PresentationMappingResult.Success -> mapping.presentation
                is PresentationMappingResult.Failure -> {
                    return@withContext failLoad(
                        SessionError(
                            SessionErrorCode.INVALID_WORLD_DEFINITION,
                            mapping.message,
                            mapping.path,
                        ),
                    )
                }
            }

            loaded = LoadedSession(definition, initialState, initialState)
            mutableState.value = GameSessionUiState.Ready(presentation)
            LoadResult.Success
        }
    }

    override suspend fun perform(action: GameSessionAction): ActionResult = mutex.withLock {
        withContext(workerDispatcher) {
            when (action) {
                is GameSessionAction.AdjustPresentedField -> adjustPresentedField(action.presentationId)
            }
        }
    }

    override suspend fun replay(): SessionReplayResult = mutex.withLock {
        withContext(workerDispatcher) {
            val session = loaded
                ?: return@withContext failReplay(
                    SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before replaying events"),
                )
            val events = eventStore.read(session.currentState.runId)
            val replayed = when (val replay = EventReplayer.replay(session.initialState, session.definition, events)) {
                is ReplayResult.Success -> replay.state
                is ReplayResult.Failure -> {
                    return@withContext failReplay(
                        SessionError(
                            SessionErrorCode.REPLAY_REJECTED,
                            replay.error.message,
                            replay.error.path,
                        ),
                    )
                }
            }
            session.currentState = replayed
            publishReady(session, events)
            SessionReplayResult.Success
        }
    }

    private suspend fun adjustPresentedField(presentationId: DefinitionId): ActionResult {
        val session = loaded
            ?: return failAction(SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before acting"))
        val binding = session.definition.source.presentation.firstOrNull { it.id == presentationId }
            ?: return failAction(
                SessionError(
                    SessionErrorCode.PRESENTATION_BINDING_NOT_FOUND,
                    "Presentation action is not defined: $presentationId",
                ),
            )
        val actorId = ActorId("system.development")
        val command = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = idSource.nextCommandId(),
            runId = session.currentState.runId,
            actorId = actorId,
            expectedSequence = session.currentState.lastSequence,
            payload = AdjustNumericComponentCommand(
                entityId = io.worldloom.world.EntityId(binding.entityId),
                componentId = binding.componentId,
                fieldId = binding.fieldId,
                delta = binding.adjustmentStep,
            ),
        )
        val authorization = CommandAuthorization(actorId, setOf(CommandPermission.ADJUST_NUMERIC_COMPONENT))
        val validated = when (
            val validation = CommandValidator.validate(
                session.currentState,
                session.definition,
                authorization,
                command,
            )
        ) {
            is CommandValidationResult.Valid -> validation.command
            is CommandValidationResult.Invalid -> {
                return failAction(
                    SessionError(
                        SessionErrorCode.COMMAND_REJECTED,
                        validation.error.message,
                        validation.error.path,
                    ),
                )
            }
        }
        val events = WorldEngine.handle(validated, idSource.nextEventId())
        val candidateState = when (val reduction = reduceAll(session.currentState, session.definition, events)) {
            is StateReductionResult.Success -> reduction.state
            is StateReductionResult.Failure -> {
                return failAction(
                    SessionError(
                        SessionErrorCode.EVENT_REJECTED,
                        reduction.error.message,
                        reduction.error.path,
                    ),
                )
            }
        }
        when (val append = eventStore.append(session.currentState.runId, session.currentState.lastSequence, events)) {
            is EventAppendResult.Success -> Unit
            is EventAppendResult.Failure -> {
                return failAction(
                    SessionError(SessionErrorCode.EVENT_STORE_REJECTED, append.error.message),
                )
            }
        }

        session.currentState = candidateState
        publishReady(session, eventStore.read(session.currentState.runId))
        return ActionResult.Success
    }

    private fun reduceAll(
        initial: GameState,
        definition: ValidatedWorldDefinition,
        events: List<EventEnvelope>,
    ): StateReductionResult {
        var state = initial
        for (event in events) {
            when (val result = StateReducer.reduce(state, definition, event)) {
                is StateReductionResult.Success -> state = result.state
                is StateReductionResult.Failure -> return result
            }
        }
        return StateReductionResult.Success(state)
    }

    private fun publishReady(
        session: LoadedSession,
        events: List<EventEnvelope>,
    ) {
        when (val mapping = PresentationMapper.map(session.definition, session.currentState, events)) {
            is PresentationMappingResult.Success -> mutableState.value = GameSessionUiState.Ready(mapping.presentation)
            is PresentationMappingResult.Failure -> mutableState.value = GameSessionUiState.Failed(
                SessionError(SessionErrorCode.INVALID_WORLD_DEFINITION, mapping.message, mapping.path),
            )
        }
    }

    private fun failLoad(error: SessionError): LoadResult.Failure {
        loaded = null
        mutableState.value = GameSessionUiState.Failed(error)
        return LoadResult.Failure(error)
    }

    private fun failAction(error: SessionError): ActionResult.Failure {
        val current = mutableState.value
        if (current is GameSessionUiState.Ready) {
            mutableState.value = current.copy(notice = error)
        } else {
            mutableState.value = GameSessionUiState.Failed(error)
        }
        return ActionResult.Failure(error)
    }

    private fun failReplay(error: SessionError): SessionReplayResult.Failure {
        val current = mutableState.value
        if (current is GameSessionUiState.Ready) {
            mutableState.value = current.copy(notice = error)
        } else {
            mutableState.value = GameSessionUiState.Failed(error)
        }
        return SessionReplayResult.Failure(error)
    }

    private data class LoadedSession(
        val definition: ValidatedWorldDefinition,
        val initialState: GameState,
        var currentState: GameState,
    )
}
