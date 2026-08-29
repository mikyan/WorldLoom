package io.worldloom.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.worldloom.platform.credentials.CredentialKey
import io.worldloom.platform.credentials.SecretValue
import io.worldloom.platform.credentials.SessionCredentialVault
import io.worldloom.provider.api.ProviderConfiguration
import io.worldloom.provider.api.ProviderConfigurationId
import io.worldloom.provider.api.ProviderConnectionTestResult
import io.worldloom.provider.api.ProviderMessage
import io.worldloom.provider.api.ProviderMessageRole
import io.worldloom.provider.api.ProviderRequest
import io.worldloom.provider.api.ProviderResult
import io.worldloom.provider.api.ProviderToolCall
import io.worldloom.provider.api.ProviderToolDefinition
import io.worldloom.provider.api.ProviderToolParameter
import io.worldloom.provider.api.ProviderToolValueType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OpenAiMiMoCompatibilityTest {
    @Test
    fun disablesThinkingAndRoundTripsNamespacedToolCallsForMimo() = runTest {
        var capturedBody: JsonObject? = null
        val fixture = provider(miMoConfig()) { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(MIMO_CHAT_COMPLETIONS_URL, request.url.toString())
            assertEquals("Bearer test-secret", request.headers[HttpHeaders.Authorization])
            val root = request.jsonBody()
            capturedBody = root
            val wireName = root.declaredToolName()
            jsonResponse(
                """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call-1",
                        "type": "function",
                        "function": {
                          "name": "$wireName",
                          "arguments": "{\"profileId\":\"test.check\"}"
                        }
                      }]
                    }
                  }],
                  "usage": {"prompt_tokens": 4, "completion_tokens": 2}
                }
                """.trimIndent(),
            )
        }

        try {
            val success = assertIs<ProviderResult.Success>(fixture.provider.complete(toolRequest()))
            val body = checkNotNull(capturedBody)
            val wireName = body.declaredToolName()

            assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("mimo-v2.5", body["model"]!!.jsonPrimitive.content)
            assertEquals(256, body["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
            assertTrue(OPENAI_TOOL_NAME_PATTERN.matches(wireName))
            assertNotEquals(LOGICAL_TOOL_NAME, wireName)
            assertEquals(LOGICAL_TOOL_NAME, success.turn.toolCalls.single().name)
            assertEquals(
                "test.check",
                success.turn.toolCalls.single().arguments["profileId"]!!.jsonPrimitive.content,
            )
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun usesTheSameWireNameForToolDeclarationsAndAssistantHistory() = runTest {
        var declaredName: String? = null
        var historyName: String? = null
        val fixture = provider(miMoConfig()) { request ->
            val root = request.jsonBody()
            declaredName = root.declaredToolName()
            historyName = root["messages"]!!
                .jsonArray[1]
                .jsonObject["tool_calls"]!!
                .jsonArray.single()
                .jsonObject["function"]!!
                .jsonObject["name"]!!
                .jsonPrimitive.content
            jsonResponse(
                """
                {
                  "choices": [{"message": {"role": "assistant", "content": "OK"}}],
                  "usage": {"prompt_tokens": 8, "completion_tokens": 1}
                }
                """.trimIndent(),
            )
        }
        val priorCall = ProviderToolCall(
            id = "call-prior",
            name = LOGICAL_TOOL_NAME,
            arguments = buildJsonObject { put("profileId", "test.check") },
        )
        val request = ProviderRequest(
            messages = listOf(
                ProviderMessage(ProviderMessageRole.USER, "Use the check tool."),
                ProviderMessage(ProviderMessageRole.ASSISTANT, toolCalls = listOf(priorCall)),
                ProviderMessage(
                    role = ProviderMessageRole.TOOL,
                    content = "{\"accepted\":true}",
                    toolCallId = priorCall.id,
                    toolName = priorCall.name,
                ),
            ),
            tools = listOf(checkTool()),
            maxOutputTokens = 64,
        )

        try {
            assertIs<ProviderResult.Success>(fixture.provider.complete(request))
            assertEquals(declaredName, historyName)
            assertTrue(OPENAI_TOOL_NAME_PATTERN.matches(checkNotNull(declaredName)))
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun decodesMappedToolNamesFromMimoStreams() = runTest {
        var capturedBody: JsonObject? = null
        val fixture = provider(miMoConfig()) { request ->
            val root = request.jsonBody()
            capturedBody = root
            val wireName = root.declaredToolName()
            sseResponse(
                """
                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-stream","type":"function","function":{"name":"$wireName","arguments":"{\"profileId\":\"test.check\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """.trimIndent(),
            )
        }

        try {
            val success = assertIs<ProviderResult.Success>(fixture.provider.completeStreaming(toolRequest()) {})
            val body = checkNotNull(capturedBody)

            assertTrue(body["stream"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(LOGICAL_TOOL_NAME, success.turn.toolCalls.single().name)
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun leavesThinkingUnspecifiedForNonMimoModels() = runTest {
        var capturedBody: JsonObject? = null
        val fixture = provider(
            OpenAiChatCompletionsConfig(
                model = "generic-model",
                baseUrl = "http://localhost/v1",
                allowInsecureTransport = true,
            ),
        ) { request ->
            capturedBody = request.jsonBody()
            jsonResponse(
                """
                {
                  "choices": [{"message": {"role": "assistant", "content": "OK"}}],
                  "usage": {"prompt_tokens": 1, "completion_tokens": 1}
                }
                """.trimIndent(),
            )
        }

        try {
            assertIs<ProviderResult.Success>(fixture.provider.complete(textRequest()))
            assertFalse("thinking" in checkNotNull(capturedBody))
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun connectionTestUsesAVisibleOutputBudgetAndMimoCompatibilityMode() = runTest {
        var capturedBody: JsonObject? = null
        val vault = SessionCredentialVault()
        vault.write(CredentialKey("openai.mimo-test"), SecretValue.create("test-secret"))
        val client = HttpClient(MockEngine { request ->
            capturedBody = request.jsonBody()
            jsonResponse(
                """
                {
                  "choices": [{"message": {"role": "assistant", "content": "OK"}}],
                  "usage": {"prompt_tokens": 6, "completion_tokens": 1}
                }
                """.trimIndent(),
            )
        })
        val adapter = OpenAiConfigurableAdapter(client, vault)
        val configuration = ProviderConfiguration(
            id = ProviderConfigurationId("mimo.test"),
            adapterId = OPENAI_ADAPTER_ID,
            displayName = "MiMo",
            baseUrl = MIMO_BASE_URL,
            modelId = "mimo-v2.5",
            credentialKey = "openai.mimo-test",
        )

        try {
            assertIs<ProviderConnectionTestResult.Connected>(adapter.test(configuration))
            val body = checkNotNull(capturedBody)
            assertEquals(64, body["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
            assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        } finally {
            client.close()
        }
    }

    @Test
    fun producesUniqueCompatibleNamesForInvalidAndLongToolIds() {
        val logicalNames = listOf(
            "worldloom.tool.check.resolve",
            "worldloom/tool/check/resolve",
            "valid_name",
            "worldloom." + "very-long-segment-".repeat(8),
        )
        val request = ProviderRequest(
            messages = listOf(ProviderMessage(ProviderMessageRole.USER, "test")),
            tools = logicalNames.map { name -> ProviderToolDefinition(name, "Test tool", emptyList()) },
            maxOutputTokens = 32,
        )
        val codec = OpenAiToolNameCodec.from(request)
        val wireNames = logicalNames.map(codec::encode)

        assertEquals(logicalNames.size, wireNames.toSet().size)
        assertTrue(wireNames.all(OPENAI_TOOL_NAME_PATTERN::matches))
        assertEquals("valid_name", codec.encode("valid_name"))
        logicalNames.zip(wireNames).forEach { (logical, wire) ->
            assertEquals(logical, codec.decode(wire))
        }
    }

    private suspend fun provider(
        config: OpenAiChatCompletionsConfig,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ProviderFixture {
        val vault = SessionCredentialVault()
        vault.write(OPENAI_API_KEY, SecretValue.create("test-secret"))
        val client = HttpClient(MockEngine { request -> handler.invoke(this, request) })
        return ProviderFixture(OpenAiChatCompletionsProvider(client, vault, config), client)
    }

    private fun miMoConfig() = OpenAiChatCompletionsConfig(
        model = "mimo-v2.5",
        baseUrl = MIMO_BASE_URL,
    )

    private fun toolRequest() = ProviderRequest(
        messages = listOf(ProviderMessage(ProviderMessageRole.USER, "Resolve the check.")),
        tools = listOf(checkTool()),
        maxOutputTokens = 256,
    )

    private fun textRequest() = ProviderRequest(
        messages = listOf(ProviderMessage(ProviderMessageRole.USER, "hello")),
        maxOutputTokens = 32,
    )

    private fun checkTool() = ProviderToolDefinition(
        name = LOGICAL_TOOL_NAME,
        description = "Resolve a deterministic check.",
        parameters = listOf(
            ProviderToolParameter(
                name = "profileId",
                description = "Check profile identifier.",
                type = ProviderToolValueType.STRING,
            ),
        ),
    )

    private fun HttpRequestData.jsonBody(): JsonObject =
        Json.parseToJsonElement((body as TextContent).text).jsonObject

    private fun JsonObject.declaredToolName(): String =
        this["tools"]!!
            .jsonArray.single()
            .jsonObject["function"]!!
            .jsonObject["name"]!!
            .jsonPrimitive.content

    private fun MockRequestHandleScope.jsonResponse(content: String): HttpResponseData = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun MockRequestHandleScope.sseResponse(content: String): HttpResponseData = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
    )

    private data class ProviderFixture(
        val provider: OpenAiChatCompletionsProvider,
        val client: HttpClient,
    )

    private companion object {
        const val LOGICAL_TOOL_NAME = "worldloom.tool.check.resolve"
        const val MIMO_BASE_URL = "https://token-plan-cn.xiaomimimo.com/v1"
        const val MIMO_CHAT_COMPLETIONS_URL = "$MIMO_BASE_URL/chat/completions"
        val OPENAI_TOOL_NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
