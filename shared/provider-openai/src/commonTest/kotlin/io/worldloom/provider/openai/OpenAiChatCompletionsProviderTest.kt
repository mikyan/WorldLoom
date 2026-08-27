package io.worldloom.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.worldloom.platform.credentials.CredentialWriteResult
import io.worldloom.platform.credentials.SecretValue
import io.worldloom.platform.credentials.SessionCredentialVault
import io.worldloom.provider.api.ProviderMessage
import io.worldloom.provider.api.ProviderMessageRole
import io.worldloom.provider.api.ProviderRequest
import io.worldloom.provider.api.ProviderResult
import io.worldloom.provider.api.ProviderStreamEvent
import io.worldloom.provider.api.ProviderToolDefinition
import io.worldloom.provider.api.ProviderToolParameter
import io.worldloom.provider.api.ProviderToolValueType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiChatCompletionsProviderTest {
    @Test
    fun mapsNeutralMessagesToolsAndUsageWithoutPuttingTheKeyInTheBody() = runTest {
        var captured: HttpRequestData? = null
        val provider = provider { request ->
            captured = request
            jsonResponse(
                """
                {
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call-1",
                        "type": "function",
                        "function": {"name": "worldloom.tool.check.resolve", "arguments": "{\"profileId\":\"test.check\"}"}
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {"prompt_tokens": 100, "completion_tokens": 20}
                }
                """.trimIndent(),
            )
        }
        val request = ProviderRequest(
            messages = listOf(
                ProviderMessage(ProviderMessageRole.SYSTEM, "system"),
                ProviderMessage(ProviderMessageRole.USER, "check"),
            ),
            tools = listOf(checkTool()),
            maxOutputTokens = 256,
        )

        val success = assertIs<ProviderResult.Success>(provider.complete(request))

        assertEquals("worldloom.tool.check.resolve", success.turn.toolCalls.single().name)
        assertEquals(JsonPrimitive("test.check"), success.turn.toolCalls.single().arguments["profileId"])
        assertEquals(100, success.turn.usage.inputTokens)
        assertEquals(20, success.turn.usage.outputTokens)
        assertEquals(2, success.turn.usage.costMicrounits)
        val sent = checkNotNull(captured)
        assertEquals("Bearer test-secret-key", sent.headers[HttpHeaders.Authorization])
        val body = assertIs<TextContent>(sent.body).text
        assertFalse("test-secret-key" in body)
        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals("system", root["messages"]!!.jsonArray.first().jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(256, root["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        assertFalse("parallel_tool_calls" in root)
        val function = root["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject
        assertFalse("strict" in function)
        val parameter = function["parameters"]!!.jsonObject["properties"]!!.jsonObject["profileId"]!!.jsonObject
        assertEquals("test.check", parameter["enum"]!!.jsonArray.single().jsonPrimitive.content)
        val arrayParameter = function["parameters"]!!.jsonObject["properties"]!!.jsonObject["knowledgeIds"]!!.jsonObject
        assertEquals("array", arrayParameter["type"]!!.jsonPrimitive.content)
        assertEquals(
            "test.knowledge",
            arrayParameter["items"]!!.jsonObject["enum"]!!.jsonArray.single().jsonPrimitive.content,
        )
    }

    @Test
    fun streamsTextDeltasAndReadsTheFinalUsageChunk() = runTest {
        val provider = provider { _ ->
            sseResponse(
                """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"你"},"finish_reason":null}],"usage":null}

                data: {"choices":[{"index":0,"delta":{"content":"好"},"finish_reason":null}],"usage":null}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":null}

                data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":2}}

                data: [DONE]

                """.trimIndent(),
            )
        }
        val deltas = mutableListOf<String>()

        val success = assertIs<ProviderResult.Success>(
            provider.completeStreaming(textRequest()) { event ->
                if (event is ProviderStreamEvent.TextDelta) deltas += event.text
            },
        )

        assertEquals(listOf("你", "好"), deltas)
        assertEquals("你好", success.turn.text)
        assertEquals(7, success.turn.usage.inputTokens)
        assertEquals(2, success.turn.usage.outputTokens)
    }

    @Test
    fun reassemblesFragmentedStreamingToolCalls() = runTest {
        val provider = provider { _ ->
            sseResponse(
                """
                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-stream","type":"function","function":{"name":"worldloom.tool.check.resolve","arguments":"{\"profile"}}]},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"Id\":\"test.check\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """.trimIndent(),
            )
        }

        val success = assertIs<ProviderResult.Success>(provider.completeStreaming(textRequest()) {})

        val call = success.turn.toolCalls.single()
        assertEquals("call-stream", call.id)
        assertEquals("worldloom.tool.check.resolve", call.name)
        assertEquals(JsonPrimitive("test.check"), call.arguments["profileId"])
    }

    @Test
    fun acceptsCompatibleStreamThatEndsWithFinishReasonWithoutDoneMarker() = runTest {
        var captured: HttpRequestData? = null
        val provider = provider { request ->
            captured = request
            sseResponse(
                """
                data: {"choices":[{"index":0,"delta":{"content":"兼容"},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"content":"成功"},"finish_reason":"stop"}]}

                """.trimIndent(),
            )
        }

        val success = assertIs<ProviderResult.Success>(provider.completeStreaming(textRequest()) {})

        assertEquals("兼容成功", success.turn.text)
        val root = Json.parseToJsonElement(assertIs<TextContent>(checkNotNull(captured).body).text).jsonObject
        assertFalse("stream_options" in root)
    }

    @Test
    fun acceptsJsonWhenCompatibleEndpointIgnoresStreaming() = runTest {
        val deltas = mutableListOf<String>()
        val provider = provider { _ ->
            jsonResponse(
                """
                {
                  "choices": [{"message": {"role": "assistant", "content": "非流式兼容"}}],
                  "usage": {"prompt_tokens": 4, "completion_tokens": 2}
                }
                """.trimIndent(),
            )
        }

        val success = assertIs<ProviderResult.Success>(
            provider.completeStreaming(textRequest()) { event ->
                if (event is ProviderStreamEvent.TextDelta) deltas += event.text
            },
        )

        assertEquals("非流式兼容", success.turn.text)
        assertEquals(listOf("非流式兼容"), deltas)
    }

    @Test
    fun retriesWithoutStreamingWhenCompatibleEndpointRejectsStreaming() = runTest {
        var calls = 0
        val deltas = mutableListOf<String>()
        val provider = provider { request ->
            calls += 1
            val root = Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject
            if (root["stream"]!!.jsonPrimitive.content.toBoolean()) {
                respond(
                    content = ByteReadChannel("""{"error":{"message":"streaming is unsupported"}}"""),
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                jsonResponse(
                    """
                    {
                      "choices": [{"message": {"role": "assistant", "content": "回退成功"}}],
                      "usage": {"prompt_tokens": 4, "completion_tokens": 2}
                    }
                    """.trimIndent(),
                )
            }
        }

        val success = assertIs<ProviderResult.Success>(
            provider.completeStreaming(textRequest()) { event ->
                if (event is ProviderStreamEvent.TextDelta) deltas += event.text
            },
        )

        assertEquals(2, calls)
        assertEquals("回退成功", success.turn.text)
        assertEquals(listOf("回退成功"), deltas)
    }

    @Test
    fun missingCredentialAndHttpFailuresAreMappedWithoutNetworkBodyLeakage() = runTest {
        var called = false
        val emptyVault = SessionCredentialVault()
        val noCredentialProvider = OpenAiChatCompletionsProvider(
            HttpClient(MockEngine { called = true; jsonResponse("{}") }),
            emptyVault,
            testConfig(),
        )

        val missing = assertIs<ProviderResult.Failure>(noCredentialProvider.complete(textRequest()))
        assertEquals(io.worldloom.provider.api.ProviderFailureCode.AUTHENTICATION, missing.code)
        assertFalse(called)

        val unavailable = provider { _ ->
            respond(
                content = ByteReadChannel("sensitive upstream diagnostic"),
                status = HttpStatusCode.ServiceUnavailable,
            )
        }
        val failure = assertIs<ProviderResult.Failure>(unavailable.complete(textRequest()))
        assertEquals(io.worldloom.provider.api.ProviderFailureCode.UNAVAILABLE, failure.code)
        assertFalse("sensitive" in failure.message)
    }

    private suspend fun provider(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): OpenAiChatCompletionsProvider {
        val vault = SessionCredentialVault()
        assertIs<CredentialWriteResult.Success>(vault.write(OPENAI_API_KEY, SecretValue.create("test-secret-key")))
        val engine = MockEngine { request -> handler.invoke(this, request) }
        return OpenAiChatCompletionsProvider(HttpClient(engine), vault, testConfig())
    }

    private fun testConfig(): OpenAiChatCompletionsConfig = OpenAiChatCompletionsConfig(
        model = "test-model",
        baseUrl = "http://localhost/v1",
        inputCostMicrounitsPerMillionTokens = 10_000,
        outputCostMicrounitsPerMillionTokens = 50_000,
        allowInsecureTransport = true,
    )

    private fun textRequest(): ProviderRequest = ProviderRequest(
        messages = listOf(ProviderMessage(ProviderMessageRole.USER, "hello")),
        maxOutputTokens = 32,
    )

    private fun checkTool(): ProviderToolDefinition = ProviderToolDefinition(
        name = "worldloom.tool.check.resolve",
        description = "Resolve a check.",
        parameters = listOf(
            ProviderToolParameter(
                name = "profileId",
                description = "Check profile.",
                type = ProviderToolValueType.STRING,
                allowedValues = listOf("test.check"),
            ),
            ProviderToolParameter(
                name = "knowledgeIds",
                description = "Optional knowledge identifiers.",
                type = ProviderToolValueType.STRING_ARRAY,
                required = false,
                allowedValues = listOf("test.knowledge"),
            ),
        ),
    )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.jsonResponse(content: String): HttpResponseData =
        respond(
            content = ByteReadChannel(content),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.sseResponse(content: String): HttpResponseData =
        respond(
            content = ByteReadChannel(content),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
        )
}
