package io.worldloom.agent.runtime

import io.worldloom.application.DefaultGameSession
import io.worldloom.application.LoadResult
import io.worldloom.application.SequentialSessionIdSource
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.WorldPackageSource
import io.worldloom.content.generation.DraftPlayabilityProblem
import io.worldloom.content.generation.DraftPlayabilityResult
import io.worldloom.content.generation.DraftPlayabilityValidator
import io.worldloom.content.generation.PlayableDraftCandidate
import io.worldloom.definition.DefinitionId
import io.worldloom.provider.api.LanguageModelProvider
import io.worldloom.world.InMemoryEventStore
import io.worldloom.world.RunId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SandboxId(val value: String) {
    init {
        require(value.startsWith("sandbox.")) { "Sandbox id must use the sandbox namespace" }
    }
}

data class SandboxDirectoryEntry(
    val sandboxId: SandboxId,
    val draftId: String,
    val draftVersion: Int,
    val generation: Int,
    val worldId: DefinitionId,
    val runId: RunId,
)

interface SandboxDirectoryStore {
    suspend fun put(entry: SandboxDirectoryEntry)
    suspend fun remove(sandboxId: SandboxId)
    suspend fun list(): List<SandboxDirectoryEntry>
}

class InMemorySandboxDirectoryStore : SandboxDirectoryStore {
    private val mutex = Mutex()
    private val entries = mutableMapOf<SandboxId, SandboxDirectoryEntry>()

    override suspend fun put(entry: SandboxDirectoryEntry) {
        mutex.withLock { entries[entry.sandboxId] = entry }
    }

    override suspend fun remove(sandboxId: SandboxId) {
        mutex.withLock { entries.remove(sandboxId) }
    }

    override suspend fun list(): List<SandboxDirectoryEntry> = mutex.withLock {
        entries.values.sortedBy { it.sandboxId.value }
    }
}

data class HostedDraftSandbox(
    val directory: SandboxDirectoryEntry,
    val session: DefaultGameSession,
    val controller: DefaultGameAgentController,
    val eventStore: InMemoryEventStore,
    val gmSessionStore: InMemoryAgentSessionStore,
    val npcSessionStore: InMemoryAgentSessionStore,
    val turnStore: InMemoryGameTurnStore,
    val npcWorkStore: InMemoryNpcWorkStore,
    val gmMemoryStore: InMemoryAgentMemoryStore,
    val npcMemoryStore: InMemoryAgentMemoryStore,
)

sealed interface DraftSandboxResult {
    data class Created(val sandbox: HostedDraftSandbox) : DraftSandboxResult
    data class Invalid(val problems: List<DraftPlayabilityProblem>) : DraftSandboxResult
    data class Failure(val message: String) : DraftSandboxResult
}

