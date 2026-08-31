package io.worldloom.world.packageformat

import io.worldloom.definition.BooleanValue
import io.worldloom.definition.CheckOutcomeDefinition
import io.worldloom.definition.CheckProfileDefinition
import io.worldloom.definition.CheckResolutionMode
import io.worldloom.definition.ComponentDefinition
import io.worldloom.definition.ComponentSeed
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.DiceExpression
import io.worldloom.definition.EntitySeed
import io.worldloom.definition.FieldDefinition
import io.worldloom.definition.FieldSeed
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.PresentationCheckDefinition
import io.worldloom.definition.PresentationFieldDefinition
import io.worldloom.definition.ValueType
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.WorldDefinitionValidator
import io.worldloom.rules.module.api.CURRENT_RULE_MODULE_API_VERSION
import io.worldloom.rules.module.api.CURRENT_WORLD_MANIFEST_SCHEMA_VERSION
import io.worldloom.rules.module.api.ModuleVersion
import io.worldloom.rules.module.api.WorldManifest
import io.worldloom.rules.module.api.WorldModuleSelection
import io.worldloom.rules.module.registry.ModuleRegistrationResult
import io.worldloom.rules.module.registry.StandardRuleModules
import io.worldloom.rules.ActivityDefinition
import io.worldloom.rules.ActivityResolutionDefinition
import io.worldloom.rules.TemporalAdventureDefinition
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlayableWorldContractTest {
    @Test
    fun openingPresentationRejectsBlankCopyAndUnstableBackgroundAssetIds() {
        val fixture = fixture()
        val invalid = fixture.contract.copy(
            opening = PlayableOpeningPresentation("", "Reach safety", "Act I"),
            scenes = fixture.contract.scenes.map { scene ->
                scene.copy(backgroundAssetId = "Not A Stable Asset")
            },
        )

        val problems = assertIs<PlayableWorldValidationResult.Invalid>(
            PlayableWorldValidator.validate(invalid, fixture.definition, fixture.modules, emptyMap()),
        ).problems

        assertTrue(problems.any { it.path == "opening" })
        assertTrue(problems.any { it.path.endsWith("backgroundAssetId") })
    }

    @Test
    fun temporalConfigurationRejectsUnknownScenesAndMissingModuleDeclarations() {
        val fixture = fixture()
        val invalid = fixture.contract.copy(
            temporal = TemporalAdventureDefinition(
                activities = listOf(
                    ActivityDefinition(
                        id = id("test.activity.wait"),
                        label = "Wait",
                        durationMinutes = 30,
                        availableSceneIds = listOf(id("test.scene.missing")),
                        resolutions = listOf(ActivityResolutionDefinition(id("worldloom.outcome.complete"))),
                    ),
                ),
            ),
        )

        val problems = assertIs<PlayableWorldValidationResult.Invalid>(
            PlayableWorldValidator.validate(invalid, fixture.definition, fixture.modules, emptyMap()),
        ).problems

        assertTrue(problems.any { it.code == PlayableWorldProblemCode.TEMPORAL_INVALID && it.path.contains("availableSceneIds") })
        assertTrue(problems.any { it.code == PlayableWorldProblemCode.REQUIRED_MODULE_MISSING })
    }

    @Test
    fun validContractSimulatesSuccessAndFailureEndings() {
        val fixture = fixture()
        val validated = assertIs<PlayableWorldValidationResult.Valid>(
            PlayableWorldValidator.validate(fixture.contract, fixture.definition, fixture.modules, emptyMap()),
        ).contract

        val result = assertIs<PlayableRouteSimulationResult.Complete>(validated.simulate(id("test.route.golden")))

        assertEquals(id("test.ending.success"), result.endingId)
        assertEquals(setOf(id("test.objective.escape")), result.completedObjectiveIds)
        assertEquals(id("test.action.escape"), result.trace.single().actionId)
    }

    @Test
    fun validationReportsPreciseEntryReferenceAndReachabilityProblems() {
        val fixture = fixture()
        val invalid = fixture.contract.copy(
            character = PlayableCharacterEntry(),
            initialSceneId = id("test.scene.missing"),
            requiredModuleIds = fixture.contract.requiredModuleIds + id("test.module.forbidden"),
            presentationIds = fixture.contract.presentationIds + id("test.presentation.missing"),
            endings = fixture.contract.endings + PlayableEnding(id("test.ending.unreachable"), "Unreachable"),
            behaviors = listOf(PlayableBehaviorReference(id("test.behavior.missing"), "behaviors/missing.json")),
        )

        val problems = assertIs<PlayableWorldValidationResult.Invalid>(
            PlayableWorldValidator.validate(invalid, fixture.definition, fixture.modules, emptyMap()),
        ).problems

        val codes = problems.map { it.code }.toSet()
        assertTrue(PlayableWorldProblemCode.MISSING_CHARACTER_ENTRY in codes)
        assertTrue(PlayableWorldProblemCode.INITIAL_SCENE_UNKNOWN in codes)
        assertTrue(PlayableWorldProblemCode.REQUIRED_MODULE_MISSING in codes)
        assertTrue(PlayableWorldProblemCode.PRESENTATION_UNKNOWN in codes)
        assertTrue(PlayableWorldProblemCode.BEHAVIOR_MISSING in codes)

        val unreachable = fixture.contract.copy(
            endings = fixture.contract.endings + PlayableEnding(id("test.ending.unreachable"), "Unreachable"),
        )
        val reachabilityCodes = assertIs<PlayableWorldValidationResult.Invalid>(
            PlayableWorldValidator.validate(unreachable, fixture.definition, fixture.modules, emptyMap()),
        ).problems.map { it.code }.toSet()
        assertTrue(PlayableWorldProblemCode.ENDING_UNREACHABLE in reachabilityCodes)
    }

    @Test
    fun validationRejectsMissingFailureAndEmptyProgression() {
        val fixture = fixture()
        val action = fixture.contract.actions.single().copy(
            resolutions = listOf(
                PlayableActionResolution(
                    id("test.outcome.success"),
                    PlayableOutcomeKind.SUCCESS,
                    PlayableProgression(),
                ),
            ),
        )
        val invalid = fixture.contract.copy(actions = listOf(action))

        val codes = assertIs<PlayableWorldValidationResult.Invalid>(
            PlayableWorldValidator.validate(invalid, fixture.definition, fixture.modules, emptyMap()),
        ).problems.map { it.code }.toSet()

        assertTrue(PlayableWorldProblemCode.ACTION_OUTCOME_MISMATCH in codes)
        assertTrue(PlayableWorldProblemCode.ACTION_FAILURE_MISSING in codes)
        assertTrue(PlayableWorldProblemCode.PROGRESSION_EMPTY in codes)
    }

    @Test
    fun randomGoldenRouteRequiresRecordedDiceFacts() {
        val fixture = fixture(random = true)

        val codes = assertIs<PlayableWorldValidationResult.Invalid>(
            PlayableWorldValidator.validate(fixture.contract, fixture.definition, fixture.modules, emptyMap()),
        ).problems.map { it.code }.toSet()

        assertTrue(PlayableWorldProblemCode.ROUTE_RANDOM_RECORD_INVALID in codes)
    }

    @Test
    fun goldenRouteRunnerRequiresToolCommandEventAndReplayEvidence() = runTest {
        val fixture = fixture()
        val contract = assertIs<PlayableWorldValidationResult.Valid>(
            PlayableWorldValidator.validate(fixture.contract, fixture.definition, fixture.modules, emptyMap()),
        ).contract
        val runner = GoldenRouteRunner(contract, RecordingDriver())

        val result = assertIs<GoldenRouteRunResult.Success>(runner.run(id("test.route.golden")))

        assertEquals(id("test.ending.success"), result.endingId)
        assertEquals("command.test.1", result.receipts.single().commandId)
        assertEquals(listOf("event.test.1"), result.receipts.single().eventIds)
    }

    @Test
    fun goldenRouteRunnerRejectsReceiptWithoutCommittedEvents() = runTest {
        val fixture = fixture()
        val contract = assertIs<PlayableWorldValidationResult.Valid>(
            PlayableWorldValidator.validate(fixture.contract, fixture.definition, fixture.modules, emptyMap()),
        ).contract
        val driver = object : GoldenRouteDriver {
            override suspend fun start(): Long = 0

            override suspend fun perform(intent: GoldenRouteIntent) = GoldenRouteStepReceipt(
                actionId = intent.actionId,
                toolId = id("test.tool.act"),
                commandId = "",
                eventIds = emptyList(),
                lastEventSequence = 0,
            )

            override suspend fun replay() = GoldenRouteReplayReceipt(0, null)
        }

        val failure = assertIs<GoldenRouteRunResult.Failure>(
            GoldenRouteRunner(contract, driver).run(id("test.route.golden")),
        )

        assertEquals(GoldenRouteRunProblemCode.AUTHORITATIVE_RECEIPT_MISSING, failure.problem.code)
    }

    private fun fixture(random: Boolean = false): Fixture {
        val definition = assertIs<DefinitionValidationResult.Valid>(
            WorldDefinitionValidator.validate(definition(random)),
        ).definition
        val manifest = manifest(random)
        val modules = assertIs<ModuleRegistrationResult.Success>(
            StandardRuleModules.registry().register(manifest),
        ).modules
        return Fixture(definition, modules, contract(manifest.modules.map { it.id }))
    }

    private fun definition(random: Boolean) = WorldDefinition(
        schemaVersion = 2,
        id = id("test.world.contract"),
        title = "Contract Test",
        components = listOf(
            ComponentDefinition(
                id("test.component.state"),
                listOf(FieldDefinition(id("test.field.value"), ValueType.INTEGER, minInteger = 0, maxInteger = 10)),
            ),
        ),
        initialEntities = listOf(
            EntitySeed(
                "player",
                listOf(
                    ComponentSeed(
                        id("test.component.state"),
                        listOf(FieldSeed(id("test.field.value"), IntegerValue(5))),
                    ),
                ),
            ),
        ),
        checkProfiles = listOf(
            CheckProfileDefinition(
                id = id("test.check.escape"),
                label = "Escape",
                mode = if (random) CheckResolutionMode.RANDOM else CheckResolutionMode.DETERMINISTIC,
                dice = if (random) DiceExpression(1, 6) else null,
                baseValue = 1,
                outcomes = listOf(
                    CheckOutcomeDefinition(id("test.outcome.success"), "Success", 1),
                    CheckOutcomeDefinition(id("test.outcome.failure"), "Failure", -1000),
                ),
            ),
        ),
        presentation = listOf(
            PresentationFieldDefinition(
                id("test.presentation.state"),
                "player",
                id("test.component.state"),
                id("test.field.value"),
                "State",
                -1,
            ),
        ),
        presentationChecks = listOf(
            PresentationCheckDefinition(id("test.presentation.escape"), id("test.check.escape"), "Escape"),
        ),
    )

    private fun manifest(random: Boolean) = WorldManifest(
        schemaVersion = CURRENT_WORLD_MANIFEST_SCHEMA_VERSION,
        runtimeApiVersion = CURRENT_RULE_MODULE_API_VERSION,
        worldId = id("test.world.contract"),
        worldDefinitionPath = "world.json",
        modules = listOf(
            WorldModuleSelection(
                id("worldloom.core.numeric-state"),
                ModuleVersion(1, 0, 0),
                mapOf(id("worldloom.parameter.direct-adjustment") to BooleanValue(true)),
            ),
            WorldModuleSelection(
                id(if (random) "worldloom.rules.random-check" else "worldloom.rules.deterministic-check"),
                ModuleVersion(1, 0, 0),
            ),
        ),
    )

    private fun contract(requiredModules: List<DefinitionId>) = PlayableWorldContract(
        schema = PLAYABLE_WORLD_CONTRACT_SCHEMA_V1,
        character = PlayableCharacterEntry(prebuiltPlayerEntityId = "player"),
        initialSceneId = id("test.scene.start"),
        requiredModuleIds = requiredModules,
        scenes = listOf(PlayableScene(id("test.scene.start"), "Start", listOf(id("test.action.escape")))),
        actions = listOf(
            PlayableAction(
                id = id("test.action.escape"),
                sceneId = id("test.scene.start"),
                checkProfileId = id("test.check.escape"),
                resolutions = listOf(
                    PlayableActionResolution(
                        id("test.outcome.success"),
                        PlayableOutcomeKind.SUCCESS,
                        PlayableProgression(
                            objectiveIds = listOf(id("test.objective.escape")),
                            endingId = id("test.ending.success"),
                        ),
                    ),
                    PlayableActionResolution(
                        id("test.outcome.failure"),
                        PlayableOutcomeKind.FAILURE,
                        PlayableProgression(endingId = id("test.ending.failure")),
                    ),
                ),
            ),
        ),
        objectives = listOf(PlayableObjective(id("test.objective.escape"), "Escape")),
        endings = listOf(
            PlayableEnding(id("test.ending.success"), "Safe"),
            PlayableEnding(id("test.ending.failure"), "Lost"),
        ),
        presentationIds = listOf(id("test.presentation.state"), id("test.presentation.escape")),
        goldenRoutes = listOf(
            PlayableRouteFixture(
                id("test.route.golden"),
                listOf(PlayableRouteStep(id("test.action.escape"))),
                id("test.ending.success"),
            ),
        ),
    )

    private data class Fixture(
        val definition: io.worldloom.definition.ValidatedWorldDefinition,
        val modules: io.worldloom.rules.module.api.RegisteredWorldModules,
        val contract: PlayableWorldContract,
    )

    private class RecordingDriver : GoldenRouteDriver {
        override suspend fun start(): Long = 0

        override suspend fun perform(intent: GoldenRouteIntent) = GoldenRouteStepReceipt(
            actionId = intent.actionId,
            toolId = id("test.tool.act"),
            commandId = "command.test.1",
            eventIds = listOf("event.test.1"),
            lastEventSequence = 1,
            endingId = id("test.ending.success"),
        )

        override suspend fun replay() = GoldenRouteReplayReceipt(1, id("test.ending.success"))
    }

    private companion object {
        fun id(value: String) = DefinitionId(value)
    }
}
