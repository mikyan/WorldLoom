package io.worldloom.application

import io.worldloom.definition.DefinitionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StaticWorldCatalogTest {
    @Test
    fun reportsSourceIndexWithoutAcceptingInvalidJson() {
        val result = assertIs<StaticWorldCatalogResult.Failure>(
            StaticWorldCatalog.fromJsonSources(listOf("{invalid")),
        )

        assertEquals(0, result.sourceIndex)
        assertEquals("definition.decode.invalid_json", result.code)
    }

    @Test
    fun emptyCatalogHasNoImplicitWorld() = runTest {
        val catalog = assertIs<StaticWorldCatalogResult.Success>(
            StaticWorldCatalog.fromJsonSources(emptyList()),
        ).catalog

        assertEquals(emptyList(), catalog.entries)
        assertEquals(null, catalog.load(DefinitionId("missing.world")))
    }
}
