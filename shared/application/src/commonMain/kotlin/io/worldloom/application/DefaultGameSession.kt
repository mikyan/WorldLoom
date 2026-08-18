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
import io.worldloom.world.EntityId
import io.worldloom.world.GameState
import io.worldloom.world.InMemoryEventStore
import io.worldloom.world.InitialGameStateFactory
import io.worldloom.world.ReplayResult
import io.worldloom.world.RunId
import io.worldloom.world.StateReducer
import io.worldloom.world.StateReductionResult
import io.worldloom.world.WorldEngine
import io.worldloom.world.EventReducer
import io.worldloom.world.EventReducerChain
import io.worldloom.world.DurableEventStore
import io.worldloom.world.DurableStoreLoadResult
import io.worldloom.world.DurableStoreWriteResult
import io.worldloom.rules.CheckCommandValidationResult
import io.worldloom.rules.CheckCommandValidator
import io.worldloom.rules.CheckEventReducer
import io.worldloom.rules.CheckResolutionResult
import io.worldloom.rules.RandomService
import io.worldloom.rules.ResolveCheckCommand
import io.worldloom.rules.RuleEngine
import io.worldloom.rules.SeededRandomService
import io.worldloom.rules.CheckResolvedEvent
import io.worldloom.rules.RandomRestoreResult
import io.worldloom.rules.RestorableRandomService
import io.worldloom.rules.module.api.RegisteredWorldModules
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
    private val randomServiceFactory: (RunId) -> RandomService = { SeededRandomService(0x574F524C444C4F4F) },
    private val snapshotInterval: Long = 10,
) : GameSession {
    init {
        require(snapshotInterval > 0) { "snapshotInterval must be positive" }
    }

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<GameSessionUiState>(GameSessionUiState.Idle)
    private var loaded: LoadedSession? = null
    private val reducer: EventReducer = EventReducerChain(listOf(StateReducer, CheckEventReducer))

    override val availableWorlds: List<WorldCatalogEntry> = catalog.entries
    override val state: StateFlow<GameSessionUiState> = mutableState.asStateFlow()

    override suspend fun load(worldId: DefinitionId): LoadResult = mutex.withLock {
        mutableState.value = GameSessionUiState.Loading(worldId)
        withContext(workerDispatcher) {
            val worldPackage = catalog.load(worldId)
                ?: return@withContext failLoad(
                    SessionError(SessionErrorCode.WORLD_NOT_FOUND, "World is not available: $worldId"),
                )
            val definition = when (val validation = WorldDefinitionValidator.validate(worldPackage.definition)) {
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
            if (eventStore is DurableEventStore) {
                when (val initialized = eventStore.initialize(initialState)) {
                    DurableStoreWriteResult.Success -> Unit
                    is DurableStoreWriteResult.Failure -> return@withContext failLoad(
                        SessionError(SessionErrorCode.PERSISTENCE_REJECTED, initialized.error.message),
                    )
                }
            }

            loaded = LoadedSession(
                definition,
                worldPackage.modules,
                initialState,
                initialState,
                randomServiceFactory(initialState.runId),
            )
            mutableState.value = GameSessionUiState.Ready(presentation)
            LoadResult.Success
        }
    }

    override suspend fun resume(
        worldId: DefinitionId,
        runId: RunId,
    ): LoadResult = mutex.withLock {
        mutableState.value = GameSessionUiState.Loading(worldId)
        withContext(workerDispatcher) {
            val durableStore = eventStore as? DurableEventStore
                ?: return@withContext failLoad(
                    SessionError(SessionErrorCode.PERSISTENCE_REJECTED, "The configured EventStore is not durable"),
                )
            val worldPackage = catalog.load(worldId)
                ?: return@withContext failLoad(
                    SessionError(SessionErrorCode.WORLD_NOT_FOUND, "World is not available: $worldId"),
                )
            val definition = when (val validation = WorldDefinitionValidator.validate(worldPackage.definition)) {
                is DefinitionValidationResult.Valid -> validation.definition
                is DefinitionValidationResult.Invalid -> {
                    val first = validation.problems.first()
                    return@withContext failLoad(
                        SessionError(SessionErrorCode.INVALID_WORLD_DEFINITION, first.message, first.path),
                    )
                }
            }
            val persisted = when (val loadedRun = durableStore.loadRun(runId)) {
                is DurableStoreLoadResult.Success -> loadedRun.run
                is DurableStoreLoadResult.Failure -> return@withContext failLoad(
                    SessionError(SessionErrorCode.PERSISTENCE_REJECTED, loadedRun.error.message),
                )
            }
            if (persisted.worldDefinitionId != definition.source.id) {
                return@withContext failLoad(
                    SessionError(SessionErrorCode.PERSISTENCE_REJECTED, "Save belongs to a different world"),
                )
            }
            val initialState = InitialGameStateFactory.create(definition, runId)
            val replayBase = persisted.snapshot ?: initialState
            val currentState = when (
                val replay = EventReplayer.replay(
                    replayBase,
                    definition,
                    persisted.eventsAfterSnapshot,
                    reducer,
                )
            ) {
                is ReplayResult.Success -> replay.state
                is ReplayResult.Failure -> return@withContext failLoad(
                    SessionError(SessionErrorCode.REPLAY_REJECTED, replay.error.message, replay.error.path),
                )
            }
            val allEvents = try {
                eventStore.read(runId)
            } catch (_: Exception) {
                return@withContext failLoad(
                    SessionError(SessionErrorCode.PERSISTENCE_REJECTED, "Stored events could not be decoded"),
                )
            }
            val randomService = randomServiceFactory(runId)
            val priorRandomRecords = allEvents.mapNotNull { event ->
                (event.payload as? CheckResolvedEvent)?.record?.randomRecord
            }
            if (priorRandomRecords.isNotEmpty()) {
                val restorable = randomService as? RestorableRandomService
                    ?: return@withContext failLoad(
                        SessionError(
                            SessionErrorCode.PERSISTENCE_REJECTED,
                            "Configured RandomService cannot restore an audited run",
                        ),
                    )
                when (val restored = restorable.restore(priorRandomRecords)) {
                    RandomRestoreResult.Success -> Unit
                    is RandomRestoreResult.Failure -> return@withContext failLoad(
                        SessionError(SessionErrorCode.PERSISTENCE_REJECTED, restored.message),
                    )
                }
            }
            val presentation = when (val mapping = PresentationMapper.map(definition, currentState, allEvents)) {
                is PresentationMappingResult.Success -> mapping.presentation
                is PresentationMappingResult.Failure -> return@withContext failLoad(
                    SessionError(SessionErrorCode.INVALID_WORLD_DEFINITION, mapping.message, mapping.path),
                )
            }
            idSource.synchronize(currentState.lastSequence)
            loaded = LoadedSession(
                definition,
                worldPackage.modules,
                initialState,
                currentState,
                randomService,
            )
            mutableState.value = GameSessionUiState.Ready(presentation)
            LoadResult.Success
        }
    }

    override suspend fun perform(action: GameSessionAction): ActionResult = mutex.withLock {
        withContext(workerDispatcher) {
            when (action) {
                is GameSessionAction.AdjustPresentedField -> adjustPresentedField(action.presentationId)
                is GameSessionAction.ResolvePresentedCheck -> resolvePresentedCheck(action.presentationId)
            }
        }
    }

    override suspend fun execute(
        command: GameSessionCommand,
        authorization: CommandAuthorization,
    ): ActionResult = mutex.withLock {
        withContext(workerDispatcher) {
            executeAuthoritative(command, authorization)
        }
    }

    override suspend fun commandContext(): SessionCommandContext? = mutex.withLock {
        loaded?.let { session ->
            SessionCommandContext(
                runId = session.currentState.runId,
                modules = session.modules,
                adjustmentTargets = session.definition.source.presentation.map { binding ->
                    SessionAdjustmentTarget(
                        entityId = EntityId(binding.entityId),
                        componentId = binding.componentId,
                        fieldId = binding.fieldId,
                    )
                },
                checkProfileIds = session.definition.source.presentationChecks.map { it.checkProfileId },
            )
        }
    }

    override suspend fun replay(): SessionReplayResult = mutex.withLock {
        withContext(workerDispatcher) {
            val session = loaded
                ?: return@withContext failReplay(
                    SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before replaying events"),
                )
            val events = eventStore.read(session.currentState.runId)
            val replayed = when (
                val replay = EventReplayer.replay(session.initialState, session.definition, events, reducer)
            ) {
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
        return executeAuthoritative(
            GameSessionCommand.AdjustNumericComponent(
                entityId = EntityId(binding.entityId),
                componentId = binding.componentId,
                fieldId = binding.fieldId,
                delta = binding.adjustmentStep,
            ),
            CommandAuthorization(actorId, setOf(CommandPermission.ADJUST_NUMERIC_COMPONENT)),
        )
    }

    private suspend fun executeAuthoritative(
        request: GameSessionCommand,
        authorization: CommandAuthorization,
    ): ActionResult = when (request) {
        is GameSessionCommand.AdjustNumericComponent -> executeAdjustment(request, authorization)
        is GameSessionCommand.ResolveCheck -> executeCheck(request, authorization)
    }

    private suspend fun executeAdjustment(
        request: GameSessionCommand.AdjustNumericComponent,
        authorization: CommandAuthorization,
    ): ActionResult {
        val session = loaded
            ?: return failAction(SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before acting"))
        val command = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = idSource.nextCommandId(),
            runId = session.currentState.runId,
            actorId = authorization.actorId,
            expectedSequence = session.currentState.lastSequence,
            payload = AdjustNumericComponentCommand(
                entityId = request.entityId,
                componentId = request.componentId,
                fieldId = request.fieldId,
                delta = request.delta,
            ),
        )
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
        val notice = writeSnapshotIfDue(session)
        publishReady(session, eventStore.read(session.currentState.runId), notice)
        return ActionResult.Success
    }

    private suspend fun resolvePresentedCheck(presentationId: DefinitionId): ActionResult {
        val session = loaded
            ?: return failAction(SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before acting"))
        val binding = session.definition.source.presentationChecks.firstOrNull { it.id == presentationId }
            ?: return failAction(
                SessionError(
                    SessionErrorCode.PRESENTATION_BINDING_NOT_FOUND,
                    "Presentation check is not defined: $presentationId",
                ),
            )
        val actorId = ActorId("system.development")
        return executeAuthoritative(
            GameSessionCommand.ResolveCheck(binding.checkProfileId),
            CommandAuthorization(actorId, setOf(CommandPermission.RESOLVE_CHECK)),
        )
    }

    private suspend fun executeCheck(
        request: GameSessionCommand.ResolveCheck,
        authorization: CommandAuthorization,
    ): ActionResult {
        val session = loaded
            ?: return failAction(SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before acting"))
        val command = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = idSource.nextCommandId(),
            runId = session.currentState.runId,
            actorId = authorization.actorId,
            expectedSequence = session.currentState.lastSequence,
            payload = ResolveCheckCommand(request.profileId, request.modifier),
        )
        val validated = when (
            val validation = CheckCommandValidator.validate(
                session.currentState,
                session.definition,
                session.modules,
                authorization,
                command,
            )
        ) {
            is CheckCommandValidationResult.Valid -> validation.command
            is CheckCommandValidationResult.Invalid -> return failAction(
                SessionError(SessionErrorCode.CHECK_REJECTED, validation.error.message, validation.error.path),
            )
        }
        val event = when (
            val resolution = RuleEngine.resolve(
                command = validated,
                eventId = idSource.nextEventId(),
                checkId = idSource.nextCheckId(),
                randomRecordId = idSource.nextRandomRecordId(),
                randomService = session.randomService,
            )
        ) {
            is CheckResolutionResult.Success -> resolution.event
            is CheckResolutionResult.Failure -> return failAction(
                SessionError(SessionErrorCode.CHECK_REJECTED, resolution.error.message),
            )
        }
        val candidateState = when (
            val reduction = reducer.reduce(session.currentState, session.definition, event)
        ) {
            is StateReductionResult.Success -> reduction.state
            is StateReductionResult.Failure -> return failAction(
                SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
            )
        }
        when (val append = eventStore.append(session.currentState.runId, session.currentState.lastSequence, listOf(event))) {
            is EventAppendResult.Success -> Unit
            is EventAppendResult.Failure -> return failAction(
                SessionError(SessionErrorCode.EVENT_STORE_REJECTED, append.error.message),
            )
        }
        session.currentState = candidateState
        val notice = writeSnapshotIfDue(session)
        publishReady(session, eventStore.read(session.currentState.runId), notice)
        return ActionResult.Success
    }

    private fun reduceAll(
        initial: GameState,
        definition: ValidatedWorldDefinition,
        events: List<EventEnvelope>,
    ): StateReductionResult {
        var state = initial
        for (event in events) {
            when (val result = reducer.reduce(state, definition, event)) {
                is StateReductionResult.Success -> state = result.state
                is StateReductionResult.Failure -> return result
            }
        }
        return StateReductionResult.Success(state)
    }

    private fun publishReady(
        session: LoadedSession,
        events: List<EventEnvelope>,
        notice: SessionError? = null,
    ) {
        when (val mapping = PresentationMapper.map(session.definition, session.currentState, events)) {
            is PresentationMappingResult.Success -> mutableState.value = GameSessionUiState.Ready(mapping.presentation, notice)
            is PresentationMappingResult.Failure -> mutableState.value = GameSessionUiState.Failed(
                SessionError(SessionErrorCode.INVALID_WORLD_DEFINITION, mapping.message, mapping.path),
            )
        }
    }

    private suspend fun writeSnapshotIfDue(session: LoadedSession): SessionError? {
        val durableStore = eventStore as? DurableEventStore ?: return null
        if (session.currentState.lastSequence % snapshotInterval != 0L) return null
        return when (val result = durableStore.writeSnapshot(session.currentState)) {
            DurableStoreWriteResult.Success -> null
            is DurableStoreWriteResult.Failure -> SessionError(
                SessionErrorCode.PERSISTENCE_REJECTED,
                result.error.message,
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
        val modules: RegisteredWorldModules,
        val initialState: GameState,
        var currentState: GameState,
        val randomService: RandomService,
    )
}
