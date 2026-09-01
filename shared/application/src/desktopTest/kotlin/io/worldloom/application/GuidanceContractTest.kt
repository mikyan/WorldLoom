package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.world.packageformat.ArchiveEntry
import io.worldloom.world.packageformat.LoadedWorldPackage
import io.worldloom.world.packageformat.PlayableWorldContractCodec
import io.worldloom.world.packageformat.PlayableWorldContractDecodeResult
import io.worldloom.world.packageformat.WorldPackageBuilder
import io.worldloom.world.packageformat.WorldPackageLoadResult
import io.worldloom.world.packageformat.WorldPackageLoader
import io.worldloom.definition.WorldDefinitionCodec
import io.worldloom.definition.WorldDefinitionDecodeResult
import io.worldloom.rules.module.api.WorldManifestCodec
import io.worldloom.rules.module.api.WorldManifestDecodeResult
import io.worldloom.rules.module.registry.StandardRuleModules
import io.worldloom.rules.ExplorationKnowledgeLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GuidanceContractTest {
    @Test
    fun builtInWorldsProjectDifferentVisibleGuidanceTargetsWithoutSubmittingFacts() {
        val war = assertNotNull(load("war-survival").playableContract).source
        val warProjection = GuidanceProjector.project(
            war,
            war.initialSceneId,
            actions = listOf(PresentedAction(DefinitionId("war.action.search-supplies"), "搜查临街药房")),
            activities = emptyList(),
            travelRoutes = emptyList(),
            addressableNpcIds = setOf(DefinitionId("war.npc.mara"), DefinitionId("war.npc.tomas")),
            visibleKnowledgeIds = war.exploration!!.sceneFrames.single { it.sceneId == war.initialSceneId }
                .initialReveals.mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(DefinitionId("war.tutorial.describe-action"), warProjection.tutorials.single().id)
        assertEquals(GuidanceTargetKind.ACTION, warProjection.suggestions.first().targetKind)
        assertTrue(warProjection.suggestions.any { it.targetKind == GuidanceTargetKind.ACTION })

        val station = assertNotNull(load("station-ai").playableContract).source
        val stationProjection = GuidanceProjector.project(
            station,
            station.initialSceneId,
            actions = listOf(PresentedAction(DefinitionId("station.action.reroute-energy"), "重新分配能源")),
            activities = listOf(PresentedActivity(DefinitionId("station.activity.recharge"), "低功率充能", 60)),
            travelRoutes = emptyList(),
            visibleKnowledgeIds = station.exploration!!.sceneFrames.single { it.sceneId == station.initialSceneId }
                .initialReveals.mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(DefinitionId("station.hint.recharge"), stationProjection.hints.single().id)
        assertEquals(GuidanceTargetKind.DRAFT, stationProjection.suggestions.first().targetKind)
        assertTrue(stationProjection.suggestions.none { it.targetKind == GuidanceTargetKind.ACTIVITY })
    }

    @Test
    fun activeSceneWithoutProgressOptionsReturnsExplicitUnplayableDiagnostic() {
        val contract = assertNotNull(load("war-survival").playableContract).source

        val projection = GuidanceProjector.project(
            contract,
            contract.initialSceneId,
            actions = emptyList(),
            activities = emptyList(),
            travelRoutes = emptyList(),
        )

        assertFalse(projection.playable)
        assertTrue(assertNotNull(projection.diagnostic).contains("没有可用行动"))
        assertTrue(projection.suggestions.isEmpty())
    }

    @Test
    fun resourceDependentSuggestionDisappearsWhenItsPublicItemIsUnavailable() {
        val contract = assertNotNull(load("war-survival").playableContract).source
        val sceneId = DefinitionId("war.scene.under-fire")
        val visibleKnowledge = assertNotNull(contract.exploration).sceneFrames
            .single { it.sceneId == sceneId }.initialReveals.mapTo(mutableSetOf()) { it.id }

        fun project(items: Set<DefinitionId>) = GuidanceProjector.project(
            contract = contract,
            currentSceneId = sceneId,
            actions = listOf(PresentedAction(DefinitionId("war.action.escape-patrol"), "冲向排水渠缺口")),
            activities = emptyList(),
            travelRoutes = emptyList(),
            addressableNpcIds = setOf(DefinitionId("war.npc.tomas")),
            visibleKnowledgeIds = visibleKnowledge,
            availableItemIds = items,
        )

        assertTrue(project(setOf(DefinitionId("war.item.bandage"))).suggestions.any {
            it.targetId == DefinitionId("war.suggestion.fire-bandage")
        })
        assertTrue(project(emptySet()).suggestions.none {
            it.targetId == DefinitionId("war.suggestion.fire-bandage")
        })
    }

    @Test
    fun sessionEnrichesInitialPresentationWithGuidanceWithoutAddingEvents() = runTest {
        val source = packageSource("war-survival")
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(listOf(source)),
        ).catalog
        val session = DefaultGameSession(catalog)
        assertIs<LoadResult.Success>(session.load(DefinitionId("contract.war-survival")))
        assertIs<ActionResult.Success>(session.confirmCharacter())

        val ready = assertIs<GameSessionUiState.Ready>(session.state.value)
        assertEquals(6, ready.presentation.lastSequence)
        assertEquals(DefinitionId("war.tutorial.describe-action"), ready.presentation.guidance.tutorials.single().id)
        assertEquals(ExplorationKnowledgeLevel.VISITED, ready.presentation.exploration.nodes.single { it.current }.level)
        assertEquals(1, ready.presentation.exploration.knownExitCount)
        assertTrue(ready.presentation.guidance.suggestions.any { it.inputDraft.contains("药房") })
        assertTrue(ready.presentation.exploration.nodes.any { it.id == DefinitionId("war.place.pharmacy") })
        assertTrue(ready.presentation.exploration.nodes.none { it.id == DefinitionId("war.place.drainage") })
        assertTrue(ready.presentation.exploration.affordances.none { it.id == DefinitionId("war.clue.emergency-kit") })

        assertIs<ActionResult.Success>(
            session.perform(GameSessionAction.PerformAvailableAction(DefinitionId("war.action.search-supplies"))),
        )
        val progressed = assertIs<GameSessionUiState.Ready>(session.state.value).presentation
        if (progressed.scene?.id == DefinitionId("war.scene.pharmacy")) {
            assertTrue(progressed.exploration.affordances.any { it.id == DefinitionId("war.clue.emergency-kit") })
        }
        assertTrue(progressed.exploration.nodes.none { it.id == DefinitionId("war.place.checkpoint") })
        assertIs<SessionReplayResult.Success>(session.replay())
        assertEquals(progressed.exploration, assertIs<GameSessionUiState.Ready>(session.state.value).presentation.exploration)
    }

    private fun load(directory: String): LoadedWorldPackage {
        val source = packageSource(directory)
        val manifest = assertIs<WorldManifestDecodeResult.Success>(
            WorldManifestCodec.decode(source.manifestJson),
        ).manifest
        val definition = assertIs<WorldDefinitionDecodeResult.Success>(
            WorldDefinitionCodec.decode(source.files.getValue("world.json")),
        ).definition
        val entries = source.files.filterKeys { it != "world.json" }.map { ArchiveEntry(it.key, it.value.encodeToByteArray()) }
        return assertIs<WorldPackageLoadResult.Success>(
            WorldPackageLoader(StandardRuleModules.registry()).load(WorldPackageBuilder.build(manifest, definition, entries)),
        ).worldPackage
    }

    private fun packageSource(directory: String): WorldPackageSource {
        val manifest = resource("$directory/manifest.json")
        val decodedManifest = assertIs<WorldManifestDecodeResult.Success>(WorldManifestCodec.decode(manifest)).manifest
        val contractPath = assertNotNull(decodedManifest.playableContractPath)
        val contractJson = resource("$directory/$contractPath")
        val contract = assertIs<PlayableWorldContractDecodeResult.Success>(
            PlayableWorldContractCodec.decode(contractJson),
        ).contract
        val files = buildMap {
            put("world.json", resource("$directory/world.json"))
            put(contractPath, contractJson)
            contract.character.profilePath?.let { put(it, resource("$directory/$it")) }
            contract.behaviors.forEach { put(it.path, resource("$directory/${it.path}")) }
        }
        return WorldPackageSource(manifest, files)
    }

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()
}
