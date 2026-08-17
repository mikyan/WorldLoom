package io.worldloom.app.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication
import io.worldloom.application.DefaultGameSession
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.ui.game.WorldloomApp

private val CONTRACT_WORLD_RESOURCES = listOf(
    "war-survival/world.json",
    "station-ai/world.json",
)

fun main() {
    val catalog = loadContractWorldCatalog()
    val session = DefaultGameSession(catalog)

    singleWindowApplication(
        title = "Worldloom / 织境",
        state = WindowState(width = 1000.dp, height = 720.dp),
    ) {
        WorldloomApp(session)
    }
}

private fun loadContractWorldCatalog(): StaticWorldCatalog {
    val classLoader = checkNotNull(Thread.currentThread().contextClassLoader) {
        "Desktop resource class loader is unavailable"
    }
    val sources = CONTRACT_WORLD_RESOURCES.map { path ->
        checkNotNull(classLoader.getResource(path)) { "Missing contract world resource: $path" }.readText()
    }
    return when (val result = StaticWorldCatalog.fromJsonSources(sources)) {
        is StaticWorldCatalogResult.Success -> result.catalog
        is StaticWorldCatalogResult.Failure -> error(
            "Invalid contract world resource at index ${result.sourceIndex}: ${result.message}",
        )
    }
}
