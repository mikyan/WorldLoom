package io.worldloom.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.persistence.db.WorldloomDatabase
import io.worldloom.provider.api.ProviderConfiguration
import io.worldloom.provider.api.ProviderConfigurationId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlDelightProviderConfigurationStoreTest {
    @Test
    fun nonSecretProviderSelectionSurvivesStoreRecreation() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val initial = configuration("primary", "model-a")
        val first = SqlDelightProviderConfigurationStore(database, initial)
        assertEquals(ProviderConfigurationId("primary"), first.selected())
        first.put(initial.copy(modelId = "model-b"))
        first.put(configuration("secondary", "model-c"))
        first.select(ProviderConfigurationId("secondary"))

        val recreated = SqlDelightProviderConfigurationStore(WorldloomDatabase(driver))

        assertEquals(listOf("model-b", "model-c"), recreated.list().map { it.modelId })
        assertEquals(ProviderConfigurationId("secondary"), recreated.selected())
        driver.close()
    }

    @Test
    fun seedsMultipleSourcesWithoutOverwritingExistingConfigurationOrSelection() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorldloomDatabase.Schema.create(driver).value
        val database = WorldloomDatabase(driver)
        val primary = configuration("primary", "model-a")
        val secondary = configuration("secondary", "model-b")
        val first = SqlDelightProviderConfigurationStore(database, listOf(primary, secondary))
        first.put(primary.copy(modelId = "user-model"))
        first.select(secondary.id)

        val recreated = SqlDelightProviderConfigurationStore(
            WorldloomDatabase(driver),
            listOf(primary, secondary, configuration("third", "model-c")),
        )

        assertEquals(
            listOf("user-model", "model-b", "model-c"),
            recreated.list().map { it.modelId },
        )
        assertEquals(secondary.id, recreated.selected())
        driver.close()
    }

    private fun configuration(id: String, model: String) = ProviderConfiguration(
        id = ProviderConfigurationId(id),
        adapterId = "test.adapter",
        displayName = id,
        baseUrl = "https://provider.example/v1",
        modelId = model,
        credentialKey = "provider.$id.api-key",
    )
}
