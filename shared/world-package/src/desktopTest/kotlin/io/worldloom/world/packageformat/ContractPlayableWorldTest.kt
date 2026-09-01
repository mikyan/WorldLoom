package io.worldloom.world.packageformat

import io.worldloom.definition.DefinitionId
import io.worldloom.definition.WorldDefinitionCodec
import io.worldloom.definition.WorldDefinitionDecodeResult
import io.worldloom.rules.module.api.WorldManifestCodec
import io.worldloom.rules.module.api.WorldManifestDecodeResult
import io.worldloom.rules.module.registry.StandardRuleModules
import io.worldloom.content.schema.CharacterCreationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString

class ContractPlayableWorldTest {
    @Test
    fun warAndStationUseTheSamePlayableContractLoader() {
        val cases = listOf(
            ContractCase("war-survival", 3, 2, DefinitionId("war.ending.hopeful"), CharacterCreationMode.FIXED),
            ContractCase("station-ai", 3, 2, DefinitionId("station.ending.stable"), CharacterCreationMode.POINT_BUY),
        )

        cases.forEach { case ->
            val loaded = load(case.directory)
            val contract = assertNotNull(loaded.playableContract)
            val routes = contract.source.goldenRoutes

            assertEquals(case.routeCount, routes.size)
            val result = assertIs<PlayableRouteSimulationResult.Complete>(contract.simulate(routes.first().id))
            assertEquals(case.expectedGoldenEnding, result.endingId)
            assertTrue(result.trace.isNotEmpty())
            assertEquals(setOf(case.creationMode), assertNotNull(contract.characterProfile).source.modes)
            assertEquals(3, contract.behaviors.size)
            assertEquals(case.npcCount, contract.source.npcs.size)
            assertTrue(contract.source.npcs.all { npc ->
                npc.knowledge.isNotEmpty() && npc.knowledge.all { it.revealable && !it.publicSummary.isNullOrBlank() }
            })
            assertEquals(2, assertNotNull(contract.source.guidance).tutorials.size)
            assertTrue(assertNotNull(contract.source.guidance).hints.isNotEmpty())
            assertTrue(contract.scene(contract.source.initialSceneId)?.participantEntityIds.orEmpty().isNotEmpty())
            if (case.directory == "war-survival") {
                assertEquals(1, contract.source.contentVersion)
                assertEquals(60, contract.source.estimatedPlayMinutes)
                assertEquals(100, contract.source.catalogPriority)
                assertEquals(10, contract.source.scenes.size)
                assertEquals(14, contract.source.actions.size)
                assertEquals(12, contract.source.objectives.size)
                assertEquals(3, contract.source.endings.size)
                assertTrue(contract.source.scenes.all { !it.description.isNullOrBlank() })
                assertTrue(contract.source.endings.all { !it.summary.isNullOrBlank() })
            } else {
                assertEquals(2, contract.source.contentVersion)
                assertEquals(60, contract.source.estimatedPlayMinutes)
                assertEquals(90, contract.source.catalogPriority)
                assertEquals(9, contract.source.scenes.size)
                assertTrue(contract.source.actions.size >= 12)
                assertEquals(8, contract.source.objectives.size)
                assertEquals(3, contract.source.endings.size)
                assertTrue(contract.source.scenes.all { !it.description.isNullOrBlank() })
                assertTrue(contract.source.endings.all { !it.summary.isNullOrBlank() })
                assertEquals(
                    setOf(
                        DefinitionId("station.ending.stable"),
                        DefinitionId("station.ending.degraded"),
                        DefinitionId("station.ending.lost"),
                    ),
                    routes.map { route ->
                        assertIs<PlayableRouteSimulationResult.Complete>(contract.simulate(route.id)).endingId
                    }.toSet(),
                )
            }
        }
    }

    @Test
    fun legacyNpcPrivateKnowledgeRemainsReadableButIsNotRevealable() {
        val legacy = kotlinx.serialization.json.Json.decodeFromString<PlayableNpcProfile>(
            """{
                "id":"legacy.npc.guide",
                "entityId":"legacy-guide",
                "displayName":"Guide",
                "identityPrompt":"Stay in character.",
                "wakeEventTypes":["worldloom.event.scene.entered"],
                "privateKnowledge":["legacy private fact"]
            }""".trimIndent(),
        )

        assertEquals(listOf("legacy private fact"), legacy.privateKnowledge)
        assertTrue(legacy.knowledge.isEmpty())
    }

