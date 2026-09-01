package io.worldloom.ui.game

import io.worldloom.provider.openai.OpenAiSubscriptionSources
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderSettingsConfigurationTest {
    @Test
    fun builtInSourceKeepsEndpointAndPersistsSelectedModel() {
        val source = OpenAiSubscriptionSources.MiMoTokenPlanCn
        val stored = source.defaultConfiguration().copy(
            modelId = "previous-model",
            maxOutputTokens = 8_192,
        )

        val updated = source.configurationWithSelection(
            storedConfiguration = stored,
            customBaseUrl = "https://untrusted.example/v1",
            modelId = "mimo-new-model",
        )

        assertEquals(source.baseUrl, updated.baseUrl)
        assertEquals("mimo-new-model", updated.modelId)
        assertEquals(8_192, updated.maxOutputTokens)
    }

    @Test
    fun customSourcePersistsEndpointAndSelectedModel() {
        val source = OpenAiSubscriptionSources.Custom

        val updated = source.configurationWithSelection(
            storedConfiguration = null,
            customBaseUrl = " https://gateway.example/v1/ ",
            modelId = " model-from-api ",
        )

        assertEquals("https://gateway.example/v1/", updated.baseUrl)
        assertEquals("model-from-api", updated.modelId)
    }
}
