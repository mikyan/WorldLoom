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
import io.worldloom.world.NpcPublicActionCommandPolicy
import io.worldloom.world.NpcPublicActionPublishedEvent
import io.worldloom.world.PublishNpcActionCommand
import io.worldloom.world.AddressNpcCommand
import io.worldloom.world.NpcAddressCommandPolicy
import io.worldloom.world.NpcAddressedEvent
import io.worldloom.world.NpcKnowledgeRevealedEvent
import io.worldloom.world.NpcKnowledgeRevealCommandPolicy
import io.worldloom.world.RevealNpcKnowledgeCommand
import io.worldloom.world.NpcDialogueAudience
import io.worldloom.world.NpcPresenceChangedEvent
import io.worldloom.world.NpcPresenceCommandPolicy
import io.worldloom.world.SetNpcPresenceCommand
import io.worldloom.world.ActionOutcomeCommandPolicy
import io.worldloom.world.ApplyActionOutcomeCommand
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
import io.worldloom.rules.ActivityResolutionDefinition
import io.worldloom.rules.AdvanceWorldTimeCommand
import io.worldloom.rules.PerformActivityCommand
import io.worldloom.rules.TemporalCommandValidationResult
import io.worldloom.rules.TemporalCommandValidator
import io.worldloom.rules.TemporalEventReducer
import io.worldloom.rules.TemporalRuleEngine
import io.worldloom.rules.TemporalState
import io.worldloom.rules.TravelResolutionDefinition
import io.worldloom.rules.TravelRouteCommand
import io.worldloom.rules.RandomRestoreResult
import io.worldloom.rules.RestorableRandomService
import io.worldloom.rules.AdventureCommandValidationResult
import io.worldloom.rules.AdventureCommandValidator
import io.worldloom.rules.AdventureEventReducer
import io.worldloom.rules.AdventureRuleEngine
import io.worldloom.rules.AdventureState
import io.worldloom.rules.AdventureStateProjector
import io.worldloom.rules.AdjustRelationshipCommand
import io.worldloom.rules.AdvanceProgressClockCommand
import io.worldloom.rules.AdvanceQuestCommand
import io.worldloom.rules.ChangeInventoryCommand
import io.worldloom.rules.UpdateConditionCommand
import io.worldloom.rules.ExplorationCommandValidationResult
import io.worldloom.rules.ExplorationCommandValidator
import io.worldloom.rules.ExplorationEventReducer
import io.worldloom.rules.ExplorationKnowledgeChange
import io.worldloom.rules.ExplorationRevealPolicy
import io.worldloom.rules.ExplorationRuleEngine
import io.worldloom.rules.ExplorationState
import io.worldloom.rules.RevealExplorationKnowledgeCommand
import io.worldloom.rules.module.api.RegisteredWorldModules
import io.worldloom.world.packageformat.ValidatedPlayableWorldContract
import io.worldloom.world.packageformat.PlayableActionResolution
import io.worldloom.world.packageformat.PlayableNpcCapability
import io.worldloom.world.packageformat.PlayableExplorationReveal
import io.worldloom.behavior.runtime.BEHAVIOR_WORK_ORDER
import io.worldloom.behavior.runtime.BehaviorCommandIdSource
import io.worldloom.behavior.runtime.BehaviorCommandSink
import io.worldloom.behavior.runtime.BehaviorCommandSubmission
import io.worldloom.behavior.runtime.BehaviorCommandSubmitResult
import io.worldloom.behavior.runtime.BehaviorDispatchLimits
import io.worldloom.behavior.runtime.BehaviorExecutionResult
import io.worldloom.behavior.runtime.BehaviorRuntime
import io.worldloom.behavior.runtime.BehaviorWorkCreateResult
import io.worldloom.behavior.runtime.BehaviorWorkId
import io.worldloom.behavior.runtime.BehaviorWorkItem
import io.worldloom.behavior.runtime.BehaviorWorkStatus
import io.worldloom.behavior.runtime.BehaviorWorkStore
import io.worldloom.behavior.runtime.BehaviorWorkUpdateResult
import io.worldloom.behavior.runtime.InMemoryBehaviorWorkStore
import io.worldloom.world.CommandId
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
    private val behaviorWorkStore: BehaviorWorkStore = InMemoryBehaviorWorkStore(),
    private val behaviorDispatchLimits: BehaviorDispatchLimits = BehaviorDispatchLimits(),
) : GameSession, ReplayInspector {
    init {
        require(snapshotInterval > 0) { "snapshotInterval must be positive" }
    }

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<GameSessionUiState>(GameSessionUiState.Idle)
    private var loaded: LoadedSession? = null
    private val reducer: EventReducer = EventReducerChain(
        listOf(StateReducer, CheckEventReducer, TemporalEventReducer, AdventureEventReducer, ExplorationEventReducer),
    )

    override val availableWorlds: List<WorldCatalogEntry> = catalog.entries

    override val currentRunId: RunId?
        get() = loaded?.currentState?.runId
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
            val baseInitialState = if (characterProfile != null && configuredPlayerId != null) {
                InitialGameStateFactory.createForCharacterCreation(definition, runId, EntityId(configuredPlayerId))
            } else {
                InitialGameStateFactory.create(definition, runId)
            }
            val temporalInitialState = playable?.source?.temporal?.let { TemporalState.initialize(baseInitialState, it) }
                ?: baseInitialState
            val adventureInitialState = playable?.source?.adventureState?.let { AdventureState.initialize(temporalInitialState, it) }
                ?: temporalInitialState
            val initialState = if (playable?.source?.exploration != null) ExplorationState.initialize(adventureInitialState) else adventureInitialState
            if (eventStore is DurableEventStore) {
                when (
                    val initialized = eventStore.initialize(
                        initialState = initialState,
                        worldContentVersion = playable?.source?.contentVersion ?: 1,
                    )
                ) {
                    DurableStoreWriteResult.Success -> Unit
                    is DurableStoreWriteResult.Failure -> return@withContext failLoad(
                        SessionError(SessionErrorCode.PERSISTENCE_REJECTED, initialized.error.message),
                    )
                }
            }
            if (characterProfile != null && configuredPlayerId != null) {
                val coordinator = CharacterCreationCoordinator(
                    worldId = definition.source.id,
                    worldTitle = definition.source.title,
                    profile = characterProfile,
                    playerEntityId = configuredPlayerId,
                    initialSceneId = playable.source.initialSceneId,
                    initialSceneParticipantIds = playable.scene(playable.source.initialSceneId)
                        ?.participantEntityIds.orEmpty().map(::EntityId),
                    fieldLabels = characterFieldLabels(definition, configuredPlayerId),
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
                    playableContract = playable,
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
                playableContract = playable,
            )
            mutableState.value = GameSessionUiState.Ready(enrichPresentation(presentation, initialState, playable))
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
            val expectedContentVersion = worldPackage.playableContract?.source?.contentVersion ?: 1
            if (persisted.worldContentVersion != expectedContentVersion) {
                return@withContext failLoad(
                    SessionError(
                        SessionErrorCode.PERSISTENCE_REJECTED,
                        "Save content version ${persisted.worldContentVersion} does not match world version $expectedContentVersion",
                    ),
                )
            }
            val snapshotNotice = persisted.snapshotFallbackReason?.let {
                SessionError(SessionErrorCode.PERSISTENCE_REJECTED, it)
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
                allEvents.any { it.payload is RunLifecycleChangedEvent } ||
                (persisted.snapshotFallbackReason != null && configuredPlayerId != null)
            val baseInitialState = if (usesLifecycle && configuredPlayerId != null) {
                InitialGameStateFactory.createForCharacterCreation(definition, runId, EntityId(configuredPlayerId))
            } else {
                InitialGameStateFactory.create(definition, runId)
            }
            val temporalInitialState = playable?.source?.temporal?.let { TemporalState.initialize(baseInitialState, it) }
                ?: baseInitialState
            val adventureInitialState = playable?.source?.adventureState?.let { AdventureState.initialize(temporalInitialState, it) }
                ?: temporalInitialState
            val initialState = if (playable?.source?.exploration != null) ExplorationState.initialize(adventureInitialState) else adventureInitialState
            val replayBase = (persistedSnapshot ?: initialState).let { state ->
                val temporalState = playable?.source?.temporal?.let { TemporalState.initialize(state, it) } ?: state
                val adventureState = playable?.source?.adventureState?.let { AdventureState.initialize(temporalState, it) } ?: temporalState
                if (playable?.source?.exploration != null) ExplorationState.initialize(adventureState) else adventureState
            }
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
                    worldId = definition.source.id,
                    worldTitle = definition.source.title,
                    profile = profile,
                    playerEntityId = playerId,
                    initialSceneId = playable.source.initialSceneId,
                    initialSceneParticipantIds = playable.scene(playable.source.initialSceneId)
                        ?.participantEntityIds.orEmpty().map(::EntityId),
                    fieldLabels = characterFieldLabels(definition, playerId),
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
                    playableContract = playable,
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
                    playableContract = playable,
                )
                publishEnded(requireNotNull(loaded), allEvents, snapshotNotice)
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
                playableContract = playable,
            )
            mutableState.value = GameSessionUiState.Ready(
                enrichPresentation(presentation, currentState, playable),
                snapshotNotice,
            )
            dispatchBehaviors()
            LoadResult.Success
        }
    }

    override suspend fun perform(action: GameSessionAction): ActionResult = mutex.withLock {
        withContext(workerDispatcher) {
            val beforeSequence = loaded?.currentState?.lastSequence
            val result = when (action) {
                is GameSessionAction.AdjustPresentedField -> adjustPresentedField(action.presentationId)
                is GameSessionAction.ResolvePresentedCheck -> resolvePresentedCheck(action.presentationId)
                is GameSessionAction.PerformAvailableAction -> executeAuthoritative(
                    GameSessionCommand.PerformAvailableAction(action.actionId),
                    CommandAuthorization(
                        ActorId("system.player"),
                        setOf(CommandPermission.APPLY_ACTION_OUTCOME, CommandPermission.RESOLVE_CHECK),
                    ),
                )
                is GameSessionAction.AdvanceWorldTime -> executeAuthoritative(
                    GameSessionCommand.AdvanceWorldTime(action.deltaMinutes),
                    CommandAuthorization(ActorId("system.player"), setOf(CommandPermission.ADVANCE_WORLD_TIME)),
                )
                is GameSessionAction.PerformActivity -> executeAuthoritative(
                    GameSessionCommand.PerformActivity(action.activityId),
                    CommandAuthorization(
                        ActorId("system.player"),
                        setOf(CommandPermission.PERFORM_ACTIVITY, CommandPermission.RESOLVE_CHECK),
                    ),
                )
                is GameSessionAction.Travel -> executeAuthoritative(
                    GameSessionCommand.Travel(action.routeId),
                    CommandAuthorization(
                        ActorId("system.player"),
                        setOf(CommandPermission.TRAVEL, CommandPermission.RESOLVE_CHECK),
                    ),
                )
            }
            if (result == ActionResult.Success && beforeSequence != null) dispatchBehaviors()
            result
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
            val events = WorldEngine.handle(validated, eventIds).toMutableList()
            var nextState = when (val reduction = reduceAll(session.currentState, session.definition, events)) {
                is StateReductionResult.Success -> reduction.state
                is StateReductionResult.Failure -> return@withContext characterFailure(
                    session,
                    SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
                )
            }
            val initialReveals = session.playableContract?.source?.exploration?.sceneFrames
                .orEmpty().firstOrNull { it.sceneId == nextState.currentSceneId }?.initialReveals.orEmpty()
            when (val exploration = buildExplorationReveal(
                session = session,
                state = nextState,
                causeId = nextState.currentSceneId ?: session.playableContract?.source?.initialSceneId,
                reveals = initialReveals,
                actorId = actorId,
                correlationId = candidate.command.correlationId,
            )) {
                is ExplorationBuildResult.Success -> {
                    nextState = exploration.state
                    exploration.event?.let(events::add)
                }
                is ExplorationBuildResult.Failure -> return@withContext characterFailure(session, exploration.error)
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
            publishEnded(session, eventStore.read(next.runId))
            ActionResult.Success
        }
    }

    override suspend fun execute(
        command: GameSessionCommand,
        authorization: CommandAuthorization,
    ): ActionResult = mutex.withLock {
        withContext(workerDispatcher) {
            val beforeSequence = loaded?.currentState?.lastSequence
            val result = executeAuthoritative(command, authorization)
            if (result == ActionResult.Success && beforeSequence != null) dispatchBehaviors()
            result
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
                lastSequence = session.currentState.lastSequence,
                currentSceneId = session.currentState.currentSceneId,
                currentSceneParticipantIds = session.currentState.sceneParticipantIds,
                availableActions = session.availableActions(),
                worldTimeMinutes = session.temporalDefinition()?.let { TemporalState.minute(session.currentState, it) },
                availableActivities = session.availableActivities(),
                availableTravelRoutes = session.availableTravelRoutes(),
                adventureStateDefinition = session.playableContract?.source?.adventureState,
                npcProfiles = session.npcProfiles(),
                playerEntityId = session.currentState.playerEntityId,
                revealedKnowledge = eventStore.read(session.currentState.runId).mapNotNull { event ->
                    val payload = event.payload as? NpcKnowledgeRevealedEvent ?: return@mapNotNull null
                    SessionRevealedKnowledge(payload.npcId, payload.knowledgeId, payload.publicSummary, event.sequence)
                },
            )
        }
    }

    override suspend fun committedEvents(
        afterSequence: Long,
        throughSequence: Long,
    ): List<SessionCommittedEvent> = mutex.withLock {
        val session = loaded ?: return@withLock emptyList()
        var sceneId: DefinitionId? = null
        var participants = emptySet<EntityId>()
        buildList {
            eventStore.read(session.currentState.runId)
                .filter { it.sequence <= throughSequence }
                .forEach { event ->
                    when (val payload = event.payload) {
                        is io.worldloom.world.PlayerEnteredInitialSceneEvent -> {
                            sceneId = payload.sceneId
                            participants = payload.participantIds.toSet()
                        }
                        is io.worldloom.world.PlayerEnteredSceneEvent -> {
                            sceneId = payload.sceneId
                            participants = payload.participantIds.toSet()
                        }
                        is NpcPresenceChangedEvent -> {
                            participants = if (payload.present) participants + payload.entityId else participants - payload.entityId
                        }
                        else -> Unit
                    }
                    if (event.sequence > afterSequence) {
                        BehaviorEventProjector.project(event)?.let { projected ->
                            add(
                                SessionCommittedEvent(
                                    event.eventId.value,
                                    event.sequence,
                                    projected.eventType,
                                    sceneId,
                                    participants,
                                    targetNpcId = when (val payload = event.payload) {
                                        is NpcAddressedEvent -> payload.targetNpcId
                                        is NpcKnowledgeRevealedEvent -> payload.npcId
                                        else -> null
                                    },
                                    publicInput = when (val payload = event.payload) {
                                        is NpcAddressedEvent -> payload.content
                                        is NpcKnowledgeRevealedEvent -> payload.publicSummary
                                        else -> null
                                    },
                                    directedNpcWake = event.payload is NpcAddressedEvent,
                                    visibleNpcIds = when (val payload = event.payload) {
                                        is NpcAddressedEvent -> if (payload.audience == NpcDialogueAudience.PRIVATE) {
                                            setOf(payload.targetNpcId)
                                        } else {
                                            session.npcProfiles().filter { it.entityId in participants }.mapTo(mutableSetOf()) { it.id }
                                        }
                                        is NpcPublicActionPublishedEvent -> if (payload.audience == NpcDialogueAudience.PRIVATE) {
                                            emptySet()
                                        } else null
                                        else -> null
                                    },
                                    audience = when (val payload = event.payload) {
                                        is NpcAddressedEvent -> payload.audience
                                        is NpcPublicActionPublishedEvent -> payload.audience
                                        else -> NpcDialogueAudience.NEARBY_GROUP
                                    },
                                    communicationMethodId = when (val payload = event.payload) {
                                        is NpcAddressedEvent -> payload.communicationMethodId
                                        is NpcPublicActionPublishedEvent -> payload.communicationMethodId
                                        else -> null
                                    },
                                ),
                            )
                        }
                    }
                    if (event.payload is io.worldloom.world.PlayerExitedSceneEvent) {
                        sceneId = null
                        participants = emptySet()
                    }
                }
        }
    }

    override suspend fun publicNpcActions(
        afterSequence: Long,
        throughSequence: Long,
    ): List<SessionNpcPublicAction> = mutex.withLock {
        val session = loaded ?: return@withLock emptyList()
        val names = session.npcProfiles().associate { it.entityId to it.displayName }
        eventStore.read(session.currentState.runId)
            .asSequence()
            .filter { it.sequence > afterSequence && it.sequence <= throughSequence }
            .mapNotNull { event ->
                val payload = event.payload as? NpcPublicActionPublishedEvent ?: return@mapNotNull null
                val displayName = names[payload.entityId] ?: return@mapNotNull null
                SessionNpcPublicAction(
                    eventId = event.eventId.value,
                    sequence = event.sequence,
                    entityId = payload.entityId,
                    displayName = displayName,
                    kind = payload.kind,
                    actionId = payload.actionId,
                    content = payload.content,
                    audience = payload.audience,
                    communicationMethodId = payload.communicationMethodId,
                )
            }
            .toList()
    }

    override suspend fun replay(): SessionReplayResult = mutex.withLock {
        withContext(workerDispatcher) {
            val session = loaded
                ?: return@withContext failReplay(
                    SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before replaying events"),
                )
            val events = eventStore.read(session.currentState.runId)
            validateBehaviorAudit(session.currentState.runId, events)?.let { error ->
                return@withContext failReplay(error)
            }
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
            if (replayed.lifecycle in setOf(RunLifecycle.COMPLETED, RunLifecycle.ABANDONED)) {
                publishEnded(session, events)
            } else {
                publishReady(session, events)
            }
            SessionReplayResult.Success
        }
    }

    override suspend fun timelinePage(
        beforeSequenceExclusive: Long?,
        limit: Int,
    ): TimelinePageResult = mutex.withLock {
        withContext(workerDispatcher) {
            val session = loaded ?: return@withContext TimelinePageResult.Failure("Run is not loaded")
            if (limit !in 1..200) return@withContext TimelinePageResult.Failure("Page size must be 1 to 200")
            val all = eventStore.read(session.currentState.runId).sortedBy(EventEnvelope::sequence)
            val eligible = beforeSequenceExclusive?.let { before -> all.filter { it.sequence < before } } ?: all
            val selected = eligible.takeLast(limit)
            TimelinePageResult.Success(
                TimelinePage(
                    events = selected.map { PresentationMapper.presentEvent(session.definition, it) },
                    totalCount = all.size,
                    hasEarlier = selected.firstOrNull()?.sequence?.let { first -> all.any { it.sequence < first } } == true,
                ),
            )
        }
    }

    override suspend fun exportVerifiedPublicReplay(): PublicReplayResult = mutex.withLock {
        withContext(workerDispatcher) {
            val session = loaded ?: return@withContext PublicReplayResult.Failure("Run is not loaded")
            val events = try {
                eventStore.read(session.currentState.runId).sortedBy(EventEnvelope::sequence)
            } catch (_: Exception) {
                return@withContext PublicReplayResult.Failure("EventLog could not be decoded")
            }
            validateBehaviorAudit(session.currentState.runId, events)?.let { error ->
                return@withContext PublicReplayResult.Failure(error.message)
            }
            val replayed = when (val replay = EventReplayer.replay(session.initialState, session.definition, events, reducer)) {
                is ReplayResult.Failure -> return@withContext PublicReplayResult.Failure(replay.error.message)
                is ReplayResult.Success -> replay.state
            }
            if (replayed != session.currentState) {
                return@withContext PublicReplayResult.Failure("EventLog projection does not match the active state")
            }
            PublicReplayResult.Verified(
                PublicReplayDocument(
                    runId = replayed.runId,
                    worldId = replayed.worldDefinitionId,
                    lastSequence = replayed.lastSequence,
                    events = events.map { PresentationMapper.presentEvent(session.definition, it) }
                        .filter(PresentedEvent::publicReplayEligible),
                ),
            )
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
        is GameSessionCommand.PerformAvailableAction -> executePlayableAction(request, authorization)
        is GameSessionCommand.AdvanceWorldTime,
        is GameSessionCommand.PerformActivity,
        is GameSessionCommand.Travel,
        -> executeTemporalCommand(request, authorization)
        is GameSessionCommand.ChangeInventory,
        is GameSessionCommand.UpdateCondition,
        is GameSessionCommand.AdjustRelationship,
        is GameSessionCommand.AdvanceQuest,
        is GameSessionCommand.AdvanceProgressClock,
        -> executeAdventureCommand(request, authorization)
        is GameSessionCommand.PublishNpcAction -> executeNpcPublicAction(request, authorization)
        is GameSessionCommand.AddressNpc -> executeNpcAddress(request, authorization)
        is GameSessionCommand.SetNpcPresence -> executeNpcPresence(request, authorization)
    }

    private suspend fun executeNpcAddress(
        request: GameSessionCommand.AddressNpc,
        authorization: CommandAuthorization,
    ): ActionResult {
        val session = loaded
            ?: return failAction(SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before acting"))
        val profile = session.npcProfiles().firstOrNull { it.id == request.npcId && it.canSpeak }
            ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "NPC is not configured for dialogue"))
        val sceneId = session.currentState.currentSceneId
            ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "NPC dialogue has no current scene"))
        val normalizedContent = request.content.trim()
        val existing = eventStore.read(session.currentState.runId)
            .mapNotNull { it.payload as? NpcAddressedEvent }
            .firstOrNull { it.idempotencyKey == request.idempotencyKey }
        if (existing != null) {
            return if (
                existing.targetNpcId == request.npcId &&
                existing.targetEntityId == profile.entityId &&
                existing.sceneId == sceneId &&
                existing.content == normalizedContent &&
                existing.audience == request.audience &&
                existing.communicationMethodId == request.communicationMethodId
            ) {
                ActionResult.Success
            } else {
                failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "NPC dialogue idempotency key was reused"))
            }
        }
        val command = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = idSource.nextCommandId(),
            runId = session.currentState.runId,
            actorId = authorization.actorId,
            expectedSequence = session.currentState.lastSequence,
            payload = AddressNpcCommand(
                targetNpcId = profile.id,
                targetEntityId = profile.entityId,
                sceneId = sceneId,
                content = request.content,
                idempotencyKey = request.idempotencyKey,
                audience = request.audience,
                communicationMethodId = request.communicationMethodId,
            ),
        )
        val validated = when (
            val validation = CommandValidator.validate(
                session.currentState,
                session.definition,
                authorization,
                command,
                npcAddressPolicy = NpcAddressCommandPolicy(
                    profile.id,
                    profile.entityId,
                    sceneId,
                    profile.remoteCommunicationMethodIds,
                ),
            )
        ) {
            is CommandValidationResult.Valid -> validation.command
            is CommandValidationResult.Invalid -> return failAction(
                SessionError(SessionErrorCode.COMMAND_REJECTED, validation.error.message, validation.error.path),
            )
        }
        val events = WorldEngine.handle(validated, idSource.nextEventId())
        val candidate = when (val reduction = reduceAll(session.currentState, session.definition, events)) {
            is StateReductionResult.Success -> reduction.state
            is StateReductionResult.Failure -> return failAction(
                SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
            )
        }
        when (val append = eventStore.append(session.currentState.runId, session.currentState.lastSequence, events)) {
            is EventAppendResult.Success -> Unit
            is EventAppendResult.Failure -> return failAction(
                SessionError(SessionErrorCode.EVENT_STORE_REJECTED, append.error.message),
            )
        }
        session.currentState = candidate
        val notice = writeSnapshotIfDue(session)
        publishReady(session, eventStore.read(session.currentState.runId), notice)
        return ActionResult.Success
    }

    private suspend fun executeNpcPresence(
        request: GameSessionCommand.SetNpcPresence,
        authorization: CommandAuthorization,
    ): ActionResult {
        val session = loaded
            ?: return failAction(SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before acting"))
        val profile = session.npcProfiles().firstOrNull { it.id == request.npcId }
            ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "NPC is not declared by this world"))
        val sceneId = session.currentState.currentSceneId
            ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "NPC presence has no current scene"))
        val command = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = idSource.nextCommandId(),
            runId = session.currentState.runId,
            actorId = authorization.actorId,
            expectedSequence = session.currentState.lastSequence,
            payload = SetNpcPresenceCommand(
                npcId = profile.id,
                entityId = profile.entityId,
                sceneId = sceneId,
                present = request.present,
            ),
        )
        val validated = when (
            val validation = CommandValidator.validate(
                session.currentState,
                session.definition,
                authorization,
                command,
                npcPresencePolicy = NpcPresenceCommandPolicy(profile.id, profile.entityId, sceneId),
            )
        ) {
            is CommandValidationResult.Valid -> validation.command
            is CommandValidationResult.Invalid -> return failAction(
                SessionError(SessionErrorCode.COMMAND_REJECTED, validation.error.message, validation.error.path),
            )
        }
        val events = WorldEngine.handle(validated, idSource.nextEventId())
        val candidate = when (val reduction = reduceAll(session.currentState, session.definition, events)) {
            is StateReductionResult.Success -> reduction.state
            is StateReductionResult.Failure -> return failAction(
                SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
            )
        }
        when (val append = eventStore.append(session.currentState.runId, session.currentState.lastSequence, events)) {
            is EventAppendResult.Success -> Unit
            is EventAppendResult.Failure -> return failAction(
                SessionError(SessionErrorCode.EVENT_STORE_REJECTED, append.error.message),
            )
        }
        session.currentState = candidate
        val notice = writeSnapshotIfDue(session)
        publishReady(session, eventStore.read(session.currentState.runId), notice)
        return ActionResult.Success
    }

    private suspend fun executeNpcPublicAction(
        request: GameSessionCommand.PublishNpcAction,
        authorization: CommandAuthorization,
    ): ActionResult {
        val session = loaded
            ?: return failAction(SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before acting"))
        val profile = session.npcProfiles().firstOrNull { it.actorId == authorization.actorId }
            ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "NPC actor is not declared by this world"))
        val sceneId = session.currentState.currentSceneId
            ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "NPC has no current scene"))
        if (request.revealKnowledgeIds.size > 4 || request.revealKnowledgeIds.distinct().size != request.revealKnowledgeIds.size ||
            (request.revealKnowledgeIds.isNotEmpty() &&
                (request.kind != io.worldloom.world.NpcPublicActionKind.SPEECH ||
                    request.audience == NpcDialogueAudience.PRIVATE))
        ) {
            return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "NPC knowledge reveal is not allowed"))
        }
        val reveals = request.revealKnowledgeIds.map { knowledgeId ->
            profile.knowledge.firstOrNull { it.id == knowledgeId && it.revealable && it.publicSummary != null }
                ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "NPC knowledge reveal is not allowed"))
        }
        val command = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = idSource.nextCommandId(),
            runId = session.currentState.runId,
            actorId = authorization.actorId,
            expectedSequence = session.currentState.lastSequence,
            payload = PublishNpcActionCommand(
                entityId = profile.entityId,
                sceneId = sceneId,
                kind = request.kind,
                actionId = request.actionId,
                content = request.content,
                audience = request.audience,
                targetEntityId = if (request.audience == NpcDialogueAudience.PRIVATE) {
                    session.currentState.playerEntityId
                } else null,
                communicationMethodId = request.communicationMethodId,
            ),
        )
        val validated = when (
            val validation = CommandValidator.validate(
                session.currentState,
                session.definition,
                authorization,
                command,
                npcPublicActionPolicy = NpcPublicActionCommandPolicy(
                    entityId = profile.entityId,
                    sceneId = sceneId,
                    allowedActionIds = profile.publicActionIds,
                    canSpeak = profile.canSpeak,
                    playerEntityId = session.currentState.playerEntityId,
                    remoteCommunicationMethodIds = profile.remoteCommunicationMethodIds,
                ),
            )
        ) {
            is CommandValidationResult.Valid -> validation.command
            is CommandValidationResult.Invalid -> return failAction(
                SessionError(SessionErrorCode.COMMAND_REJECTED, validation.error.message, validation.error.path),
            )
        }
        val events = WorldEngine.handle(validated, idSource.nextEventId()).toMutableList()
        var candidate = when (val reduction = reduceAll(session.currentState, session.definition, events)) {
            is StateReductionResult.Success -> reduction.state
            is StateReductionResult.Failure -> return failAction(
                SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
            )
        }
        val alreadyRevealed = eventStore.read(session.currentState.runId).mapNotNullTo(mutableSetOf()) { event ->
            (event.payload as? NpcKnowledgeRevealedEvent)?.knowledgeId
        }
        reveals.filter { it.id !in alreadyRevealed }.forEach { knowledge ->
            val summary = requireNotNull(knowledge.publicSummary)
            val revealEnvelope = CommandEnvelope(
                schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
                commandId = idSource.nextCommandId(),
                runId = session.currentState.runId,
                actorId = authorization.actorId,
                expectedSequence = candidate.lastSequence,
                payload = RevealNpcKnowledgeCommand(
                    npcId = profile.id,
                    entityId = profile.entityId,
                    sceneId = sceneId,
                    knowledgeId = knowledge.id,
                    publicSummary = summary,
                ),
            )
            val reveal = when (
                val validation = CommandValidator.validate(
                    candidate,
                    session.definition,
                    authorization,
                    revealEnvelope,
                    npcKnowledgeRevealPolicy = NpcKnowledgeRevealCommandPolicy(
                        profile.id,
                        profile.entityId,
                        sceneId,
                        knowledge.id,
                        summary,
                    ),
                )
            ) {
                is CommandValidationResult.Valid -> validation.command
                is CommandValidationResult.Invalid -> return failAction(
                    SessionError(SessionErrorCode.COMMAND_REJECTED, "NPC knowledge reveal is not allowed"),
                )
            }
            val event = WorldEngine.handle(reveal, idSource.nextEventId()).single()
            candidate = when (val reduction = reducer.reduce(candidate, session.definition, event)) {
                is StateReductionResult.Success -> reduction.state
                is StateReductionResult.Failure -> return failAction(
                    SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
                )
            }
            events += event
            when (val exploration = buildExplorationReveal(
                session = session,
                state = candidate,
                causeId = knowledge.id,
                reveals = knowledge.explorationReveals,
                actorId = authorization.actorId,
                correlationId = revealEnvelope.correlationId ?: revealEnvelope.commandId.value,
            )) {
                is ExplorationBuildResult.Success -> {
                    candidate = exploration.state
                    exploration.event?.let(events::add)
                }
                is ExplorationBuildResult.Failure -> return failAction(exploration.error)
            }
        }
        when (val append = eventStore.append(session.currentState.runId, session.currentState.lastSequence, events)) {
            is EventAppendResult.Success -> Unit
            is EventAppendResult.Failure -> return failAction(
                SessionError(SessionErrorCode.EVENT_STORE_REJECTED, append.error.message),
            )
        }
        session.currentState = candidate
        val notice = writeSnapshotIfDue(session)
        publishReady(session, eventStore.read(session.currentState.runId), notice)
        return ActionResult.Success
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
            is EventAppendResult.Failure -> {
                restoreRandomServiceAfterRejectedAppend(session)
                return failAction(SessionError(SessionErrorCode.EVENT_STORE_REJECTED, append.error.message))
            }
        }
        session.currentState = candidateState
        val notice = writeSnapshotIfDue(session)
        publishReady(session, eventStore.read(session.currentState.runId), notice)
        return ActionResult.Success
    }

    private suspend fun executePlayableAction(
        request: GameSessionCommand.PerformAvailableAction,
        authorization: CommandAuthorization,
    ): ActionResult {
        val session = loaded
            ?: return failAction(SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before acting"))
        val contract = session.playableContract
            ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "World has no playable action contract"))
        val sceneId = session.currentState.currentSceneId
            ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "Player is not in a scene"))
        val scene = contract.scene(sceneId)
            ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "Current scene is not playable"))
        if (request.actionId !in scene.actionIds) {
            return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "Action is not available in the current scene"))
        }
        val action = contract.action(request.actionId)
            ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "Playable action is missing"))
        if (!actionUnlocked(action, session.currentState)) {
            return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "Action quest requirement is not satisfied"))
        }
        val baseState = session.currentState
        var candidateState = baseState
        val events = mutableListOf<EventEnvelope>()
        val checkProfileId = action.checkProfileId
        val outcomeId = if (checkProfileId != null) {
            val checkCommand = CommandEnvelope(
                schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
                commandId = idSource.nextCommandId(),
                runId = baseState.runId,
                actorId = authorization.actorId,
                expectedSequence = baseState.lastSequence,
                payload = ResolveCheckCommand(checkProfileId),
            )
            val validated = when (
                val validation = CheckCommandValidator.validate(
                    baseState,
                    session.definition,
                    session.modules,
                    authorization,
                    checkCommand,
                )
            ) {
                is CheckCommandValidationResult.Valid -> validation.command
                is CheckCommandValidationResult.Invalid -> return failAction(
                    SessionError(SessionErrorCode.CHECK_REJECTED, validation.error.message, validation.error.path),
                )
            }
            val checkEvent = when (
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
            candidateState = when (val reduction = reducer.reduce(candidateState, session.definition, checkEvent)) {
                is StateReductionResult.Success -> reduction.state
                is StateReductionResult.Failure -> return failAction(
                    SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
                )
            }
            events += checkEvent
            (checkEvent.payload as CheckResolvedEvent).record.outcomeId
        } else {
            request.selectedOutcomeId ?: action.resolutions.singleOrNull()?.outcomeId
                ?: return failAction(
                    SessionError(SessionErrorCode.COMMAND_REJECTED, "Action requires an explicit configured outcome"),
                )
        }
        val resolution = action.resolutions.firstOrNull { it.outcomeId == outcomeId }
            ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "Action outcome is not configured"))
        val progression = resolution.progression
        val participants = progression.nextSceneId
            ?.let(contract::scene)
            ?.participantEntityIds
            .orEmpty()
            .map(::EntityId)
        val progressionCommand = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = idSource.nextCommandId(),
            runId = baseState.runId,
            actorId = authorization.actorId,
            expectedSequence = candidateState.lastSequence,
            payload = ApplyActionOutcomeCommand(
                actionId = action.id,
                outcomeId = resolution.outcomeId,
                fromSceneId = sceneId,
                nextSceneId = progression.nextSceneId,
                objectiveIds = progression.objectiveIds,
                endingId = progression.endingId,
                participantIds = participants,
            ),
        )
        val policy = ActionOutcomeCommandPolicy(
            actionId = action.id,
            outcomeId = resolution.outcomeId,
            fromSceneId = sceneId,
            nextSceneId = progression.nextSceneId,
            objectiveIds = progression.objectiveIds,
            endingId = progression.endingId,
            participantIds = participants,
        )
        val validatedProgression = when (
            val validation = CommandValidator.validate(
                candidateState,
                session.definition,
                authorization,
                progressionCommand,
                actionOutcomePolicy = policy,
            )
        ) {
            is CommandValidationResult.Valid -> validation.command
            is CommandValidationResult.Invalid -> return failAction(
                SessionError(SessionErrorCode.COMMAND_REJECTED, validation.error.message, validation.error.path),
            )
        }
        val progressionEvents = WorldEngine.handle(
            validatedProgression,
            List(WorldEngine.requiredEventCount(validatedProgression)) { idSource.nextEventId() },
        )
        when (val reduction = reduceAll(candidateState, session.definition, progressionEvents)) {
            is StateReductionResult.Success -> candidateState = reduction.state
            is StateReductionResult.Failure -> return failAction(
                SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
            )
        }
        events += progressionEvents
        val sceneEntryReveals = progression.nextSceneId?.let { nextSceneId ->
            contract.source.exploration?.sceneFrames.orEmpty()
                .firstOrNull { it.sceneId == nextSceneId }?.initialReveals
        }.orEmpty()
        when (val exploration = buildExplorationReveal(
            session = session,
            state = candidateState,
            causeId = action.id,
            reveals = progression.explorationReveals + sceneEntryReveals,
            actorId = authorization.actorId,
            correlationId = progressionCommand.correlationId ?: progressionCommand.commandId.value,
        )) {
            is ExplorationBuildResult.Success -> {
                candidateState = exploration.state
                exploration.event?.let(events::add)
            }
            is ExplorationBuildResult.Failure -> return failAction(exploration.error)
        }
        when (val append = eventStore.append(baseState.runId, baseState.lastSequence, events)) {
            is EventAppendResult.Success -> Unit
            is EventAppendResult.Failure -> {
                if (checkProfileId != null) restoreRandomServiceAfterRejectedAppend(session)
                return failAction(SessionError(SessionErrorCode.EVENT_STORE_REJECTED, append.error.message))
            }
        }
        session.currentState = candidateState
        val notice = writeSnapshotIfDue(session)
        if (candidateState.lifecycle == RunLifecycle.COMPLETED) {
            publishEnded(session, eventStore.read(candidateState.runId), notice)
        } else {
            publishReady(session, eventStore.read(baseState.runId), notice)
        }
        return ActionResult.Success
    }

    private suspend fun executeTemporalCommand(
        request: GameSessionCommand,
        authorization: CommandAuthorization,
    ): ActionResult {
        val session = loaded
            ?: return failAction(SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before acting"))
        val temporal = session.temporalDefinition()
            ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "World has no temporal adventure definition"))
        val baseState = session.currentState
        var candidateState = baseState
        val events = mutableListOf<EventEnvelope>()
        val checkProfileId = when (request) {
            is GameSessionCommand.PerformActivity -> if (request.interrupted) {
                null
            } else {
                temporal.activities.firstOrNull { it.id == request.activityId }?.checkProfileId
            }
            is GameSessionCommand.Travel -> temporal.routes.firstOrNull { it.id == request.routeId }?.checkProfileId
            is GameSessionCommand.AdvanceWorldTime -> null
            else -> return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "Command is not temporal"))
        }
        val checkedOutcomeId = checkProfileId?.let { profileId ->
            val checkCommand = CommandEnvelope(
                schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
                commandId = idSource.nextCommandId(),
                runId = baseState.runId,
                actorId = authorization.actorId,
                expectedSequence = baseState.lastSequence,
                payload = ResolveCheckCommand(profileId),
            )
            val validated = when (
                val validation = CheckCommandValidator.validate(
                    baseState,
                    session.definition,
                    session.modules,
                    authorization,
                    checkCommand,
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
            candidateState = when (val reduction = reducer.reduce(candidateState, session.definition, event)) {
                is StateReductionResult.Success -> reduction.state
                is StateReductionResult.Failure -> return failAction(
                    SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
                )
            }
            events += event
            (event.payload as CheckResolvedEvent).record.outcomeId
        }
        val destinationParticipants: List<EntityId>
        val payload = when (request) {
            is GameSessionCommand.AdvanceWorldTime -> {
                destinationParticipants = emptyList()
                AdvanceWorldTimeCommand(deltaMinutes = request.deltaMinutes, reasonId = request.reasonId)
            }
            is GameSessionCommand.PerformActivity -> {
                destinationParticipants = emptyList()
                val activity = temporal.activities.firstOrNull { it.id == request.activityId }
                    ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "Activity is not configured"))
                val interruptionOutcomeId = activity.interruption?.outcomeId
                val normalResolutions = activity.resolutions.filterNot { it.outcomeId == interruptionOutcomeId }
                val outcomeId = if (request.interrupted) {
                    interruptionOutcomeId
                        ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "Activity cannot be interrupted"))
                } else {
                    checkedOutcomeId ?: request.selectedOutcomeId
                    ?: normalResolutions.singleOrNull()?.outcomeId
                    ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "Activity requires an outcome"))
                }
                PerformActivityCommand(
                    activityId = request.activityId,
                    outcomeId = outcomeId,
                    interrupted = request.interrupted,
                )
            }
            is GameSessionCommand.Travel -> {
                val route = temporal.routes.firstOrNull { it.id == request.routeId }
                    ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "Travel route is not configured"))
                destinationParticipants = session.playableContract
                    ?.scene(route.toSceneId)
                    ?.participantEntityIds
                    .orEmpty()
                    .map(::EntityId)
                val outcomeId = checkedOutcomeId ?: request.selectedOutcomeId
                    ?: route.resolutions.singleOrNull()?.outcomeId
                    ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "Travel requires an outcome"))
                TravelRouteCommand(
                    routeId = request.routeId,
                    outcomeId = outcomeId,
                    destinationParticipantIds = destinationParticipants,
                )
            }
        }
        val envelope = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = idSource.nextCommandId(),
            runId = baseState.runId,
            actorId = authorization.actorId,
            expectedSequence = candidateState.lastSequence,
            payload = payload,
        )
        val validated = when (
            val validation = TemporalCommandValidator.validate(
                candidateState,
                session.definition,
                session.modules,
                authorization,
                envelope,
                temporal,
                expectedDestinationParticipants = destinationParticipants,
            )
        ) {
            is TemporalCommandValidationResult.Valid -> validation.command
            is TemporalCommandValidationResult.Invalid -> return failAction(
                SessionError(SessionErrorCode.COMMAND_REJECTED, validation.error.message, validation.error.path),
            )
        }
        val temporalEvents = TemporalRuleEngine.handle(
            validated,
            List(TemporalRuleEngine.requiredEventCount(validated)) { idSource.nextEventId() },
        )
        when (val reduction = reduceAll(candidateState, session.definition, temporalEvents)) {
            is StateReductionResult.Success -> candidateState = reduction.state
            is StateReductionResult.Failure -> return failAction(
                SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
            )
        }
        events += temporalEvents
        if (candidateState.currentSceneId != baseState.currentSceneId) {
            val sceneReveals = session.playableContract?.source?.exploration?.sceneFrames.orEmpty()
                .firstOrNull { it.sceneId == candidateState.currentSceneId }?.initialReveals.orEmpty()
            val causeId = when (request) {
                is GameSessionCommand.Travel -> request.routeId
                is GameSessionCommand.PerformActivity -> request.activityId
                is GameSessionCommand.AdvanceWorldTime -> request.reasonId
            }
            when (val exploration = buildExplorationReveal(
                session = session,
                state = candidateState,
                causeId = causeId,
                reveals = sceneReveals,
                actorId = authorization.actorId,
                correlationId = envelope.correlationId ?: envelope.commandId.value,
            )) {
                is ExplorationBuildResult.Success -> {
                    candidateState = exploration.state
                    exploration.event?.let(events::add)
                }
                is ExplorationBuildResult.Failure -> return failAction(exploration.error)
            }
        }
        when (val append = eventStore.append(baseState.runId, baseState.lastSequence, events)) {
            is EventAppendResult.Success -> Unit
            is EventAppendResult.Failure -> {
                if (checkProfileId != null) restoreRandomServiceAfterRejectedAppend(session)
                return failAction(SessionError(SessionErrorCode.EVENT_STORE_REJECTED, append.error.message))
            }
        }
        session.currentState = candidateState
        val notice = writeSnapshotIfDue(session)
        publishReady(session, eventStore.read(baseState.runId), notice)
        return ActionResult.Success
    }

    private suspend fun executeAdventureCommand(
        request: GameSessionCommand,
        authorization: CommandAuthorization,
    ): ActionResult {
        val session = loaded
            ?: return failAction(SessionError(SessionErrorCode.SESSION_NOT_LOADED, "Load a world before acting"))
        val adventure = session.playableContract?.source?.adventureState
            ?: return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "World has no adventure-state definition"))
        val baseState = session.currentState
        val payload = when (request) {
            is GameSessionCommand.ChangeInventory -> ChangeInventoryCommand(
                itemId = request.itemId,
                quantity = request.quantity,
                operation = request.operation,
            )
            is GameSessionCommand.UpdateCondition -> UpdateConditionCommand(
                conditionId = request.conditionId,
                stackDelta = request.stackDelta,
                elapsedMinutes = request.elapsedMinutes,
            )
            is GameSessionCommand.AdjustRelationship -> AdjustRelationshipCommand(
                relationshipId = request.relationshipId,
                delta = request.delta,
            )
            is GameSessionCommand.AdvanceQuest -> AdvanceQuestCommand(
                questId = request.questId,
                stageId = request.stageId,
                status = request.status,
            )
            is GameSessionCommand.AdvanceProgressClock -> AdvanceProgressClockCommand(
                clockId = request.clockId,
                delta = request.delta,
            )
            else -> return failAction(SessionError(SessionErrorCode.COMMAND_REJECTED, "Command is not an adventure-state command"))
        }
        val envelope = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = idSource.nextCommandId(),
            runId = baseState.runId,
            actorId = authorization.actorId,
            expectedSequence = baseState.lastSequence,
            payload = payload,
        )
        val validated = when (
            val validation = AdventureCommandValidator.validate(
                baseState,
                session.modules,
                authorization,
                envelope,
                adventure,
            )
        ) {
            is AdventureCommandValidationResult.Valid -> validation.command
            is AdventureCommandValidationResult.Invalid -> return failAction(
                SessionError(SessionErrorCode.COMMAND_REJECTED, validation.error.message, validation.error.path),
            )
        }
        val events = AdventureRuleEngine.handle(
            validated,
            baseState,
            adventure,
            List(AdventureRuleEngine.requiredEventCount(validated, baseState, adventure)) { idSource.nextEventId() },
        )
        val candidate = when (val reduction = reduceAll(baseState, session.definition, events)) {
            is StateReductionResult.Success -> reduction.state
            is StateReductionResult.Failure -> return failAction(
                SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
            )
        }
        when (val append = eventStore.append(baseState.runId, baseState.lastSequence, events)) {
            is EventAppendResult.Success -> Unit
            is EventAppendResult.Failure -> return failAction(
                SessionError(SessionErrorCode.EVENT_STORE_REJECTED, append.error.message),
            )
        }
        session.currentState = candidate
        val notice = writeSnapshotIfDue(session)
        publishReady(session, eventStore.read(baseState.runId), notice)
        return ActionResult.Success
    }

    /** Scans committed facts so a crash between Event append and queue creation is recoverable. */
    private suspend fun dispatchBehaviors() {
        val session = loaded ?: return
        val behaviors = session.playableContract?.behaviors.orEmpty()
        if (behaviors.isEmpty()) return
        while (true) {
            val events = eventStore.read(session.currentState.runId)
            val before = behaviorWorkStore.list(session.currentState.runId)
            for (event in events) {
                val context = BehaviorEventProjector.project(event) ?: continue
                val origin = before.firstOrNull { item ->
                    event.causationId.value.startsWith("behavior.${item.id.value}.")
                }
                val rootEventId = origin?.rootEventId ?: event.eventId
                val depth = origin?.let { it.causalDepth + 1 } ?: 0
                behaviors.asSequence()
                    .filter { it.source.trigger.eventType == context.eventType }
                    .sortedWith(compareByDescending<io.worldloom.behavior.runtime.ValidatedBehavior> { it.source.policy.priority }
                        .thenBy { it.source.id.value })
                    .forEach { behavior ->
                        repeat(behavior.source.policy.maxFiringsPerEvent) { ordinal ->
                            val id = BehaviorWorkId("${event.eventId.value}:${behavior.source.id.value}:$ordinal")
                            behaviorWorkStore.create(
                                BehaviorWorkItem(
                                    id = id,
                                    runId = session.currentState.runId,
                                    rootEventId = rootEventId,
                                    parentEventId = event.eventId,
                                    parentSequence = event.sequence,
                                    parentEventType = context.eventType,
                                    behaviorId = behavior.source.id,
                                    priority = behavior.source.policy.priority,
                                    causalDepth = depth,
                                    triggerOrdinal = ordinal,
                                    signature = "${behavior.source.id.value}|${context.eventType.value}",
                                ),
                            )
                        }
                    }
            }
            val all = behaviorWorkStore.list(session.currentState.runId)
            val next = all.filter { it.status == BehaviorWorkStatus.PENDING || it.status == BehaviorWorkStatus.RUNNING }
                .sortedWith(BEHAVIOR_WORK_ORDER)
                .firstOrNull() ?: return
            val behavior = behaviors.firstOrNull { it.source.id == next.behaviorId }
            if (behavior == null) {
                pauseBehaviorChain(next, "Behavior is no longer registered by the fixed world package")
                continue
            }
            val chain = all.filter { it.rootEventId == next.rootEventId }
            val derivedCommands = chain.filter { it.status == BehaviorWorkStatus.COMPLETED }.sumOf { it.derivedCommandCount }
            val limitMessage = when {
                next.causalDepth > behaviorDispatchLimits.maximumCausalDepth -> "Behavior causal depth limit was reached"
                chain.size > behaviorDispatchLimits.maximumFiringsPerChain -> "Behavior firing limit was reached"
                chain.count { it.signature == next.signature } > behaviorDispatchLimits.maximumRepeatedSignature ->
                    "Behavior repeated-signature limit was reached"
                derivedCommands + behavior.source.effects.size > behaviorDispatchLimits.maximumDerivedCommandsPerChain ->
                    "Behavior derived-command limit was reached"
                else -> null
            }
            if (limitMessage != null) {
                pauseBehaviorChain(next, limitMessage)
                continue
            }
            val running = when (val updated = behaviorWorkStore.update(
                next.revision,
                next.copy(status = BehaviorWorkStatus.RUNNING, message = null),
            )) {
                is BehaviorWorkUpdateResult.Updated -> updated.item
                is BehaviorWorkUpdateResult.Conflict -> continue
                is BehaviorWorkUpdateResult.Failure -> {
                    publishBehaviorNotice(session, updated.message)
                    return
                }
            }
            val parentEvent = events.firstOrNull { it.eventId == running.parentEventId }
            val context = parentEvent?.let(BehaviorEventProjector::project)
            if (context == null) {
                pauseBehaviorChain(running, "Behavior parent Event is unavailable")
                continue
            }
            val commandAudits = linkedMapOf<CommandId, String>()
            val runtime = BehaviorRuntime(
                BehaviorCommandSink { submission ->
                    commandAudits[submission.envelope.commandId] = behaviorCommandSignature(submission.envelope.payload)
                    executeBehaviorSubmission(submission)
                },
            )
            val result = runtime.execute(
                behavior = behavior,
                event = context,
                state = session.currentState,
                actorId = ActorId("system.behavior"),
                commandIds = BehaviorCommandIdSource { _, effectIndex ->
                    CommandId("behavior.${running.id.value}.$effectIndex")
                },
                stateProvider = { session.currentState },
            )
            when (result) {
                is BehaviorExecutionResult.Applied -> completeBehaviorWork(
                    running,
                    result.commandCount,
                    result.finalSequence,
                    commandAudits,
                )
                BehaviorExecutionResult.ConditionFalse,
                BehaviorExecutionResult.NotTriggered,
                -> completeBehaviorWork(running, 0, session.currentState.lastSequence, emptyMap())
                is BehaviorExecutionResult.Failed -> pauseBehaviorChain(running, result.message)
            }
        }
    }

    private suspend fun executeBehaviorSubmission(
        submission: BehaviorCommandSubmission,
    ): BehaviorCommandSubmitResult {
        val session = loaded ?: return BehaviorCommandSubmitResult.Rejected("Run is not loaded")
        val existing = eventStore.read(session.currentState.runId).filter {
            it.causationId == submission.envelope.commandId
        }
        if (existing.isNotEmpty()) return BehaviorCommandSubmitResult.Accepted(existing.maxOf(EventEnvelope::sequence))
        val authorization = CommandAuthorization(submission.envelope.actorId, setOf(submission.requiredPermission))
        val events = when (submission.envelope.payload) {
            is AdjustNumericComponentCommand -> {
                val validated = when (
                    val validation = CommandValidator.validate(
                        session.currentState,
                        session.definition,
                        authorization,
                        submission.envelope,
                    )
                ) {
                    is CommandValidationResult.Valid -> validation.command
                    is CommandValidationResult.Invalid -> return BehaviorCommandSubmitResult.Rejected(validation.error.message)
                }
                WorldEngine.handle(validated, idSource.nextEventId())
            }
            is ChangeInventoryCommand,
            is UpdateConditionCommand,
            is AdjustRelationshipCommand,
            is AdvanceQuestCommand,
            is AdvanceProgressClockCommand,
            -> {
                val adventure = session.playableContract?.source?.adventureState
                    ?: return BehaviorCommandSubmitResult.Rejected("World has no adventure-state definition")
                val validated = when (
                    val validation = AdventureCommandValidator.validate(
                        session.currentState,
                        session.modules,
                        authorization,
                        submission.envelope,
                        adventure,
                    )
                ) {
                    is AdventureCommandValidationResult.Valid -> validation.command
                    is AdventureCommandValidationResult.Invalid -> return BehaviorCommandSubmitResult.Rejected(validation.error.message)
                }
                AdventureRuleEngine.handle(
                    validated,
                    session.currentState,
                    adventure,
                    List(AdventureRuleEngine.requiredEventCount(validated, session.currentState, adventure)) {
                        idSource.nextEventId()
                    },
                )
            }
            else -> return BehaviorCommandSubmitResult.Rejected("Behavior command is not supported by the application gateway")
        }
        val candidate = when (val reduction = reduceAll(session.currentState, session.definition, events)) {
            is StateReductionResult.Success -> reduction.state
            is StateReductionResult.Failure -> return BehaviorCommandSubmitResult.Rejected(reduction.error.message)
        }
        when (val append = eventStore.append(session.currentState.runId, session.currentState.lastSequence, events)) {
            is EventAppendResult.Success -> Unit
            is EventAppendResult.Failure -> return BehaviorCommandSubmitResult.Rejected(append.error.message)
        }
        session.currentState = candidate
        val notice = writeSnapshotIfDue(session)
        publishReady(session, eventStore.read(session.currentState.runId), notice)
        return BehaviorCommandSubmitResult.Accepted(candidate.lastSequence)
    }

    private suspend fun completeBehaviorWork(
        item: BehaviorWorkItem,
        commandCount: Int,
        sequence: Long,
        commandAudits: Map<CommandId, String>,
    ) {
        val orderedAudits = commandAudits.entries.sortedBy { entry ->
            entry.key.value.substringAfterLast('.').toIntOrNull() ?: Int.MAX_VALUE
        }
        when (
            val result = behaviorWorkStore.update(
                item.revision,
                item.copy(
                    status = BehaviorWorkStatus.COMPLETED,
                    derivedCommandCount = commandCount,
                    derivedCommandIds = orderedAudits.map { it.key },
                    derivedCommandSignatures = orderedAudits.map { it.value },
                    committedThroughSequence = sequence,
                    message = null,
                ),
            )
        ) {
            is BehaviorWorkUpdateResult.Updated -> Unit
            is BehaviorWorkUpdateResult.Conflict -> Unit
            is BehaviorWorkUpdateResult.Failure -> loaded?.let { publishBehaviorNotice(it, result.message) }
        }
    }

    private suspend fun pauseBehaviorChain(item: BehaviorWorkItem, message: String) {
        behaviorWorkStore.list(item.runId)
            .filter { it.rootEventId == item.rootEventId && it.status != BehaviorWorkStatus.COMPLETED && it.status != BehaviorWorkStatus.PAUSED }
            .forEach { pending ->
                behaviorWorkStore.update(
                    pending.revision,
                    pending.copy(status = BehaviorWorkStatus.PAUSED, message = message),
                )
            }
        loaded?.let { publishBehaviorNotice(it, message) }
    }

    private fun behaviorCommandSignature(payload: io.worldloom.world.GameCommandPayload): String = when (payload) {
        is AdjustNumericComponentCommand -> listOf(
            "numeric",
            payload.entityId.value,
            payload.componentId.value,
            payload.fieldId.value,
            payload.delta,
        ).joinToString("|")
        is ChangeInventoryCommand -> listOf("inventory", payload.itemId.value, payload.quantity, payload.operation.name).joinToString("|")
        is UpdateConditionCommand -> listOf(
            "condition",
            payload.conditionId.value,
            payload.stackDelta,
            payload.elapsedMinutes,
        ).joinToString("|")
        is AdjustRelationshipCommand -> listOf("relationship", payload.relationshipId.value, payload.delta).joinToString("|")
        is AdvanceQuestCommand -> listOf(
            "quest",
            payload.questId.value,
            payload.stageId.value,
            payload.status.name,
        ).joinToString("|")
        is AdvanceProgressClockCommand -> listOf("clock", payload.clockId.value, payload.delta).joinToString("|")
        else -> payload::class.simpleName ?: "unknown"
    }

    private suspend fun validateBehaviorAudit(
        runId: RunId,
        events: List<EventEnvelope>,
    ): SessionError? {
        val completed = behaviorWorkStore.list(runId).filter { it.status == BehaviorWorkStatus.COMPLETED }
        for (item in completed) {
            if (item.derivedCommandIds.size != item.derivedCommandCount ||
                item.derivedCommandSignatures.size != item.derivedCommandCount ||
                item.derivedCommandSignatures.any(String::isBlank)
            ) {
                return SessionError(SessionErrorCode.REPLAY_REJECTED, "Behavior command audit is incomplete: ${item.id.value}")
            }
            var previousSequence = item.parentSequence
            for (commandId in item.derivedCommandIds) {
                val derived = events.filter { it.causationId == commandId }.sortedBy(EventEnvelope::sequence)
                if (derived.isEmpty() || derived.first().sequence <= previousSequence ||
                    derived.any { it.correlationId != item.parentEventId.value }
                ) {
                    return SessionError(SessionErrorCode.REPLAY_REJECTED, "Behavior-derived Event audit is inconsistent: ${item.id.value}")
                }
                previousSequence = derived.last().sequence
            }
            val committedThrough = item.committedThroughSequence
            if (committedThrough != null && previousSequence > committedThrough) {
                return SessionError(SessionErrorCode.REPLAY_REJECTED, "Behavior committed sequence is inconsistent: ${item.id.value}")
            }
        }
        return null
    }

    private suspend fun publishBehaviorNotice(session: LoadedSession, message: String) {
        publishReady(
            session,
            eventStore.read(session.currentState.runId),
            SessionError(SessionErrorCode.BEHAVIOR_PAUSED, message),
        )
    }

    private fun buildExplorationReveal(
        session: LoadedSession,
        state: GameState,
        causeId: DefinitionId?,
        reveals: List<PlayableExplorationReveal>,
        actorId: ActorId,
        correlationId: String?,
    ): ExplorationBuildResult {
        if (session.playableContract?.source?.exploration == null || reveals.isEmpty()) {
            return ExplorationBuildResult.Success(state, null)
        }
        val normalized = reveals
            .distinctBy { it.id }
            .map { ExplorationKnowledgeChange(it.kind, it.id, it.level) }
            .filter { ExplorationState.canUpgrade(ExplorationState.level(state, it.id), it.level) }
        if (normalized.isEmpty()) return ExplorationBuildResult.Success(state, null)
        val stableCause = causeId ?: return ExplorationBuildResult.Failure(
            SessionError(SessionErrorCode.COMMAND_REJECTED, "Exploration reveal has no authoritative cause"),
        )
        val envelope = CommandEnvelope(
            schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
            commandId = idSource.nextCommandId(),
            runId = state.runId,
            actorId = actorId,
            expectedSequence = state.lastSequence,
            correlationId = correlationId,
            payload = RevealExplorationKnowledgeCommand(causeId = stableCause, changes = normalized),
        )
        val validated = when (val validation = ExplorationCommandValidator.validate(
            state = state,
            authorization = CommandAuthorization(actorId, setOf(CommandPermission.REVEAL_EXPLORATION_KNOWLEDGE)),
            envelope = envelope,
            policy = ExplorationRevealPolicy(stableCause, normalized.toSet()),
        )) {
            is ExplorationCommandValidationResult.Valid -> validation.command
            is ExplorationCommandValidationResult.Invalid -> return ExplorationBuildResult.Failure(
                SessionError(SessionErrorCode.COMMAND_REJECTED, validation.error.message, validation.error.path),
            )
        }
        val event = ExplorationRuleEngine.handle(validated, idSource.nextEventId())
        return when (val reduction = reducer.reduce(state, session.definition, event)) {
            is StateReductionResult.Success -> ExplorationBuildResult.Success(reduction.state, event)
            is StateReductionResult.Failure -> ExplorationBuildResult.Failure(
                SessionError(SessionErrorCode.EVENT_REJECTED, reduction.error.message, reduction.error.path),
            )
        }
    }

    private sealed interface ExplorationBuildResult {
        data class Success(val state: GameState, val event: EventEnvelope?) : ExplorationBuildResult
        data class Failure(val error: SessionError) : ExplorationBuildResult
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
            is PresentationMappingResult.Success -> mutableState.value = GameSessionUiState.Ready(
                enrichPresentation(mapping.presentation, session.currentState, session.playableContract),
                notice,
            )
            is PresentationMappingResult.Failure -> mutableState.value = GameSessionUiState.Failed(
                SessionError(SessionErrorCode.INVALID_WORLD_DEFINITION, mapping.message, mapping.path),
            )
        }
    }

    private fun publishEnded(
        session: LoadedSession,
        events: List<EventEnvelope>,
        notice: SessionError? = null,
    ) {
        when (val mapping = PresentationMapper.map(session.definition, session.currentState, events)) {
            is PresentationMappingResult.Success -> mutableState.value = GameSessionUiState.Ended(
                worldId = session.currentState.worldDefinitionId,
                lifecycle = session.currentState.lifecycle,
                presentation = enrichPresentation(mapping.presentation, session.currentState, session.playableContract),
                notice = notice,
            )
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

    /** A rejected atomic append must not consume a random draw that never became audited history. */
    private suspend fun restoreRandomServiceAfterRejectedAppend(session: LoadedSession) {
        val replacement = randomServiceFactory(session.currentState.runId)
        val records = try {
            eventStore.read(session.currentState.runId).mapNotNull { event ->
                (event.payload as? CheckResolvedEvent)?.record?.randomRecord
            }
        } catch (_: Exception) {
            return
        }
        if (records.isNotEmpty()) {
            val restorable = replacement as? RestorableRandomService ?: return
            if (restorable.restore(records) !is RandomRestoreResult.Success) return
        }
        session.randomService = replacement
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
            is GameSessionUiState.Ended -> mutableState.value = current.copy(notice = error)
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

    private fun characterFieldLabels(
        definition: ValidatedWorldDefinition,
        playerEntityId: String,
    ): Map<Pair<DefinitionId, DefinitionId>, String> = definition.source.presentation
        .asSequence()
        .filter { it.entityId == playerEntityId }
        .associate { (it.componentId to it.fieldId) to it.label }

    private data class LoadedSession(
        val definition: ValidatedWorldDefinition,
        val modules: RegisteredWorldModules,
        val initialState: GameState,
        var currentState: GameState,
        var randomService: RandomService,
        val characterCoordinator: CharacterCreationCoordinator? = null,
        var characterDraft: CharacterCreationDraft? = null,
        var confirmedCharacterCommandId: io.worldloom.world.CommandId? = null,
        val playableContract: ValidatedPlayableWorldContract? = null,
    )

    private fun LoadedSession.availableActions(): List<SessionAvailableAction> {
        val contract = playableContract ?: return emptyList()
        val scene = currentState.currentSceneId?.let(contract::scene) ?: return emptyList()
        return scene.actionIds.mapNotNull(contract::action).filter { action ->
            actionUnlocked(action, currentState)
        }.map { action ->
            val checkProfile = action.checkProfileId?.let(definition::checkProfile)
            SessionAvailableAction(
                actionId = action.id,
                label = action.label ?: action.id.value,
                outcomeIds = action.resolutions.map(PlayableActionResolution::outcomeId),
                requiresCheck = action.checkProfileId != null,
                checkProfileId = checkProfile?.id,
                checkLabel = checkProfile?.label,
                diceCount = checkProfile?.dice?.count,
                diceSides = checkProfile?.dice?.sides,
            )
        }
    }

    private fun LoadedSession.temporalDefinition() = playableContract?.source?.temporal

    private fun LoadedSession.npcProfiles(): List<SessionNpcProfile> =
        playableContract?.source?.npcs.orEmpty().map { npc ->
            val playerEntityId = currentState.playerEntityId?.value
            val remoteMethods = playableContract?.source?.remoteCommunicationMethods.orEmpty().filter { method ->
                playerEntityId != null && playerEntityId in method.participantEntityIds &&
                    npc.entityId in method.participantEntityIds
            }
            SessionNpcProfile(
                id = npc.id,
                entityId = EntityId(npc.entityId),
                actorId = ActorId("worldloom.actor.npc.${npc.id.value}"),
                displayName = npc.displayName,
                identityPrompt = npc.identityPrompt,
                wakeEventTypes = npc.wakeEventTypes.toSet(),
                visiblePresentationIds = npc.visiblePresentationIds.toSet(),
                goals = npc.goals,
                privateKnowledge = npc.privateKnowledge,
                knowledge = npc.knowledge.map { knowledge ->
                    SessionNpcKnowledge(
                        knowledge.id,
                        knowledge.privateText,
                        knowledge.publicSummary,
                        knowledge.revealable,
                        knowledge.explorationReveals,
                    )
                },
                canSpeak = PlayableNpcCapability.SPEAK in npc.capabilities,
                publicActionIds = npc.publicActionIds.toSet(),
                remoteCommunicationMethodIds = remoteMethods.mapTo(mutableSetOf()) { it.id },
            )
        }.sortedBy { it.id.value }

    private fun LoadedSession.availableActivities(): List<SessionAvailableActivity> {
        val sceneId = currentState.currentSceneId ?: return emptyList()
        return temporalDefinition()?.activities.orEmpty()
            .filter { sceneId in it.availableSceneIds }
            .map { activity ->
                SessionAvailableActivity(
                    activity.id,
                    activity.label,
                    activity.durationMinutes,
                    activity.resolutions.map(ActivityResolutionDefinition::outcomeId),
                    activity.checkProfileId != null,
                    activity.interruption?.outcomeId,
                )
            }
    }

    private fun LoadedSession.availableTravelRoutes(): List<SessionAvailableTravelRoute> {
        val sceneId = currentState.currentSceneId ?: return emptyList()
        return temporalDefinition()?.routes.orEmpty()
            .filter { it.fromSceneId == sceneId }
            .map { route ->
                SessionAvailableTravelRoute(
                    route.id,
                    route.label,
                    route.toSceneId,
                    route.durationMinutes,
                    route.resolutions.map(TravelResolutionDefinition::outcomeId),
                    route.checkProfileId != null,
                )
            }
    }

    private fun enrichPresentation(
        presentation: GamePresentation,
        state: GameState,
        contract: ValidatedPlayableWorldContract?,
    ): GamePresentation {
        val playerEntityId = state.playerEntityId?.value
        val characters = contract?.source?.npcs.orEmpty()
            .filter { PlayableNpcCapability.SPEAK in it.capabilities }
            .sortedBy { it.id.value }
            .map { npc ->
                val methods = contract?.source?.remoteCommunicationMethods.orEmpty()
                    .filter { method ->
                        playerEntityId != null && playerEntityId in method.participantEntityIds &&
                            npc.entityId in method.participantEntityIds
                    }
                    .map { PresentedCommunicationMethod(it.id, it.label) }
                PresentedNpc(
                    id = npc.id,
                    entityId = EntityId(npc.entityId),
                    displayName = npc.displayName,
                    publicIntroduction = npc.publicIntroduction,
                    nearby = EntityId(npc.entityId) in state.sceneParticipantIds,
                    remoteCommunicationMethods = methods,
                    avatarAssetId = npc.avatarAssetId,
                )
            }
        val scene = state.currentSceneId?.let { sceneId ->
            contract?.scene(sceneId)?.let { configured ->
                PresentedScene(
                    id = sceneId,
                    label = configured.label,
                    participantIds = state.sceneParticipantIds.sortedBy(EntityId::value),
                    actions = configured.actionIds.mapNotNull(contract::action).filter { action ->
                        actionUnlocked(action, state)
                    }.map { action ->
                        PresentedAction(action.id, action.label ?: action.id.value)
                    },
                    description = configured.description,
                    backgroundAssetId = configured.backgroundAssetId,
                    addressableNpcs = contract.source.npcs.filter { npc ->
                        PlayableNpcCapability.SPEAK in npc.capabilities && EntityId(npc.entityId) in state.sceneParticipantIds
                    }.sortedBy { it.id.value }.map { npc ->
                        PresentedNpc(
                            npc.id,
                            EntityId(npc.entityId),
                            npc.displayName,
                            npc.publicIntroduction,
                            nearby = true,
                            remoteCommunicationMethods = characters.firstOrNull { it.id == npc.id }
                                ?.remoteCommunicationMethods.orEmpty(),
                            avatarAssetId = npc.avatarAssetId,
                        )
                    },
                )
            }
        }
        val activities = contract?.source?.temporal?.activities.orEmpty()
            .filter { state.currentSceneId in it.availableSceneIds }
            .map { PresentedActivity(it.id, it.label, it.durationMinutes) }
        val travelRoutes = contract?.source?.temporal?.routes.orEmpty()
            .filter { it.fromSceneId == state.currentSceneId }
            .map { PresentedTravelRoute(it.id, it.label, it.toSceneId, it.durationMinutes) }
        val exploration = contract?.let { ExplorationProjector.project(it.source, state) } ?: ExplorationPresentation()
        val guidance = contract?.let {
            GuidanceProjector.project(
                contract = it.source,
                currentSceneId = state.currentSceneId,
                actions = scene?.actions.orEmpty(),
                activities = activities,
                travelRoutes = travelRoutes,
                addressableNpcIds = scene?.addressableNpcs.orEmpty().mapTo(mutableSetOf(), PresentedNpc::id),
                visibleKnowledgeIds = ExplorationState.known(state).keys,
                availableItemIds = presentation.adventureState?.inventory.orEmpty()
                    .mapTo(mutableSetOf()) { it.id },
            )
        } ?: GuidancePresentation()
        return presentation.copy(
            scene = scene,
            exploration = exploration,
            characters = characters,
            opening = contract?.source?.opening?.let { opening ->
                val initialScene = contract.scene(contract.source.initialSceneId)
                val initialParticipants = initialScene?.participantEntityIds.orEmpty().toSet()
                PresentedOpening(
                    premise = opening.premise,
                    objective = opening.objective,
                    firstActLabel = opening.firstActLabel,
                    sceneLabel = initialScene?.label ?: contract.source.initialSceneId.value,
                    sceneDescription = initialScene?.description,
                    npcs = contract.source.npcs.filter { npc ->
                        npc.entityId in initialParticipants
                    }.sortedBy { it.id.value }.map { npc ->
                        PresentedNpc(
                            id = npc.id,
                            entityId = EntityId(npc.entityId),
                            displayName = npc.displayName,
                            publicIntroduction = npc.publicIntroduction,
                            avatarAssetId = npc.avatarAssetId,
                        )
                    },
                    backgroundAssetId = initialScene?.backgroundAssetId,
                )
            },
            completedObjectiveIds = state.completedObjectiveIds,
            endingId = state.endingId,
            worldTimeMinutes = contract?.source?.temporal?.let { TemporalState.minute(state, it) },
            activities = activities,
            travelRoutes = travelRoutes,
            adventureState = contract?.source?.adventureState?.let { AdventureStateProjector.project(state, it) },
            endingSummary = state.endingId?.let { contract?.ending(it)?.summary },
            guidance = guidance,
            timeline = presentation.timeline.map { event ->
                val chat = event.chatMessage
                if (chat == null) return@map event
                val speakerName = if (chat.speakerKind == PresentedChatSpeakerKind.NPC && chat.speakerId != null) {
                    contract?.source?.npcs?.firstOrNull { it.entityId == chat.speakerId }?.displayName
                        ?: chat.speakerId
                } else chat.speakerName
                val targetName = when {
                    chat.speakerKind == PresentedChatSpeakerKind.PLAYER && chat.targetId != null ->
                        contract?.source?.npcs?.firstOrNull { it.id.value == chat.targetId }?.displayName ?: chat.targetId
                    chat.speakerKind == PresentedChatSpeakerKind.NPC && chat.targetId != null -> "你"
                    else -> chat.targetName
                }
                val communicationLabel = chat.communicationMethodId?.let { methodId ->
                    contract?.source?.remoteCommunicationMethods?.firstOrNull { it.id == methodId }?.label
                }
                event.copy(
                    chatMessage = chat.copy(
                        speakerName = speakerName,
                        targetName = targetName,
                        communicationLabel = communicationLabel,
                    ),
                )
            },
        )
    }

    private fun actionUnlocked(action: io.worldloom.world.packageformat.PlayableAction, state: GameState): Boolean {
        val requiredQuestId = action.requiredQuestId
        val requiredStageId = action.requiredQuestStageId
        return requiredQuestId == null || requiredStageId == null ||
            AdventureState.questStage(state, requiredQuestId) == requiredStageId
    }
}
