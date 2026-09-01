package io.worldloom.provider.openai

import io.worldloom.platform.credentials.CredentialConfiguration
import io.worldloom.platform.credentials.CredentialConfigurationState
import io.worldloom.platform.credentials.SessionCredentialVault
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiSubscriptionSourceTest {
    @Test
    fun presetsHaveStableDistinctConfigurationsAndCredentialReferences() {
        val sources = OpenAiSubscriptionSources.all
        val configurations = sources.map(OpenAiSubscriptionSource::defaultConfiguration)

        assertEquals(10, sources.size)
        assertEquals(sources.size, configurations.map { it.id }.distinct().size)
        assertEquals(sources.size, configurations.map { it.credentialKey }.distinct().size)
        assertTrue(sources.all { it.description.isNotBlank() })
        assertEquals("https://opencode.ai/zen/go/v1", OpenAiSubscriptionSources.OpenCodeGo.baseUrl)
        assertEquals("https://token-plan-cn.xiaomimimo.com/v1", OpenAiSubscriptionSources.MiMoTokenPlanCn.baseUrl)
        assertEquals("https://api.openai.com/v1", OpenAiSubscriptionSources.OpenAi.baseUrl)
        assertEquals("https://openrouter.ai/api/v1", OpenAiSubscriptionSources.OpenRouter.baseUrl)
        assertEquals("https://api.deepseek.com", OpenAiSubscriptionSources.DeepSeek.baseUrl)
        assertEquals("https://api.groq.com/openai/v1", OpenAiSubscriptionSources.Groq.baseUrl)
        assertEquals("https://api.mistral.ai/v1", OpenAiSubscriptionSources.Mistral.baseUrl)
        assertEquals("https://api.siliconflow.cn/v1", OpenAiSubscriptionSources.SiliconFlow.baseUrl)
        assertEquals("https://api.x.ai/v1", OpenAiSubscriptionSources.XAi.baseUrl)
        assertFalse(OpenAiSubscriptionSources.OpenCodeGo.customEndpoint)
        assertFalse(OpenAiSubscriptionSources.MiMoTokenPlanCn.customEndpoint)
        assertTrue(sources.filterNot { it === OpenAiSubscriptionSources.Custom }.all { !it.customEndpoint })
        assertTrue(OpenAiSubscriptionSources.Custom.customEndpoint)
        assertEquals("openai.primary", OpenAiSubscriptionSources.Custom.configurationId.value)
        assertEquals(OPENAI_API_KEY.value, OpenAiSubscriptionSources.Custom.credentialKey.value)
    }

    @Test
    fun configuringOneSubscriptionDoesNotConfigureAnother() = runTest {
        val vault = SessionCredentialVault()
        val openCode = CredentialConfiguration(vault, OpenAiSubscriptionSources.OpenCodeGo.credentialKey)
        val miMo = CredentialConfiguration(vault, OpenAiSubscriptionSources.MiMoTokenPlanCn.credentialKey)

        assertTrue(openCode.configure("open-code-secret"))
        miMo.refresh()

        assertIs<CredentialConfigurationState.Configured>(openCode.state.value)
        assertIs<CredentialConfigurationState.NotConfigured>(miMo.state.value)
    }
}