class HostedDraftSandboxManager(
    private val validator: DraftPlayabilityValidator,
    private val directoryStore: SandboxDirectoryStore = InMemorySandboxDirectoryStore(),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()
    private val active = mutableMapOf<SandboxId, SandboxRegistration>()

    suspend fun create(
        candidate: PlayableDraftCandidate,
        provider: LanguageModelProvider,
    ): DraftSandboxResult = mutex.withLock {
        createLocked(candidate, provider, generation = 1)
    }

    suspend fun reset(sandboxId: SandboxId): DraftSandboxResult = mutex.withLock {
        val existing = active[sandboxId]
            ?: return@withLock DraftSandboxResult.Failure("Sandbox does not exist")
        when (val replacement = createLocked(existing.candidate, existing.provider, existing.generation + 1)) {
            is DraftSandboxResult.Created -> {
                active.remove(sandboxId)
                directoryStore.remove(sandboxId)
                replacement
            }
            is DraftSandboxResult.Failure -> replacement
            is DraftSandboxResult.Invalid -> replacement
        }
    }

    suspend fun delete(sandboxId: SandboxId): Boolean = mutex.withLock {
        val removed = active.remove(sandboxId) != null
        directoryStore.remove(sandboxId)
        removed
    }

    suspend fun list(): List<SandboxDirectoryEntry> = directoryStore.list()

    private suspend fun createLocked(
        candidate: PlayableDraftCandidate,
        provider: LanguageModelProvider,
        generation: Int,
    ): DraftSandboxResult {
        val validated = when (val result = validator.validate(candidate)) {
            is DraftPlayabilityResult.Valid -> result.draft
            is DraftPlayabilityResult.Invalid -> return DraftSandboxResult.Invalid(result.problems)
        }
        val worldPackage = validated.worldPackage
        val playableContract = requireNotNull(worldPackage.playableContract).source
        val sandboxFiles = buildSet {
            add(worldPackage.manifest.worldDefinitionPath)
            worldPackage.manifest.playableContractPath?.let(::add)
            playableContract.character.profilePath?.let(::add)
            playableContract.behaviors.mapTo(this) { it.path }
        }
        val catalogSource = WorldPackageSource(
            manifestJson = requireNotNull(worldPackage.entries["manifest.json"]).decodeToString(),
            files = worldPackage.entries
                .filterKeys(sandboxFiles::contains)
                .mapValues { (_, value) -> value.decodeToString() },
        )
        val catalog = when (val result = StaticWorldCatalog.fromPackageSources(listOf(catalogSource))) {
            is StaticWorldCatalogResult.Success -> result.catalog
            is StaticWorldCatalogResult.Failure -> return DraftSandboxResult.Failure(
                "Sandbox catalog rejected ${result.path ?: result.code}: ${result.message}",
            )
        }
        val safeDraft = candidate.draftId.map { character ->
            if (character.isLetterOrDigit()) character else '-'
        }.joinToString("").trim('-').ifBlank { "draft" }
        val sandboxId = SandboxId("sandbox.$safeDraft.v${candidate.draftVersion}.g$generation")
        if (sandboxId in active) {
            return DraftSandboxResult.Failure("Sandbox already exists for this draft version and generation")
        }
        val eventStore = InMemoryEventStore()
        val session = DefaultGameSession(
            catalog = catalog,
            eventStore = eventStore,
            idSource = SequentialSessionIdSource(sandboxId.value),
            workerDispatcher = workerDispatcher,
            snapshotInterval = 1,
        )
        when (val loaded = session.load(worldPackage.manifest.worldId)) {
            LoadResult.Success -> Unit
            is LoadResult.Failure -> return DraftSandboxResult.Failure(loaded.error.message)
        }
        val runId = requireNotNull(session.currentRunId)
        require(runId.value.startsWith("sandbox.")) { "Sandbox RunId escaped its namespace" }
        val gmSessionStore = InMemoryAgentSessionStore()
        val npcSessionStore = InMemoryAgentSessionStore()
        val turnStore = InMemoryGameTurnStore()
        val npcWorkStore = InMemoryNpcWorkStore()
        val gmMemoryStore = InMemoryAgentMemoryStore()
        val npcMemoryStore = InMemoryAgentMemoryStore()
        val npcOrchestrator = NpcSceneOrchestrator(
            runtime = AgentRuntime(provider, DefaultAgentToolGateway(session), npcSessionStore),
            gameSession = session,
            workStore = npcWorkStore,
            memoryStoreFactory = { npcMemoryStore },
        )
        val gateway = DefaultAgentToolGateway(session, npcOrchestrator)
        val controller = DefaultGameAgentController(
            runtime = AgentRuntime(provider, gateway, gmSessionStore),
            gameSession = session,
            turnStore = turnStore,
            directToolGateway = gateway,
            memoryStoreFactory = { gmMemoryStore },
        )
        val directory = SandboxDirectoryEntry(
            sandboxId = sandboxId,
            draftId = candidate.draftId,
            draftVersion = candidate.draftVersion,
            generation = generation,
            worldId = worldPackage.manifest.worldId,
            runId = runId,
        )
        directoryStore.put(directory)
        val sandbox = HostedDraftSandbox(
            directory,
            session,
            controller,
            eventStore,
            gmSessionStore,
            npcSessionStore,
            turnStore,
            npcWorkStore,
            gmMemoryStore,
            npcMemoryStore,
        )
        active[sandboxId] = SandboxRegistration(candidate, provider, generation, sandbox)
        return DraftSandboxResult.Created(sandbox)
    }

    private data class SandboxRegistration(
        val candidate: PlayableDraftCandidate,
        val provider: LanguageModelProvider,
        val generation: Int,
        val sandbox: HostedDraftSandbox,
    )
}
