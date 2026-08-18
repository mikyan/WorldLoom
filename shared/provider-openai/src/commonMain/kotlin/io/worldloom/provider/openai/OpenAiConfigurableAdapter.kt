package io.worldloom.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.worldloom.platform.credentials.CredentialKey
import io.worldloom.platform.credentials.CredentialReadResult
import io.worldloom.platform.credentials.CredentialVault
import io.worldloom.provider.api.ConfigurableProviderAdapter
import io.worldloom.provider.api.LanguageModelProvider
import io.worldloom.provider.api.ProviderCapabilities
import io.worldloom.provider.api.ProviderConfiguration
import io.worldloom.provider.api.ProviderConnectionTestResult
import io.worldloom.provider.api.ProviderFailureCode
import io.worldloom.provider.api.ProviderModelDescriptor
import io.worldloom.provider.api.ProviderModelDiscoveryResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val OPENAI_ADAPTER_ID: String = "openai-chat-completions"

/** Management and factory boundary for OpenAI-compatible Chat Completions endpoints. */
class OpenAiConfigurableAdapter(
    private val httpClient: HttpClient,
    private val credentialVault: CredentialVault,
) : ConfigurableProviderAdapter {
    override val adapterId: String = OPENAI_ADAPTER_ID
    override val capabilities: ProviderCapabilities = CAPABILITIES

    override suspend fun test(configuration: ProviderConfiguration): ProviderConnectionTestResult =
        when (val result = discoverModels(configuration)) {
            is ProviderModelDiscoveryResult.Success -> ProviderConnectionTestResult.Connected(
                capabilities = CAPABILITIES,
                model = result.models.firstOrNull { it.id == configuration.modelId },
            )
            is ProviderModelDiscoveryResult.Failure -> ProviderConnectionTestResult.Failed(
                code = result.code,
                message = result.message,
                retryable = result.retryable,
            )
        }

    override suspend fun discoverModels(configuration: ProviderConfiguration): ProviderModelDiscoveryResult =
        withCredential(configuration) { credential ->
            try {
                val response = httpClient.get("${configuration.baseUrl.trimEnd('/')}/models") {
                    bearerAuth(credential)
                }
                if (response.status != HttpStatusCode.OK) {
                    return@withCredential failureForStatus(response.status)
                }
                val models = try {
                    Json.parseToJsonElement(response.bodyAsText())
                        .jsonObject.getValue("data")
                        .jsonArray
                        .map { element -> element.jsonObject.getValue("id").jsonPrimitive.content }
                        .distinct()
                        .sorted()
                        .map(::ProviderModelDescriptor)
                } catch (_: Exception) {
                    return@withCredential ProviderModelDiscoveryResult.Failure(
                        ProviderFailureCode.INVALID_RESPONSE,
                        "Provider model discovery returned an invalid response",
                        retryable = false,
                    )
                }
                ProviderModelDiscoveryResult.Success(models)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProviderModelDiscoveryResult.Failure(
                    ProviderFailureCode.NETWORK,
                    "Provider model discovery failed",
                    retryable = true,
                )
            }
        }

    override fun create(configuration: ProviderConfiguration): LanguageModelProvider =
        OpenAiChatCompletionsProvider(
            httpClient = httpClient,
            credentialVault = credentialVault,
            config = OpenAiChatCompletionsConfig(
                model = configuration.modelId,
                baseUrl = configuration.baseUrl,
                inputCostMicrounitsPerMillionTokens = configuration.inputCostMicrounitsPerMillionTokens,
                outputCostMicrounitsPerMillionTokens = configuration.outputCostMicrounitsPerMillionTokens,
                allowInsecureTransport = configuration.allowInsecureLocalTransport,
            ),
            credentialKey = CredentialKey(configuration.credentialKey),
        )

    private suspend fun withCredential(
        configuration: ProviderConfiguration,
        block: suspend (String) -> ProviderModelDiscoveryResult,
    ): ProviderModelDiscoveryResult = when (
        val credential = credentialVault.read(CredentialKey(configuration.credentialKey))
    ) {
        is CredentialReadResult.Success -> credential.secret.access(block)
        is CredentialReadResult.Failure -> ProviderModelDiscoveryResult.Failure(
            ProviderFailureCode.AUTHENTICATION,
            "Provider credential is not configured",
            retryable = false,
        )
    }

    private fun failureForStatus(status: HttpStatusCode): ProviderModelDiscoveryResult.Failure = when (status) {
        HttpStatusCode.Unauthorized,
        HttpStatusCode.Forbidden,
        -> ProviderModelDiscoveryResult.Failure(
            ProviderFailureCode.AUTHENTICATION,
            "Provider rejected the configured credential",
            retryable = false,
        )
        HttpStatusCode.TooManyRequests -> ProviderModelDiscoveryResult.Failure(
            ProviderFailureCode.RATE_LIMITED,
            "Provider model discovery was rate limited",
            retryable = true,
        )
        else -> ProviderModelDiscoveryResult.Failure(
            if (status.value >= 500) ProviderFailureCode.UNAVAILABLE else ProviderFailureCode.INVALID_REQUEST,
            "Provider model discovery failed with HTTP ${status.value}",
            retryable = status.value >= 500,
        )
    }

    private companion object {
        val CAPABILITIES = ProviderCapabilities(
            toolCalling = true,
            streaming = true,
            structuredOutput = false,
        )
    }
}
