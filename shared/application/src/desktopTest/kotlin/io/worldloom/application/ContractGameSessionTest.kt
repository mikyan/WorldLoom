package io.worldloom.application

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContractGameSessionTest {
    @Test
    fun bothContractWorldsExposeAndReplayTheirConfiguredCheck() = runTest {
        val sources = listOf("war-survival", "station-ai").map { directory ->
            WorldPackageSource(
                manifestJson = resource("$directory/manifest.json"),
                files = mapOf("world.json" to resource("$directory/world.json")),
            )
        }
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromPackageSources(sources),
        ).catalog

        catalog.entries.forEach { entry ->
            val session = DefaultGameSession(
                catalog = catalog,
                idSource = SequentialSessionIdSource(entry.id.value),
                workerDispatcher = StandardTestDispatcher(testScheduler),
            )
            assertIs<LoadResult.Success>(session.load(entry.id))
            val loaded = assertIs<GameSessionUiState.Ready>(session.state.value)
            val check = loaded.presentation.checks.single()

            assertIs<ActionResult.Success>(
                session.perform(GameSessionAction.ResolvePresentedCheck(check.presentationId)),
            )
            val resolved = assertIs<GameSessionUiState.Ready>(session.state.value)

            assertEquals(1, resolved.presentation.lastSequence)
            assertTrue(resolved.presentation.timeline.single().summary.contains("检定"))
            assertIs<SessionReplayResult.Success>(session.replay())
            assertEquals(resolved.presentation, assertIs<GameSessionUiState.Ready>(session.state.value).presentation)
        }
    }

    private fun resource(path: String): String =
        assertNotNull(javaClass.classLoader.getResource(path), "Missing resource $path").readText()
}
