package io.worldloom.provider.openai

import io.worldloom.platform.credentials.CredentialKey
import io.worldloom.provider.api.ProviderConfiguration
import io.worldloom.provider.api.ProviderConfigurationId

enum class OpenAiSubscriptionSourceKind {
    OPENCODE_GO,
    MIMO_TOKEN_PLAN_CN,
    CUSTOM,
}

/**
 * A user-facing OpenAI-compatible subscription preset. Presets contain no secrets: [credentialKey]
 * is only a stable reference into the platform credential vault.
 */
data class OpenAiSubscriptionSource(
    val kind: OpenAiSubscriptionSourceKind,
    val configurationId: ProviderConfigurationId,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val credentialKey: CredentialKey,
    val customEndpoint: Boolean,
) {
    init {
        require(displayName.isNotBlank()) { "Subscription source display name must not be blank" }
        require(baseUrl.isNotBlank()) { "Subscription source base URL must not be blank" }
        require(modelId.isNotBlank()) { "Subscription source model must not be blank" }
    }

    fun defaultConfiguration(): ProviderConfiguration = ProviderConfiguration(
        id = configurationId,
        adapterId = OPENAI_ADAPTER_ID,
        displayName = displayName,
        baseUrl = baseUrl,
        modelId = modelId,
        credentialKey = credentialKey.value,
    )
}

object OpenAiSubscriptionSources {
    val OpenCodeGo = OpenAiSubscriptionSource(
        kind = OpenAiSubscriptionSourceKind.OPENCODE_GO,
        configurationId = ProviderConfigurationId("openai.opencode-go"),
        displayName = "OpenCode Go",
        baseUrl = "https://opencode.ai/zen/go/v1",
        modelId = "glm-5.3-flash",
        credentialKey = CredentialKey("openai.opencode-go.api-key"),
        customEndpoint = false,
    )

    val MiMoTokenPlanCn = OpenAiSubscriptionSource(
        kind = OpenAiSubscriptionSourceKind.MIMO_TOKEN_PLAN_CN,
        configurationId = ProviderConfigurationId("openai.mimo-token-plan-cn"),
        displayName = "MiMo Token Plan CN",
        baseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
        modelId = "mimo-v2.5",
        credentialKey = CredentialKey("openai.mimo-token-plan-cn.api-key"),
        customEndpoint = false,
    )

    /** Keeps the previous built-in OpenAI configuration and vault identifiers migration-free. */
    val Custom = OpenAiSubscriptionSource(
        kind = OpenAiSubscriptionSourceKind.CUSTOM,
        configurationId = ProviderConfigurationId("openai.primary"),
        displayName = "自定义 OpenAI-compatible",
        baseUrl = "https://api.openai.com/v1",
        modelId = "gpt-5.6-luna",
        credentialKey = OPENAI_API_KEY,
        customEndpoint = true,
    )

    val all: List<OpenAiSubscriptionSource> = listOf(OpenCodeGo, MiMoTokenPlanCn, Custom)
}
