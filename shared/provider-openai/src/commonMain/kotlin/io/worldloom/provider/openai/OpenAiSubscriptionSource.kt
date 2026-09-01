package io.worldloom.provider.openai

import io.worldloom.platform.credentials.CredentialKey
import io.worldloom.provider.api.ProviderConfiguration
import io.worldloom.provider.api.ProviderConfigurationId

enum class OpenAiSubscriptionSourceKind {
    OPENCODE_GO,
    MIMO_TOKEN_PLAN_CN,
    OPENAI,
    OPENROUTER,
    DEEPSEEK,
    GROQ,
    MISTRAL,
    SILICONFLOW,
    XAI,
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
    val description: String,
) {
    init {
        require(displayName.isNotBlank()) { "Subscription source display name must not be blank" }
        require(baseUrl.isNotBlank()) { "Subscription source base URL must not be blank" }
        require(modelId.isNotBlank()) { "Subscription source model must not be blank" }
        require(description.isNotBlank()) { "Subscription source description must not be blank" }
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
        description = "OpenCode Go 订阅，模型列表从服务端实时获取。",
    )

    val MiMoTokenPlanCn = OpenAiSubscriptionSource(
        kind = OpenAiSubscriptionSourceKind.MIMO_TOKEN_PLAN_CN,
        configurationId = ProviderConfigurationId("openai.mimo-token-plan-cn"),
        displayName = "MiMo Token Plan CN",
        baseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
        modelId = "mimo-v2.5",
        credentialKey = CredentialKey("openai.mimo-token-plan-cn.api-key"),
        customEndpoint = false,
        description = "小米 MiMo Token Plan 中国区订阅，模型列表从服务端实时获取。",
    )

    val OpenAi = OpenAiSubscriptionSource(
        kind = OpenAiSubscriptionSourceKind.OPENAI,
        configurationId = ProviderConfigurationId("openai.official"),
        displayName = "OpenAI API",
        baseUrl = "https://api.openai.com/v1",
        modelId = "gpt-5.6-luna",
        credentialKey = CredentialKey("openai.official.api-key"),
        customEndpoint = false,
        description = "OpenAI 官方 API；需要单独的 API Key，不使用 ChatGPT 订阅登录。",
    )

    val OpenRouter = OpenAiSubscriptionSource(
        kind = OpenAiSubscriptionSourceKind.OPENROUTER,
        configurationId = ProviderConfigurationId("openai.openrouter"),
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        modelId = "openrouter/auto",
        credentialKey = CredentialKey("openai.openrouter.api-key"),
        customEndpoint = false,
        description = "OpenRouter 聚合服务；可从账户可用模型中选择。",
    )

    val DeepSeek = OpenAiSubscriptionSource(
        kind = OpenAiSubscriptionSourceKind.DEEPSEEK,
        configurationId = ProviderConfigurationId("openai.deepseek"),
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        modelId = "deepseek-v4-flash",
        credentialKey = CredentialKey("openai.deepseek.api-key"),
        customEndpoint = false,
        description = "DeepSeek 官方 OpenAI-compatible API。",
    )

    val Groq = OpenAiSubscriptionSource(
        kind = OpenAiSubscriptionSourceKind.GROQ,
        configurationId = ProviderConfigurationId("openai.groq"),
        displayName = "GroqCloud",
        baseUrl = "https://api.groq.com/openai/v1",
        modelId = "openai/gpt-oss-120b",
        credentialKey = CredentialKey("openai.groq.api-key"),
        customEndpoint = false,
        description = "GroqCloud OpenAI-compatible API。",
    )

    val Mistral = OpenAiSubscriptionSource(
        kind = OpenAiSubscriptionSourceKind.MISTRAL,
        configurationId = ProviderConfigurationId("openai.mistral"),
        displayName = "Mistral AI",
        baseUrl = "https://api.mistral.ai/v1",
        modelId = "mistral-large-latest",
        credentialKey = CredentialKey("openai.mistral.api-key"),
        customEndpoint = false,
        description = "Mistral AI 官方 Chat Completions API。",
    )

    val SiliconFlow = OpenAiSubscriptionSource(
        kind = OpenAiSubscriptionSourceKind.SILICONFLOW,
        configurationId = ProviderConfigurationId("openai.siliconflow-cn"),
        displayName = "SiliconFlow 中国站",
        baseUrl = "https://api.siliconflow.cn/v1",
        modelId = "Qwen/Qwen3-235B-A22B-Instruct-2507",
        credentialKey = CredentialKey("openai.siliconflow-cn.api-key"),
        customEndpoint = false,
        description = "硅基流动中国站 OpenAI-compatible API。",
    )

    val XAi = OpenAiSubscriptionSource(
        kind = OpenAiSubscriptionSourceKind.XAI,
        configurationId = ProviderConfigurationId("openai.xai"),
        displayName = "xAI",
        baseUrl = "https://api.x.ai/v1",
        modelId = "grok-4.6",
        credentialKey = CredentialKey("openai.xai.api-key"),
        customEndpoint = false,
        description = "xAI 官方 API。",
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
        description = "填写自有 OpenAI-compatible 地址；模型列表发现取决于服务是否实现 /models。",
    )

    val all: List<OpenAiSubscriptionSource> = listOf(
        OpenCodeGo,
        MiMoTokenPlanCn,
        OpenAi,
        OpenRouter,
        DeepSeek,
        Groq,
        Mistral,
        SiliconFlow,
        XAi,
        Custom,
    )
}
