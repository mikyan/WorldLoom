package io.worldloom.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.worldloom.platform.credentials.CredentialKey
import io.worldloom.platform.credentials.SecretValue
import io.worldloom.platform.credentials.SessionCredentialVault
import io.worldloom.provider.api.ProviderConfiguration
import io.worldloom.provider.api.ProviderConfigurationId
import io.worldloom.provider.api.ProviderConnectionTestResult
import io.worldloom.provider.api.ProviderFailureCode
import io.worldloom.provider.api.ProviderModelDiscoveryResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenAiConfigurableAdapterTest {
    @Test
    fun discoversModelsUsingCredentialFromVault() = runTest {
        val vault = SessionCredentialVault()
        vault.write(CredentialKey("openai.test"), SecretValue.create("test-secret"))
        val engine = MockEngine { request ->
            assertEquals("Bearer test-secret", request.headers[HttpHeaders.Authorization])
            respond(
                content = """{"data":[{"id":"gpt-b"},{"id":"gpt-a"},{"id":"gpt-a"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine)
        val adapter = OpenAiConfigurableAdapter(client, vault)

        val discovery = assertIs<ProviderModelDiscoveryResult.Success>(adapter.discoverModels(configuration()))
        assertEquals(listOf("gpt-a", "gpt-b"), discovery.models.map { it.id })
        assertIs<ProviderConnectionTestResult.Connected>(adapter.test(configuration()))
        client.close()
    }

    @Test
    fun mapsMissingCredentialWithoutMakingARequest() = runTest {
        val client = HttpClient(MockEngine { error("HTTP must not be called") })
        val adapter = OpenAiConfigurableAdapter(client, SessionCredentialVault())

        val result = assertIs<ProviderModelDiscoveryResult.Failure>(adapter.discoverModels(configuration()))
        assertEquals(ProviderFailureCode.AUTHENTICATION, result.code)
        client.close()
    }

    private fun configuration() = ProviderConfiguration(
        id = ProviderConfigurationId("test"),
        adapterId = OPENAI_ADAPTER_ID,
        displayName = "Test",
        baseUrl = "https://provider.example/v1",
        modelId = "gpt-a",
        credentialKey = "openai.test",
    )
}
