package io.worldloom.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
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
import kotlin.test.assertTrue

class OpenAiConfigurableAdapterTest {
    @Test
    fun discoversModelsUsingCredentialFromVault() = runTest {
        val vault = SessionCredentialVault()
        vault.write(CredentialKey("openai.test"), SecretValue.create("test-secret"))
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
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
        client.close()
    }

    @Test
    fun discoversModelsFromBareArraysAndPreservesUsefulMetadata() = runTest {
        val vault = SessionCredentialVault()
        vault.write(CredentialKey("openai.test"), SecretValue.create("test-secret"))
        val engine = MockEngine {
            respond(
                content = """[{"id":"mistral-large","name":"Mistral Large","max_context_length":131072}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine)
        val adapter = OpenAiConfigurableAdapter(client, vault)

        val discovery = assertIs<ProviderModelDiscoveryResult.Success>(adapter.discoverModels(configuration()))

        assertEquals(1, discovery.models.size)
        assertEquals("mistral-large", discovery.models.single().id)
        assertEquals("Mistral Large", discovery.models.single().displayName)
        assertEquals(131_072L, discovery.models.single().contextWindowTokens)
        client.close()
    }

    @Test
    fun openCodeAndMiMoDiscoverThroughTheirModelsEndpoints() = runTest {
        val vault = SessionCredentialVault()
        val requestedUrls = mutableListOf<String>()
        listOf(OpenAiSubscriptionSources.OpenCodeGo, OpenAiSubscriptionSources.MiMoTokenPlanCn)
            .forEach { source ->
                vault.write(source.credentialKey, SecretValue.create("test-secret"))
            }
        val engine = MockEngine { request ->
            requestedUrls += request.url.toString()
            respond(
                content = """{"data":[{"id":"available-model"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine)
        val adapter = OpenAiConfigurableAdapter(client, vault)

        val openCode = adapter.discoverModels(OpenAiSubscriptionSources.OpenCodeGo.defaultConfiguration())
        val miMo = adapter.discoverModels(OpenAiSubscriptionSources.MiMoTokenPlanCn.defaultConfiguration())

        assertIs<ProviderModelDiscoveryResult.Success>(openCode)
        assertIs<ProviderModelDiscoveryResult.Success>(miMo)
        assertEquals(
            listOf(
                "https://opencode.ai/zen/go/v1/models",
                "https://token-plan-cn.xiaomimimo.com/v1/models",
            ),
            requestedUrls,
        )
        client.close()
    }

    @Test
    fun testsConfiguredModelWithoutUsingDiscoveryEndpoint() = runTest {
        val vault = SessionCredentialVault()
        vault.write(CredentialKey("openai.test"), SecretValue.create("test-secret"))
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://proxy.example/v1/chat/completions", request.url.toString())
            assertTrue((request.body as TextContent).text.contains("\"model\":\"gpt-a\""))
            respond(
                content = """{"choices":[{"message":{"role":"assistant","content":"OK"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine)
        val adapter = OpenAiConfigurableAdapter(client, vault)

        val result = assertIs<ProviderConnectionTestResult.Connected>(
            adapter.test(configuration().copy(baseUrl = "http://proxy.example/v1")),
        )
        assertEquals("gpt-a", result.model?.id)
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
