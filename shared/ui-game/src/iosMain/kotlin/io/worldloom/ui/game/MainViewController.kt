package io.worldloom.ui.game

import androidx.compose.ui.window.ComposeUIViewController
import io.worldloom.application.DefaultGameSession
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import platform.UIKit.UIViewController

fun MainViewController(worldSources: List<String>): UIViewController {
    val catalog = loadCatalog(worldSources)
    val session = DefaultGameSession(catalog)
    return ComposeUIViewController {
        WorldloomApp(session)
    }
}

private fun loadCatalog(worldSources: List<String>): StaticWorldCatalog =
    when (val result = StaticWorldCatalog.fromJsonSources(worldSources)) {
        is StaticWorldCatalogResult.Success -> result.catalog
        is StaticWorldCatalogResult.Failure -> error(
            "Invalid contract world resource at index ${result.sourceIndex}: ${result.message}",
        )
    }
