package io.worldloom.agent.runtime

import io.worldloom.application.ActionResult
import io.worldloom.application.DefaultGameSession
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.LoadResult
import io.worldloom.application.SequentialSessionIdSource
import io.worldloom.application.SessionReplayResult
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.WorldPackageSource
import io.worldloom.content.generation.DraftPlayabilityValidator
import io.worldloom.content.generation.PlayableDraftCandidate
import io.worldloom.definition.WorldDefinitionCodec
import io.worldloom.definition.WorldDefinitionDecodeResult
import io.worldloom.provider.api.LanguageModelProvider
import io.worldloom.provider.api.ProviderCapabilities
import io.worldloom.provider.api.ProviderMessageRole
import io.worldloom.provider.api.ProviderRequest
import io.worldloom.provider.api.ProviderResult
import io.worldloom.provider.api.ProviderToolCall
import io.worldloom.provider.api.ProviderTurn
import io.worldloom.provider.api.ProviderUsage
import io.worldloom.rules.module.api.WorldManifestCodec
import io.worldloom.rules.module.api.WorldManifestDecodeResult
import io.worldloom.rules.module.registry.StandardRuleModules
import io.worldloom.world.ActorId
import io.worldloom.world.InMemoryEventStore
import io.worldloom.world.packageformat.ArchiveEntry
import io.worldloom.world.packageformat.WorldPackageBuilder
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HostedDraftSandboxTest {
    @Test
    fun validatedDraftUsesHostedPipelineWhileResetAndDeleteStayIsolated() = runTest {
        val formalStore = InMemoryEventStore()
        val formal = DefaultGameSession(
            catalog = catalog(),
            eventStore = formalStore,
            idSource = SequentialSessionIdSource("formal"),
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
        assertIs<LoadResult.Success>(formal.load(formal.availableWorlds.single().id))
        assertIs<ActionResult.Success>(formal.confirmCharacter())
        val formalRunId = assertNotNull(formal.currentRunId)
        val formalEvents = formalStore.read(formalRunId)

        val directory = InMemorySandboxDirectoryStore()
        val manager = HostedDraftSandboxManager(
            validator = DraftPlayabilityValidator(StandardRuleModules.registry()),
            directoryStore = directory,
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val created = assertIs<DraftSandboxResult.Created>(manager.create(candidate(), SandboxProvider())).sandbox
        assertIs<DraftSandboxResult.Failure>(manager.create(candidate(), SandboxProvider()))
        assertEquals(listOf(created.directory), manager.list())
        assertTrue(created.directory.runId.value.startsWith("sandbox."))
        assertIs<GameSessionUiState.CharacterCreation>(created.session.state.value)
        assertIs<ActionResult.Success>(created.session.confirmCharacter())

        created.controller.send("搜索附近的补给")
        val pending = assertIs<GameAgentState.AwaitingCheck>(created.controller.state.value)
        created.controller.rollPendingCheck(pending.turnId)

        assertIs<GameAgentState.Completed>(created.controller.state.value)
        val played = assertIs<GameSessionUiState.Ready>(created.session.state.value).presentation
        assertTrue(played.lastSequence > 5)
        assertIs<io.worldloom.application.PublicReplayResult.Verified>(created.session.exportVerifiedPublicReplay())
        val beforeReplay = played
        assertIs<SessionReplayResult.Success>(created.session.replay())
        assertEquals(beforeReplay, assertIs<GameSessionUiState.Ready>(created.session.state.value).presentation)
        val gmIdentity = AgentIdentity(GM_AGENT_ID, ActorId("worldloom.actor.gm"), emptySet())
        val oldGmSession = assertIs<AgentSessionLoadResult.Success>(
            created.gmSessionStore.load(
                AgentSessionId("worldloom.gm.check-resolution.${created.directory.runId.value}"),
                gmIdentity,
                created.directory.runId,
            ),
        ).snapshot
        assertTrue(oldGmSession.revision > 0)

        val reset = assertIs<DraftSandboxResult.Created>(manager.reset(created.directory.sandboxId)).sandbox

        assertNotEquals(created.directory.sandboxId, reset.directory.sandboxId)
        assertNotEquals(created.directory.runId, reset.directory.runId)
        assertEquals(2, reset.directory.generation)
        assertIs<GameSessionUiState.CharacterCreation>(reset.session.state.value)
        assertTrue(reset.eventStore.read(reset.directory.runId).isNotEmpty())
        val newGmSession = assertIs<AgentSessionLoadResult.Success>(
            reset.gmSessionStore.load(gmSessionId(reset.directory.runId), gmIdentity, reset.directory.runId),
        ).snapshot
        assertEquals(0, newGmSession.revision)
        assertTrue(reset.gmMemoryStore.turns(GM_AGENT_ID).isEmpty())
        assertEquals(formalEvents, formalStore.read(formalRunId))
        assertEquals(listOf(reset.directory), manager.list())

        assertTrue(manager.delete(reset.directory.sandboxId))
        assertTrue(manager.list().isEmpty())
        assertEquals(formalEvents, formalStore.read(formalRunId))
    }

    @Test
    fun invalidDraftCannotCreateAnySandboxDirectoryEntry() = runTest {
        val directory = InMemorySandboxDirectoryStore()
        val manager = HostedDraftSandboxManager(
            DraftPlayabilityValidator(StandardRuleModules.registry()),
            directory,
            StandardTestDispatcher(testScheduler),
        )
        val invalid = candidate().copy(packageBytes = "not a world package".encodeToByteArray())

        assertIs<DraftSandboxResult.Invalid>(manager.create(invalid, SandboxProvider()))
        assertTrue(directory.list().isEmpty())
    }

    private fun catalog(): StaticWorldCatalog = assertIs<StaticWorldCatalogResult.Success>(
        StaticWorldCatalog.fromPackageSources(
            listOf(
                WorldPackageSource(
                    resource("war-survival/manifest.json"),
                    authoredFiles(),
                ),
            ),
        ),
    ).catalog

    private fun candidate(): PlayableDraftCandidate {
        val manifest = assertIs<WorldManifestDecodeResult.Success>(
            WorldManifestCodec.decode(resource("war-survival/manifest.json")),
        ).manifest
        val definition = assertIs<WorldDefinitionDecodeResult.Success>(
            WorldDefinitionCodec.decode(resource("war-survival/world.json")),
        ).definition
        val entries = authoredFiles().filterKeys { it != "world.json" }
            .map { (path, content) -> ArchiveEntry(path, content.encodeToByteArray()) }
        return PlayableDraftCandidate(
            "sandbox-war",
            1,
            WorldPackageBuilder.build(manifest, definition, entries),
        )
    }

    private fun authoredFiles() = mapOf(
        "world.json" to resource("war-survival/world.json"),
        "playable-world.json" to resource("war-survival/playable-world.json"),
        "character-profile.json" to resource("war-survival/character-profile.json"),
        "behaviors/activity-starts-quest.json" to resource("war-survival/behaviors/activity-starts-quest.json"),
        "behaviors/quest-raises-threat.json" to resource("war-survival/behaviors/quest-raises-threat.json"),
        "behaviors/timed-supply.json" to resource("war-survival/behaviors/timed-supply.json"),
    )

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()
}

private class SandboxProvider : LanguageModelProvider {
    override val capabilities = ProviderCapabilities(toolCalling = true, streaming = false, structuredOutput = false)
    private var calls: Int = 0

    override suspend fun complete(request: ProviderRequest): ProviderResult {
        calls += 1
        if (request.messages.last().role == ProviderMessageRole.TOOL) {
            return ProviderResult.Success(ProviderTurn("主持人确认权威结果。", usage = ProviderUsage(8, 4)))
        }
        request.tools.firstOrNull { it.name == PERFORM_ACTION_TOOL_ID.value }?.let { tool ->
            val actionId = tool.parameters.single { it.name == "actionId" }.allowedValues.first()
            return ProviderResult.Success(
                ProviderTurn(
                    toolCalls = listOf(
                        ProviderToolCall(
                            "sandbox-gm-$calls",
                            tool.name,
                            buildJsonObject { put("actionId", actionId) },
                        ),
                    ),
                    usage = ProviderUsage(12, 4),
                ),
            )
        }
        request.tools.firstOrNull { it.name == NPC_SPEAK_TOOL_ID.value }?.let { tool ->
            return ProviderResult.Success(
                ProviderTurn(
                    toolCalls = listOf(
                        ProviderToolCall(
                            "sandbox-npc-$calls",
                            tool.name,
                            buildJsonObject { put("content", "同伴确认沙箱中的公开变化。") },
                        ),
                    ),
                    usage = ProviderUsage(10, 4),
                ),
            )
        }
        return ProviderResult.Success(ProviderTurn("局势等待下一步。", usage = ProviderUsage(4, 2)))
    }
}
