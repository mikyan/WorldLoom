package io.worldloom.rules

import io.worldloom.definition.CURRENT_WORLD_DEFINITION_SCHEMA_VERSION
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.DefinitionValidationResult
import io.worldloom.definition.EntitySeed
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.WorldDefinitionValidator
import io.worldloom.world.CommandId
import io.worldloom.world.CURRENT_EVENT_SCHEMA_VERSION
import io.worldloom.world.EntityId
import io.worldloom.world.EventEnvelope
import io.worldloom.world.EventId
import io.worldloom.world.InitialGameStateFactory
import io.worldloom.world.RunId
import io.worldloom.world.StateReductionErrorCode
import io.worldloom.world.StateReductionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdventureStateTest {
    @Test
    fun initializesDefinitionDrivenStateAndHidesPrivateFacts() {
        val fixture = fixture()
        val initialized = AdventureState.initialize(fixture.state, fixture.adventure)

        assertEquals(2, AdventureState.inventoryQuantity(initialized, id("test.item.bread")))
        assertEquals(-1, AdventureState.relationshipValue(initialized, id("test.relationship.guide")))
        assertEquals(QuestStatus.NOT_STARTED, AdventureState.questStatus(initialized, id("test.quest.escape")))
        assertEquals(1, AdventureState.clockValue(initialized, id("test.clock.danger")))
        assertTrue(AdventureStateProjector.project(initialized, fixture.adventure).relationships.isEmpty())
        assertEquals(1, AdventureStateProjector.project(initialized, fixture.adventure, includePrivate = true).relationships.size)
    }

    @Test
    fun conditionDurationAndPreviousValueAreAuditedByEvents() {
        val fixture = fixture()
        val initialized = AdventureState.initialize(fixture.state, fixture.adventure)
        val applied = assertIs<StateReductionResult.Success>(
            AdventureEventReducer.reduce(
                initialized,
                fixture.world,
                event(
                    initialized,
                    ConditionUpdatedEvent(
                        conditionId = id("test.condition.tired"),
                        previousStacks = 0,
                        stacks = 2,
                        previousRemainingMinutes = 0,
                        remainingMinutes = 60,
                    ),
                ),
            ),
        ).state
        assertEquals(2, AdventureState.conditionStacks(applied, id("test.condition.tired")))
        assertEquals(60, AdventureState.conditionRemaining(applied, id("test.condition.tired")))

        val tampered = assertIs<StateReductionResult.Failure>(
            AdventureEventReducer.reduce(
                applied,
                fixture.world,
                event(
                    applied,
                    ConditionUpdatedEvent(
                        conditionId = id("test.condition.tired"),
                        previousStacks = 0,
                        stacks = 0,
                        previousRemainingMinutes = 60,
                        remainingMinutes = 0,
                    ),
                ),
            ),
        )
        assertEquals(StateReductionErrorCode.PREVIOUS_VALUE_MISMATCH, tampered.error.code)
    }

    @Test
    fun rejectsInvalidConfigurationReferencesAndBounds() {
        val fixture = fixture()
        val invalid = fixture.adventure.copy(
            inventory = fixture.adventure.inventory?.copy(capacity = 1),
            endingConditions = listOf(AdventureEndingCondition(id("test.ending.unknown"), clockId = id("test.clock.missing"), minimumClockValue = 8)),
        )

        val result = assertIs<AdventureDefinitionValidationResult.Invalid>(
            AdventureStateDefinitionValidator.validate(invalid, fixture.world, setOf(id("test.ending.escape"))),
        )
        assertTrue(result.problems.any { it.code == AdventureDefinitionProblemCode.INVALID_BOUND })
        assertTrue(result.problems.any { it.code == AdventureDefinitionProblemCode.ENDING_UNKNOWN })
        assertTrue(result.problems.any { it.code == AdventureDefinitionProblemCode.REFERENCE_UNKNOWN })
    }

    private fun fixture(): Fixture {
        val world = assertIs<DefinitionValidationResult.Valid>(
            WorldDefinitionValidator.validate(
                WorldDefinition(
                    schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
                    id = id("test.world.adventure"),
                    title = "Adventure",
                    components = emptyList(),
                    initialEntities = listOf(EntitySeed("player", emptyList()), EntitySeed("npc-guide", emptyList())),
                    presentation = emptyList(),
                ),
            ),
        ).definition
        val adventure = AdventureStateDefinition(
            items = listOf(ItemDefinition(id("test.item.bread"), "Bread", 2)),
            inventory = InventoryDefinition(EntityId("player"), 10, listOf(ItemStackDefinition(id("test.item.bread"), 2))),
            conditions = listOf(ConditionDefinition(id("test.condition.tired"), "Tired", 3, 60)),
            relationships = listOf(
                RelationshipDefinition(
                    id("test.relationship.guide"),
                    "Guide trust",
                    EntityId("player"),
                    EntityId("npc-guide"),
                    -2,
                    2,
                    -1,
                    AdventureVisibility.PRIVATE,
                ),
            ),
            quests = listOf(
                QuestDefinition(id("test.quest.escape"), "Escape", listOf(QuestStageDefinition(id("test.quest.escape.start"), "Start"))),
            ),
            clocks = listOf(ProgressClockDefinition(id("test.clock.danger"), "Danger", 4, 1)),
            endingConditions = listOf(
                AdventureEndingCondition(id("test.ending.escape"), completedQuestId = id("test.quest.escape")),
            ),
        )
        return Fixture(world, adventure, InitialGameStateFactory.create(world, RunId("run.adventure")))
    }

    private fun event(state: io.worldloom.world.GameState, payload: io.worldloom.world.GameEventPayload) = EventEnvelope(
        schemaVersion = CURRENT_EVENT_SCHEMA_VERSION,
        eventId = EventId("event.${state.lastSequence + 1}"),
        runId = state.runId,
        sequence = state.lastSequence + 1,
        causationId = CommandId("command.adventure"),
        correlationId = "command.adventure",
        payload = payload,
    )

    private data class Fixture(
        val world: io.worldloom.definition.ValidatedWorldDefinition,
        val adventure: AdventureStateDefinition,
        val state: io.worldloom.world.GameState,
    )

    private companion object {
        fun id(value: String) = DefinitionId(value)
    }
}
