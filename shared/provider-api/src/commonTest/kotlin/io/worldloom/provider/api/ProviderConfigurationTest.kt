package io.worldloom.provider.api

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ProviderConfigurationTest {
    @Test
    fun selectsTestsAndDiscoversThroughRegisteredAdapter() = runTest {
        val adapter = FakeConfigurableAdapter()
        val center = ProviderConfigurationCenter(
            adapters = listOf(adapter),
            store = InMemoryProviderConfigurationStore(),
        )
        val configuration = configuration()

        center.upsert(configuration)
        center.select(configuration.id)

        assertEquals(configuration.id, center.selectedConfigurationId())
        assertIs<ProviderConnectionTestResult.Connected>(center.test(configuration.id))
        val models = assertIs<ProviderModelDiscoveryResult.Success>(center.discoverModels(configuration.id))
        assertEquals(listOf("model-fast", "model-deep"), models.models.map(ProviderModelDescriptor::id))
        val selected = assertIs<SelectedProviderResult.Success>(center.selectedProvider())
        assertEquals(configuration, selected.configuration)
    }

    @Test
    fun removingSelectedConfigurationClearsSelection() = runTest {
        val store = InMemoryProviderConfigurationStore()
        val center = ProviderConfigurationCenter(listOf(FakeConfigurableAdapter()), store)
        val configuration = configuration()
        center.upsert(configuration)
        center.select(configuration.id)

        center.remove(configuration.id)

        assertEquals(null, store.selected())
        assertIs<SelectedProviderResult.NotSelected>(center.selectedProvider())
    }

    @Test
    fun rejectsUnknownAdaptersAndUnsupportedTransport() = runTest {
        val center = ProviderConfigurationCenter(listOf(FakeConfigurableAdapter()), InMemoryProviderConfigurationStore())
        assertFailsWith<IllegalArgumentException> {
            configuration().copy(adapterId = "missing")
                .let { center.upsert(it) }
        }
        configuration().copy(baseUrl = "http://remote.example/v1")
        configuration().copy(baseUrl = "http://localhost:11434/v1")
        assertFailsWith<IllegalArgumentException> { configuration().copy(baseUrl = "ftp://provider.example/v1") }
    }

    private fun configuration(): ProviderConfiguration = ProviderConfiguration(
        id = ProviderConfigurationId("primary"),
        adapterId = "fake",
        displayName = "Primary",
        baseUrl = "https://provider.example/v1",
        modelId = "model-fast",
        credentialKey = "provider.primary.api-key",
    )
}

private class FakeConfigurableAdapter : ConfigurableProviderAdapter {
    override val adapterId: String = "fake"
    override val capabilities: ProviderCapabilities = CAPABILITIES

    override suspend fun test(configuration: ProviderConfiguration): ProviderConnectionTestResult =
        ProviderConnectionTestResult.Connected(CAPABILITIES, ProviderModelDescriptor(configuration.modelId))

    override suspend fun discoverModels(configuration: ProviderConfiguration): ProviderModelDiscoveryResult =
        ProviderModelDiscoveryResult.Success(
            listOf(ProviderModelDescriptor("model-fast"), ProviderModelDescriptor("model-deep")),
        )

    override fun create(configuration: ProviderConfiguration): LanguageModelProvider = object : LanguageModelProvider {
        override val capabilities: ProviderCapabilities = CAPABILITIES

        override suspend fun complete(request: ProviderRequest): ProviderResult =
            ProviderResult.Success(ProviderTurn("ok", usage = ProviderUsage.Zero))
    }

    private companion object {
        val CAPABILITIES = ProviderCapabilities(toolCalling = true, streaming = true, structuredOutput = true)
    }
}
