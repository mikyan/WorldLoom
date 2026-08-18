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
import io.worldloom.world.ChangeRunLifecycleCommand
import io.worldloom.world.CreatePlayerCharacterCommand
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
import io.worldloom.world.RunLifecycle
import io.worldloom.world.RunLifecycleChangedEvent
import io.worldloom.world.PlayerEntityCreatedEvent
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
    private val characterDraftStore: CharacterCreationDraftStore = InMemoryCharacterCreationDraftStore(),
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
            val runId = idSource.nextRunId()
            val playable = worldPackage.playableContract
            val characterProfile = playable?.characterProfile
            val configuredPlayerId = playable?.source?.character?.playerEntityId
            val initialState = if (characterProfile != null && configuredPlayerId != null) {
                InitialGameStateFactory.createForCharacterCreation(definition, runId, EntityId(configuredPlayerId))
            } else {
                InitialGameStateFactory.create(definition, runId)
            }
            if (eventStore is DurableEventStore) {
                when (val initialized = eventStore.initialize(initialState)) {
                    DurableStoreWriteResult.Success -> Unit
                    is DurableStoreWriteResult.Failure -> return@withContext failLoad(
                        SessionError(SessionErrorCode.PERSISTENCE_REJECTED, initialized.error.message),
                    )
                }
            }
            if (characterProfile != null && configuredPlayerId != null) {
                val coordinator = CharacterCreationCoordinator(
                    worldId = definition.source.id,
                    profile = characterProfile,
                    playerEntityId = configuredPlayerId,
                    initialSceneId = playable.source.initialSceneId,
                )
                val draft = coordinator.createDraft(runId, idSource.nextCommandId())
                characterDraftStore.save(draft)
                val actorId = ActorId("system.application")
                val lifecycleCommand = CommandEnvelope(
                    schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
                    commandId = idSource.nextCommandId(),
                    runId = runId,
                    actorId = actorId,
                    expectedSequence = initialState.lastSequence,
                    payload = ChangeRunLifecycleCommand(lifecycle = RunLifecycle.CHARACTER_CREATION),
                )
                val validated = when (
                    val validation = CommandValidator.validate(
                        initialState,
                        definition,
                        CommandAuthorization(actorId, setOf(CommandPermission.MANAGE_RUN_LIFECYCLE)),
                        lifecycleCommand,
                    )
                ) {
                    is CommandValidationResult.Valid -> validation.command
                    is CommandValidationResult.Invalid -> return@withContext failLoad(
                        SessionError(SessionErrorCode.COMMAND_REJECTED, validation.error.message, validation.error.path),
                    )
                }
                val lifecycleEvents = WorldEngine.handle(validated, idSource.nextEventId())
                val creatingState = when (val reduction = reduceAll(initialState, definition, lifecycleEvents)) {
                    is StateReductionResult.Success -> reduction.state
                    is StateReductionResult.Failure -> return@withContext failLoad(
                        SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
                    )
                }
                when (val append = eventStore.append(runId, initialState.lastSequence, lifecycleEvents)) {
                    is EventAppendResult.Success -> Unit
                    is EventAppendResult.Failure -> return@withContext failLoad(
                        SessionError(SessionErrorCode.EVENT_STORE_REJECTED, append.error.message),
                    )
                }
                loaded = LoadedSession(
                    definition,
                    worldPackage.modules,
                    initialState,
                    creatingState,
                    randomServiceFactory(runId),
                    coordinator,
                    draft,
                )
                mutableState.value = GameSessionUiState.CharacterCreation(coordinator.present(draft))
                return@withContext LoadResult.Success
            }
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
            val allEvents = try {
                eventStore.read(runId)
            } catch (_: Exception) {
                return@withContext failLoad(
                    SessionError(SessionErrorCode.PERSISTENCE_REJECTED, "Stored events could not be decoded"),
                )
            }
            val playable = worldPackage.playableContract
            val configuredPlayerId = playable?.source?.character?.playerEntityId
            val persistedSnapshot = persisted.snapshot
            val usesLifecycle = (persistedSnapshot != null && persistedSnapshot.lifecycle != RunLifecycle.ACTIVE) ||
                allEvents.any { it.payload is RunLifecycleChangedEvent }
            val initialState = if (usesLifecycle && configuredPlayerId != null) {
                InitialGameStateFactory.createForCharacterCreation(definition, runId, EntityId(configuredPlayerId))
            } else {
                InitialGameStateFactory.create(definition, runId)
            }
            val replayBase = persistedSnapshot ?: initialState
            var currentState = when (
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
            if (currentState.lifecycle in setOf(RunLifecycle.CREATED, RunLifecycle.CHARACTER_CREATION)) {
                val profile = playable?.characterProfile ?: return@withContext failLoad(
                    SessionError(
                        SessionErrorCode.PERSISTENCE_REJECTED,
                        "In-progress character creation has no pinned profile",
                    ),
                )
                val playerId = configuredPlayerId ?: return@withContext failLoad(
                    SessionError(SessionErrorCode.PERSISTENCE_REJECTED, "In-progress character creation has no player ID"),
                )
                val coordinator = CharacterCreationCoordinator(
                    definition.source.id,
                    profile,
                    playerId,
                    playable.source.initialSceneId,
                )
                val draft = try {
                    characterDraftStore.load(runId)
                } catch (error: Exception) {
                    return@withContext failLoad(
                        SessionError(
                            SessionErrorCode.PERSISTENCE_REJECTED,
                            error.message ?: "Stored character draft is invalid",
                        ),
                    )
                } ?: coordinator.createDraft(runId, idSource.nextCommandId()).also { characterDraftStore.save(it) }
                if (currentState.lifecycle == RunLifecycle.CREATED) {
                    val actorId = ActorId("system.application")
                    val command = CommandEnvelope(
                        schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
                        commandId = idSource.nextCommandId(),
                        runId = runId,
                        actorId = actorId,
                        expectedSequence = currentState.lastSequence,
                        payload = ChangeRunLifecycleCommand(lifecycle = RunLifecycle.CHARACTER_CREATION),
                    )
                    val validated = when (
                        val result = CommandValidator.validate(
                            currentState,
                            definition,
                            CommandAuthorization(actorId, setOf(CommandPermission.MANAGE_RUN_LIFECYCLE)),
                            command,
                        )
                    ) {
                        is CommandValidationResult.Valid -> result.command
                        is CommandValidationResult.Invalid -> return@withContext failLoad(
                            SessionError(SessionErrorCode.COMMAND_REJECTED, result.error.message, result.error.path),
                        )
                    }
                    val event = WorldEngine.handle(validated, idSource.nextEventId()).single()
                    val next = when (val result = reducer.reduce(currentState, definition, event)) {
                        is StateReductionResult.Success -> result.state
                        is StateReductionResult.Failure -> return@withContext failLoad(
                            SessionError(SessionErrorCode.EVENT_REJECTED, result.error.message, result.error.path),
                        )
                    }
                    when (val result = eventStore.append(runId, currentState.lastSequence, listOf(event))) {
                        is EventAppendResult.Success -> currentState = next
                        is EventAppendResult.Failure -> return@withContext failLoad(
                            SessionError(SessionErrorCode.EVENT_STORE_REJECTED, result.error.message),
                        )
                    }
                }
                idSource.synchronize(currentState.lastSequence)
                loaded = LoadedSession(
                    definition,
                    worldPackage.modules,
                    initialState,
                    currentState,
                    randomService,
                    coordinator,
                    draft,
                )
                mutableState.value = GameSessionUiState.CharacterCreation(coordinator.present(draft))
                return@withContext LoadResult.Success
            }
            if (currentState.lifecycle in setOf(RunLifecycle.COMPLETED, RunLifecycle.ABANDONED)) {
                idSource.synchronize(currentState.lastSequence)
                loaded = LoadedSession(
                    definition,
                    worldPackage.modules,
                    initialState,
                    currentState,
                    randomService,
                )
                mutableState.value = GameSessionUiState.Ended(definition.source.id, currentState.lifecycle)
                return@withContext LoadResult.Success
            }
            if (currentState.lifecycle == RunLifecycle.ACTIVE && playable?.characterProfile != null) {
                characterDraftStore.delete(runId)
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

    override suspend fun updateCharacter(
        request: io.worldloom.content.schema.CharacterCreationRequest,
    ): ActionResult = mutex.withLock {
        withContext(workerDispatcher) {
            val session = loaded ?: return@withContext failAction(
                SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before creating a character"),
            )
            val coordinator = session.characterCoordinator ?: return@withContext failAction(
                SessionError(SessionErrorCode.CHARACTER_CREATION_REJECTED, "This Run has no character profile"),
            )
            val draft = session.characterDraft ?: return@withContext failAction(
                SessionError(SessionErrorCode.CHARACTER_CREATION_REJECTED, "Character creation is already complete"),
            )
            val updated = try {
                coordinator.update(draft, request)
            } catch (error: IllegalArgumentException) {
                return@withContext failAction(
                    SessionError(SessionErrorCode.CHARACTER_CREATION_REJECTED, error.message ?: "Invalid character input"),
                )
            }
            characterDraftStore.save(updated)
            session.characterDraft = updated
            mutableState.value = GameSessionUiState.CharacterCreation(coordinator.present(updated))
            ActionResult.Success
        }
    }

    override suspend fun confirmCharacter(): ActionResult = mutex.withLock {
        withContext(workerDispatcher) {
            val session = loaded ?: return@withContext failAction(
                SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before confirming a character"),
            )
            if (session.confirmedCharacterCommandId != null) return@withContext ActionResult.Success
            if (session.currentState.lifecycle == RunLifecycle.ACTIVE) {
                val committed = eventStore.read(session.currentState.runId).firstOrNull {
                    it.payload is PlayerEntityCreatedEvent
                }
                if (committed != null) {
                    session.confirmedCharacterCommandId = committed.causationId
                    return@withContext ActionResult.Success
                }
            }
            val coordinator = session.characterCoordinator ?: return@withContext failAction(
                SessionError(SessionErrorCode.CHARACTER_CREATION_REJECTED, "This Run has no character profile"),
            )
            val draft = session.characterDraft ?: return@withContext failAction(
                SessionError(SessionErrorCode.CHARACTER_CREATION_REJECTED, "Character draft is unavailable"),
            )
            val actorId = ActorId("system.application")
            val candidate = when (val result = coordinator.candidate(session.currentState, draft, actorId)) {
                is CharacterCreationCandidateResult.Success -> result
                is CharacterCreationCandidateResult.Failure -> {
                    mutableState.value = GameSessionUiState.CharacterCreation(coordinator.present(draft))
                    return@withContext ActionResult.Failure(
                        SessionError(
                            SessionErrorCode.CHARACTER_CREATION_REJECTED,
                            result.problems.firstOrNull()?.message ?: "Character input is invalid",
                            result.problems.firstOrNull()?.path,
                        ),
                    )
                }
            }
            val authorization = CommandAuthorization(actorId, setOf(CommandPermission.CREATE_PLAYER_CHARACTER))
            val validated = when (
                val validation = CommandValidator.validate(
                    session.currentState,
                    session.definition,
                    authorization,
                    candidate.command,
                    coordinator.commandPolicy(),
                )
            ) {
                is CommandValidationResult.Valid -> validation.command
                is CommandValidationResult.Invalid -> return@withContext characterFailure(
                    session,
                    SessionError(SessionErrorCode.COMMAND_REJECTED, validation.error.message, validation.error.path),
                )
            }
            val eventIds = List(WorldEngine.requiredEventCount(validated)) { idSource.nextEventId() }
            val events = WorldEngine.handle(validated, eventIds)
            val nextState = when (val reduction = reduceAll(session.currentState, session.definition, events)) {
                is StateReductionResult.Success -> reduction.state
                is StateReductionResult.Failure -> return@withContext characterFailure(
                    session,
                    SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
                )
            }
            when (val append = eventStore.append(session.currentState.runId, session.currentState.lastSequence, events)) {
                is EventAppendResult.Success -> Unit
                is EventAppendResult.Failure -> return@withContext characterFailure(
                    session,
                    SessionError(SessionErrorCode.EVENT_STORE_REJECTED, append.error.message),
                )
            }
            session.currentState = nextState
            session.confirmedCharacterCommandId = draft.confirmationCommandId
            session.characterDraft = null
            characterDraftStore.delete(nextState.runId)
            publishReady(session, eventStore.read(nextState.runId))
            ActionResult.Success
        }
    }

    override suspend fun abandonCharacter(): ActionResult = mutex.withLock {
        withContext(workerDispatcher) {
            val session = loaded ?: return@withContext failAction(
                SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a Run before abandoning it"),
            )
            if (session.currentState.lifecycle == RunLifecycle.ABANDONED) return@withContext ActionResult.Success
            val actorId = ActorId("system.application")
            val command = CommandEnvelope(
                schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
                commandId = idSource.nextCommandId(),
                runId = session.currentState.runId,
                actorId = actorId,
                expectedSequence = session.currentState.lastSequence,
                payload = ChangeRunLifecycleCommand(lifecycle = RunLifecycle.ABANDONED),
            )
            val validated = when (
                val validation = CommandValidator.validate(
                    session.currentState,
                    session.definition,
                    CommandAuthorization(actorId, setOf(CommandPermission.MANAGE_RUN_LIFECYCLE)),
                    command,
                )
            ) {
                is CommandValidationResult.Valid -> validation.command
                is CommandValidationResult.Invalid -> return@withContext failAction(
                    SessionError(SessionErrorCode.COMMAND_REJECTED, validation.error.message, validation.error.path),
                )
            }
            val event = WorldEngine.handle(validated, idSource.nextEventId()).single()
            val next = when (val reduction = reducer.reduce(session.currentState, session.definition, event)) {
                is StateReductionResult.Success -> reduction.state
                is StateReductionResult.Failure -> return@withContext failAction(
                    SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
                )
            }
            when (val append = eventStore.append(session.currentState.runId, session.currentState.lastSequence, listOf(event))) {
                is EventAppendResult.Success -> Unit
                is EventAppendResult.Failure -> return@withContext failAction(
                    SessionError(SessionErrorCode.EVENT_STORE_REJECTED, append.error.message),
                )
            }
            session.currentState = next
            session.characterDraft = null
            characterDraftStore.delete(next.runId)
            mutableState.value = GameSessionUiState.Ended(next.worldDefinitionId, next.lifecycle)
            ActionResult.Success
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
        when (current) {
            is GameSessionUiState.Ready -> mutableState.value = current.copy(notice = error)
            is GameSessionUiState.CharacterCreation -> mutableState.value = current.copy(
                presentation = current.presentation.copy(notice = error),
            )
            else -> mutableState.value = GameSessionUiState.Failed(error)
        }
        return ActionResult.Failure(error)
    }

    private fun characterFailure(session: LoadedSession, error: SessionError): ActionResult.Failure {
        val coordinator = session.characterCoordinator
        val draft = session.characterDraft
        mutableState.value = if (coordinator != null && draft != null) {
            GameSessionUiState.CharacterCreation(coordinator.present(draft, error))
        } else {
            GameSessionUiState.Failed(error)
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
        val characterCoordinator: CharacterCreationCoordinator? = null,
        var characterDraft: CharacterCreationDraft? = null,
        var confirmedCharacterCommandId: io.worldloom.world.CommandId? = null,
    )
}
