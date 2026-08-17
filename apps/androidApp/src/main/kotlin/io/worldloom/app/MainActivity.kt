package io.worldloom.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.worldloom.application.DefaultGameSession
import io.worldloom.application.StaticWorldCatalog
import io.worldloom.application.StaticWorldCatalogResult
import io.worldloom.ui.game.WorldloomApp

private val CONTRACT_WORLD_ASSETS = listOf(
    "war-survival/world.json",
    "station-ai/world.json",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = DefaultGameSession(loadContractWorldCatalog())
        setContent {
            WorldloomApp(session)
        }
    }

    private fun loadContractWorldCatalog(): StaticWorldCatalog {
        val sources = CONTRACT_WORLD_ASSETS.map { path ->
            assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return when (val result = StaticWorldCatalog.fromJsonSources(sources)) {
            is StaticWorldCatalogResult.Success -> result.catalog
            is StaticWorldCatalogResult.Failure -> error(
                "Invalid contract world asset at index ${result.sourceIndex}: ${result.message}",
            )
        }
    }
}