    @Test
    fun guidanceRejectsUnknownOrConditionallyHiddenTargetsWithPaths() {
        val loaded = load("war-survival")
        val source = assertNotNull(loaded.playableContract).source
        val guidance = assertNotNull(source.guidance)
        val invalid = source.copy(
            guidance = guidance.copy(
                hints = guidance.hints.mapIndexed { index, hint ->
                    if (index == 0) hint.copy(
                        target = PlayableGuidanceTarget(
                            PlayableGuidanceTargetKind.ACTION,
                            DefinitionId("war.action.missing"),
                        ),
                    ) else hint
                },
            ),
        )

        val result = assertIs<PlayableWorldValidationResult.Invalid>(
            PlayableWorldValidator.validate(invalid, loaded.definition, loaded.modules, loaded.entries),
        )

        assertTrue(result.problems.any {
            it.code == PlayableWorldProblemCode.GUIDANCE_REFERENCE_UNKNOWN &&
                it.path == "guidance.hints[0].target.id"
        })
    }

    @Test
    fun dynamicallyLockableSceneWithoutActivityOrTravelIsRejectedAsDeadEnd() {
        val loaded = load("station-ai")
        val source = assertNotNull(loaded.playableContract).source
        val locked = source.copy(
            temporal = null,
            actions = source.actions.map { action ->
                if (action.sceneId == source.initialSceneId) action.copy(
                    requiredQuestId = DefinitionId("station.quest.restore-grid"),
                    requiredQuestStageId = DefinitionId("station.quest-stage.diagnose"),
                ) else action
            },
        )

        val result = assertIs<PlayableWorldValidationResult.Invalid>(
            PlayableWorldValidator.validate(locked, loaded.definition, loaded.modules, loaded.entries),
        )

        assertTrue(result.problems.any { it.code == PlayableWorldProblemCode.GUIDANCE_DYNAMIC_DEAD_END })
    }

    @Test
    fun loaderRejectsManifestThatDeclaresMissingPlayableContract() {
        val manifest = assertIs<WorldManifestDecodeResult.Success>(
            WorldManifestCodec.decode(resource("war-survival/manifest.json")),
        ).manifest
        val definition = assertIs<WorldDefinitionDecodeResult.Success>(
            WorldDefinitionCodec.decode(resource("war-survival/world.json")),
        ).definition
        val archive = WorldPackageBuilder.build(manifest, definition)

        val failure = assertIs<WorldPackageLoadResult.Failure>(
            WorldPackageLoader(StandardRuleModules.registry()).load(archive),
        )

        assertEquals(WorldPackageProblemCode.PLAYABLE_CONTRACT_MISSING, failure.problems.single().code)
        assertTrue(failure.problems.single().message.contains("playable-world.json"))
    }

    private fun load(directory: String): LoadedWorldPackage {
        val manifest = assertIs<WorldManifestDecodeResult.Success>(
            WorldManifestCodec.decode(resource("$directory/manifest.json")),
        ).manifest
        val definition = assertIs<WorldDefinitionDecodeResult.Success>(
            WorldDefinitionCodec.decode(resource("$directory/world.json")),
        ).definition
        val contractPath = assertNotNull(manifest.playableContractPath)
        val contractJson = resource("$directory/$contractPath")
        val contract = assertIs<PlayableWorldContractDecodeResult.Success>(
            PlayableWorldContractCodec.decode(contractJson),
        ).contract
        val entries = buildList {
            add(ArchiveEntry(contractPath, contractJson.encodeToByteArray()))
            contract.character.profilePath?.let { path ->
                add(ArchiveEntry(path, resource("$directory/$path").encodeToByteArray()))
            }
            contract.behaviors.forEach { behavior ->
                add(ArchiveEntry(behavior.path, resource("$directory/${behavior.path}").encodeToByteArray()))
            }
        }
        val archive = WorldPackageBuilder.build(manifest, definition, entries)
        val result = WorldPackageLoader(StandardRuleModules.registry()).load(archive)
        return assertIs<WorldPackageLoadResult.Success>(result, result.toString()).worldPackage
    }

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()

    private data class ContractCase(
        val directory: String,
        val routeCount: Int,
        val npcCount: Int,
        val expectedGoldenEnding: DefinitionId,
        val creationMode: CharacterCreationMode,
    )
}
