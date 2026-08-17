package io.worldloom.application

import io.worldloom.definition.ComponentDefinition
import io.worldloom.definition.ComponentSeed
import io.worldloom.definition.CURRENT_WORLD_DEFINITION_SCHEMA_VERSION
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.EntitySeed
import io.worldloom.definition.FieldDefinition
import io.worldloom.definition.FieldSeed
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.PresentationFieldDefinition
import io.worldloom.definition.ValueType
import io.worldloom.definition.WorldDefinition
import io.worldloom.definition.WorldDefinitionCodec
import io.worldloom.world.EventAppendResult
import io.worldloom.world.EventEnvelope
import io.worldloom.world.EventStore
import io.worldloom.world.EventStoreError
import io.worldloom.world.EventStoreErrorCode
import io.worldloom.world.RunId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class DefaultGameSessionTest {
    @Test
    fun actionTravelsThroughTheAuthoritativePipelineAndUpdatesPresentation() = runTest {
        val catalog = catalog(worldSource(namespace = "station", initialValue = 80, maximum = 100, step = -10))
        val session = DefaultGameSession(
            catalog = catalog,
            workerDispatcher = StandardTestDispatcher(testScheduler),
            idSource = SequentialSessionIdSource("test"),
        )
        val worldId = catalog.entries.single().id

        assertIs<LoadResult.Success>(session.load(worldId))
        val loaded = assertIs<GameSessionUiState.Ready>(session.state.value)
        assertEquals(80, loaded.presentation.fields.single().value)

        assertIs<ActionResult.Success>(
            session.perform(
                GameSessionAction.AdjustPresentedField(loaded.presentation.fields.single().presentationId),
            ),
        )
        val updated = assertIs<GameSessionUiState.Ready>(session.state.value)

        assertEquals(70, updated.presentation.fields.single().value)
        assertEquals("能源储备: 80 → 70", updated.presentation.timeline.single().summary)
        assertEquals(1, updated.presentation.lastSequence)
        assertIs<SessionReplayResult.Success>(session.replay())
        assertEquals(updated.presentation, assertIs<GameSessionUiState.Ready>(session.state.value).presentation)
    }

    @Test
    fun rejectedAppendDoesNotPublishCandidateState() = runTest {
        val catalog = catalog(worldSource())
        val session = DefaultGameSession(
            catalog = catalog,
            eventStore = RejectingEventStore,
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
        assertIs<LoadResult.Success>(session.load(catalog.entries.single().id))
        val before = assertIs<GameSessionUiState.Ready>(session.state.value)

        val failure = assertIs<ActionResult.Failure>(
            session.perform(
                GameSessionAction.AdjustPresentedField(before.presentation.fields.single().presentationId),
            ),
        )
        val after = assertIs<GameSessionUiState.Ready>(session.state.value)

        assertEquals(SessionErrorCode.EVENT_STORE_REJECTED, failure.error.code)
        assertEquals(before.presentation, after.presentation)
        assertNotNull(after.notice)
    }

    @Test
    fun invalidWorldFailsBeforeCreatingAState() = runTest {
        val invalid = worldSource().copy(schemaVersion = 999)
        val catalog = catalog(invalid)
        val session = DefaultGameSession(
            catalog = catalog,
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )

        val failure = assertIs<LoadResult.Failure>(session.load(catalog.entries.single().id))

        assertEquals(SessionErrorCode.INVALID_WORLD_DEFINITION, failure.error.code)
        assertIs<GameSessionUiState.Failed>(session.state.value)
    }

    private fun catalog(vararg definitions: WorldDefinition): StaticWorldCatalog =
        assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromJsonSources(definitions.map(WorldDefinitionCodec::encode)),
        ).catalog

    private fun worldSource(
        namespace: String = "war",
        initialValue: Long = 7,
        maximum: Long = 10,
        step: Long = -1,
    ): WorldDefinition {
        val componentId = DefinitionId("$namespace.status")
        val fieldId = DefinitionId("$namespace.energy")
        return WorldDefinition(
            schemaVersion = CURRENT_WORLD_DEFINITION_SCHEMA_VERSION,
            id = DefinitionId("contract.$namespace"),
            title = "$namespace world",
            components = listOf(
                ComponentDefinition(
                    componentId,
                    listOf(
                        FieldDefinition(
                            fieldId,
                            ValueType.INTEGER,
                            minInteger = 0,
                            maxInteger = maximum,
                        ),
                    ),
                ),
            ),
            initialEntities = listOf(
                EntitySeed(
                    "player",
                    listOf(ComponentSeed(componentId, listOf(FieldSeed(fieldId, IntegerValue(initialValue))))),
                ),
            ),
            presentation = listOf(
                PresentationFieldDefinition(
                    id = DefinitionId("$namespace.presentation.primary"),
                    entityId = "player",
                    componentId = componentId,
                    fieldId = fieldId,
                    label = if (namespace == "station") "能源储备" else "身体状况",
                    adjustmentStep = step,
                ),
            ),
        )
    }

    private data object RejectingEventStore : EventStore {
        override suspend fun append(
            runId: RunId,
            expectedSequence: Long,
            events: List<EventEnvelope>,
        ): EventAppendResult = EventAppendResult.Failure(
            EventStoreError(EventStoreErrorCode.SEQUENCE_CONFLICT, "Injected append rejection"),
        )

        override suspend fun read(
            runId: RunId,
            afterSequence: Long,
        ): List<EventEnvelope> = emptyList()
    }
}
