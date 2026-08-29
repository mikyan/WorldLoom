package io.worldloom.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import io.worldloom.platform.credentials.CredentialKey
import io.worldloom.platform.credentials.CredentialReadResult
import io.worldloom.platform.credentials.CredentialVault
import io.worldloom.platform.credentials.CredentialVaultErrorCode
import io.worldloom.platform.credentials.SecretValue
import io.worldloom.provider.api.ProviderCapabilities
import io.worldloom.provider.api.ProviderFailureCode
import io.worldloom.provider.api.ProviderMessage
import io.worldloom.provider.api.ProviderMessageRole
import io.worldloom.provider.api.ProviderRequest
import io.worldloom.provider.api.ProviderResult
import io.worldloom.provider.api.ProviderStreamEvent
import io.worldloom.provider.api.ProviderToolCall
import io.worldloom.provider.api.ProviderToolDefinition
import io.worldloom.provider.api.ProviderToolValueType
import io.worldloom.provider.api.ProviderTurn
import io.worldloom.provider.api.ProviderUsage
import io.worldloom.provider.api.StreamingLanguageModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

val OPENAI_API_KEY: CredentialKey = CredentialKey("openai.api-key")

enum class OpenAiInstructionRole {
    DEVELOPER,
    SYSTEM,
}

data class OpenAiChatCompletionsConfig(
    val model: String,
    val baseUrl: String = "https://api.openai.com/v1",
    val instructionRole: OpenAiInstructionRole = OpenAiInstructionRole.SYSTEM,
    val inputCostMicrounitsPerMillionTokens: Long = 0,
    val outputCostMicrounitsPerMillionTokens: Long = 0,
    val allowInsecureTransport: Boolean = false,
) {
    init {
        require(model.isNotBlank()) { "OpenAI model must not be blank" }
        require(baseUrl.isNotBlank()) { "OpenAI base URL must not be blank" }
        require(inputCostMicrounitsPerMillionTokens >= 0) { "Input price must not be negative" }
        require(outputCostMicrounitsPerMillionTokens >= 0) { "Output price must not be negative" }
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
            "OpenAI base URL must use HTTP or HTTPS"
        }
        require(!baseUrl.contains('@')) { "OpenAI base URL must not contain user information" }
    }
}

/** OpenAI Chat Completions-compatible adapter; no vendor DTO crosses provider-api. */
class OpenAiChatCompletionsProvider(
    private val httpClient: HttpClient,
    private val credentialVault: CredentialVault,
    private val config: OpenAiChatCompletionsConfig,
    private val credentialKey: CredentialKey = OPENAI_API_KEY,
) : StreamingLanguageModelProvider {
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        toolCalling = true,
        streaming = true,
        structuredOutput = false,
    )

    private val endpoint = "${config.baseUrl.trimEnd('/')}/chat/completions"
    private val officialOpenAiEndpoint = config.baseUrl.trimEnd('/').equals(
        "https://api.openai.com/v1",
        ignoreCase = true,
    )
    private val miMoCompatibilityMode = config.model.startsWith("mimo-", ignoreCase = true)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override suspend fun complete(request: ProviderRequest): ProviderResult = withCredential { credential ->
        completeWithCredential(request, credential)
    }

    override suspend fun completeStreaming(
        request: ProviderRequest,
        onEvent: suspend (ProviderStreamEvent) -> Unit,
    ): ProviderResult = withCredential { credential ->
        try {
            var streamed = streamWithCredential(request, credential, includeUsage = true, onEvent)
            if (
                streamed.result is ProviderResult.Failure &&
                !streamed.emittedEvent &&
                !officialOpenAiEndpoint &&
                streamed.result.code == ProviderFailureCode.INVALID_REQUEST
            ) {
                streamed = streamWithCredential(request, credential, includeUsage = false, onEvent)
            }
            if (
                streamed.result is ProviderResult.Failure &&
                !streamed.emittedEvent &&
                streamed.result.code in setOf(ProviderFailureCode.INVALID_REQUEST, ProviderFailureCode.INVALID_RESPONSE)
            ) {
                completeWithCredential(request, credential).also { result ->
                    if (result is ProviderResult.Success) {
                        result.turn.text?.takeIf(String::isNotEmpty)?.let {
                            onEvent(ProviderStreamEvent.TextDelta(it))
                        }
                    }
                }
            } else {
                streamed.result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure(ProviderFailureCode.NETWORK, "Provider stream failed", retryable = true)
        }
    }

    private suspend fun streamWithCredential(
        request: ProviderRequest,
        credential: String,
        includeUsage: Boolean,
        onEvent: suspend (ProviderStreamEvent) -> Unit,
    ): StreamingAttempt {
        val toolNames = OpenAiToolNameCodec.from(request)
        var emittedEvent = false
        val result = httpClient.preparePost(endpoint) {
            contentType(ContentType.Application.Json)
            bearerAuth(credential)
            setBody(
                requestBody(
                    request = request,
                    stream = true,
                    includeStreamUsage = includeUsage,
                    toolNames = toolNames,
                ).toString(),
            )
        }.execute { response ->
            if (!response.status.isSuccess()) return@execute httpFailure(response.status)
            if (response.headers[HttpHeaders.ContentType].orEmpty().contains("application/json", ignoreCase = true)) {
                parseHttpResponse(response, toolNames).also { parsed ->
                    if (parsed is ProviderResult.Success) {
                        parsed.turn.text?.takeIf(String::isNotEmpty)?.let {
                            emittedEvent = true
                            onEvent(ProviderStreamEvent.TextDelta(it))
                        }
                    }
                }
            } else {
                parseStream(response, toolNames) { event ->
                    emittedEvent = true
                    onEvent(event)
                }
            }
        }
        return StreamingAttempt(result, emittedEvent)
    }

    private suspend fun completeWithCredential(
        request: ProviderRequest,
        credential: String,
    ): ProviderResult = try {
        val toolNames = OpenAiToolNameCodec.from(request)
        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            bearerAuth(credential)
            setBody(
                requestBody(
                    request = request,
                    stream = false,
                    toolNames = toolNames,
                ).toString(),
            )
        }
        parseHttpResponse(response, toolNames)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        failure(ProviderFailureCode.NETWORK, "Provider request failed", retryable = true)
    }

    private suspend fun parseHttpResponse(
        response: HttpResponse,
        toolNames: OpenAiToolNameCodec,
    ): ProviderResult {
        if (!response.status.isSuccess()) return httpFailure(response.status)
        val root = try {
            json.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (_: Exception) {
            return failure(ProviderFailureCode.INVALID_RESPONSE, "Provider returned invalid JSON", retryable = false)
        }
        val choice = root["choices"]?.asArrayOrNull()?.firstOrNull()?.asObjectOrNull()
            ?: return failure(ProviderFailureCode.INVALID_RESPONSE, "Provider response has no choice", retryable = false)
        val message = choice["message"]?.asObjectOrNull()
            ?: return failure(ProviderFailureCode.INVALID_RESPONSE, "Provider response has no message", retryable = false)
        val usage = parseUsage(root["usage"]?.asObjectOrNull())
            ?: return failure(ProviderFailureCode.INVALID_RESPONSE, "Provider returned invalid usage", retryable = false)
        return parseTurn(message, usage, toolNames)
    }

    private suspend fun parseStream(
        response: HttpResponse,
        toolNames: OpenAiToolNameCodec,
        onEvent: suspend (ProviderStreamEvent) -> Unit,
    ): ProviderResult {
        val text = StringBuilder()
        val toolCalls = mutableMapOf<Int, StreamedToolCall>()
        var usage = ProviderUsage.Zero
        var completed = false
        val dataLines = mutableListOf<String>()

        suspend fun consumeEvent(): ProviderResult.Failure? {
            if (dataLines.isEmpty()) return null
            val payload = dataLines.joinToString("\n")
            dataLines.clear()
            if (payload == "[DONE]") {
                completed = true
                return null
            }
            val root = try {
                json.parseToJsonElement(payload).jsonObject
            } catch (_: Exception) {
                return failure(ProviderFailureCode.INVALID_RESPONSE, "Provider stream contained invalid JSON", false)
            }
            root["usage"]?.asObjectOrNull()?.let { source ->
                usage = parseUsage(source)
                    ?: return failure(ProviderFailureCode.INVALID_RESPONSE, "Provider returned invalid usage", false)
            }
            val choice = root["choices"]?.asArrayOrNull()?.firstOrNull()?.asObjectOrNull() ?: return null
            if (choice["finish_reason"]?.asStringOrNull() != null) completed = true
            val delta = choice["delta"]?.asObjectOrNull() ?: return null
            delta["content"]?.asStringOrNull()?.takeIf(String::isNotEmpty)?.let { fragment ->
                text.append(fragment)
                onEvent(ProviderStreamEvent.TextDelta(fragment))
            }
            delta["tool_calls"]?.asArrayOrNull()?.forEach { element ->
                val call = element.asObjectOrNull()
                    ?: return failure(ProviderFailureCode.INVALID_RESPONSE, "Tool-call delta is invalid", false)
                val index = call["index"]?.jsonPrimitive?.intOrNull
                    ?: return failure(ProviderFailureCode.INVALID_RESPONSE, "Tool-call delta has no index", false)
                val streamed = toolCalls.getOrPut(index) { StreamedToolCall() }
                call["id"]?.asStringOrNull()?.let(streamed.id::append)
                val function = call["function"]?.asObjectOrNull()
                function?.get("name")?.asStringOrNull()?.let(streamed.name::append)
                function?.get("arguments")?.asStringOrNull()?.let(streamed.arguments::append)
            }
            return null
        }

        val channel = response.bodyAsChannel()
        while (!channel.isClosedForRead) {
            val line = channel.readLine() ?: break
            when {
                line.isEmpty() -> consumeEvent()?.let { return it }
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").trimStart()
            }
        }
        consumeEvent()?.let { return it }
        if (!completed) {
            return failure(ProviderFailureCode.INVALID_RESPONSE, "Provider stream ended before [DONE]", retryable = true)
        }
        val calls = mutableListOf<ProviderToolCall>()
        for (index in toolCalls.keys.sorted()) {
            val streamed = toolCalls.getValue(index)
            val arguments = try {
                json.parseToJsonElement(streamed.arguments.toString()).jsonObject
            } catch (_: Exception) {
                return failure(ProviderFailureCode.INVALID_RESPONSE, "Provider returned invalid tool arguments", false)
            }
            val id = streamed.id.toString()
            val wireName = streamed.name.toString()
            if (id.isBlank() || wireName.isBlank()) {
                return failure(ProviderFailureCode.INVALID_RESPONSE, "Provider returned an incomplete tool call", false)
            }
            calls += ProviderToolCall(id, toolNames.decode(wireName), arguments)
        }
        return turnResult(text.toString().takeIf(String::isNotEmpty), calls, usage)
    }

    private fun parseTurn(
        message: JsonObject,
        usage: ProviderUsage,
        toolNames: OpenAiToolNameCodec,
    ): ProviderResult {
        val text = message["content"]?.asStringOrNull()
        val calls = mutableListOf<ProviderToolCall>()
        message["tool_calls"]?.asArrayOrNull()?.forEach { element ->
            val call = element.asObjectOrNull()
                ?: return failure(ProviderFailureCode.INVALID_RESPONSE, "Provider returned an invalid tool call", false)
            val id = call["id"]?.asStringOrNull()
                ?: return failure(ProviderFailureCode.INVALID_RESPONSE, "Provider tool call has no id", false)
            val function = call["function"]?.asObjectOrNull()
                ?: return failure(ProviderFailureCode.INVALID_RESPONSE, "Provider tool call has no function", false)
            val wireName = function["name"]?.asStringOrNull()
                ?: return failure(ProviderFailureCode.INVALID_RESPONSE, "Provider tool call has no name", false)
            val arguments = try {
                json.parseToJsonElement(function["arguments"]?.asStringOrNull() ?: "").jsonObject
            } catch (_: Exception) {
                return failure(ProviderFailureCode.INVALID_RESPONSE, "Provider returned invalid tool arguments", false)
            }
            calls += ProviderToolCall(id, toolNames.decode(wireName), arguments)
        }
        return turnResult(text, calls, usage)
    }

    private fun turnResult(
        text: String?,
        calls: List<ProviderToolCall>,
        usage: ProviderUsage,
    ): ProviderResult = if (text != null || calls.isNotEmpty()) {
        ProviderResult.Success(ProviderTurn(text, calls, usage))
    } else {
        failure(ProviderFailureCode.INVALID_RESPONSE, "Provider returned neither text nor tool calls", false)
    }

    private fun requestBody(
        request: ProviderRequest,
        stream: Boolean,
        includeStreamUsage: Boolean = false,
        toolNames: OpenAiToolNameCodec,
    ): JsonObject = buildJsonObject {
        put("model", config.model)
        put("messages", buildJsonArray {
            request.messages.forEach { add(messageJson(it, toolNames)) }
        })
        put("max_completion_tokens", request.maxOutputTokens)
        put("stream", stream)
        if (miMoCompatibilityMode) {
            // Worldloom deliberately does not persist private model reasoning. Disabling MiMo's
            // default thinking mode also keeps multi-turn tool calls compatible without replaying it.
            put("thinking", buildJsonObject { put("type", "disabled") })
        }
        if (stream && includeStreamUsage) {
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray { request.tools.forEach { add(toolJson(it, toolNames)) } })
            put("tool_choice", "auto")
        }
    }

    private fun messageJson(
        message: ProviderMessage,
        toolNames: OpenAiToolNameCodec,
    ): JsonObject = buildJsonObject {
        val role = when (message.role) {
            ProviderMessageRole.SYSTEM -> when (config.instructionRole) {
                OpenAiInstructionRole.DEVELOPER -> "developer"
                OpenAiInstructionRole.SYSTEM -> "system"
            }

            ProviderMessageRole.USER -> "user"
            ProviderMessageRole.ASSISTANT -> "assistant"
            ProviderMessageRole.TOOL -> "tool"
        }
        put("role", role)
        message.content?.let { put("content", it) }
        if (message.toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                message.toolCalls.forEach { call ->
                    add(
                        buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", toolNames.encode(call.name))
                                put("arguments", call.arguments.toString())
                            })
                        },
                    )
                }
            })
        }
        message.toolCallId?.let { put("tool_call_id", it) }
    }

    private fun toolJson(
        tool: ProviderToolDefinition,
        toolNames: OpenAiToolNameCodec,
    ): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", toolNames.encode(tool.name))
            put("description", tool.description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    tool.parameters.forEach { parameter ->
                        put(parameter.name, buildJsonObject {
                            put("type", if (parameter.type == ProviderToolValueType.STRING_ARRAY) "array" else when (parameter.type) {
                                ProviderToolValueType.STRING -> "string"
                                ProviderToolValueType.INTEGER -> "integer"
                                ProviderToolValueType.BOOLEAN -> "boolean"
                                ProviderToolValueType.STRING_ARRAY -> error("handled above")
                            })
                            put("description", parameter.description)
                            if (parameter.type == ProviderToolValueType.STRING_ARRAY) {
                                put("items", buildJsonObject {
                                    put("type", "string")
                                    if (parameter.allowedValues.isNotEmpty()) {
                                        put("enum", JsonArray(parameter.allowedValues.map(::JsonPrimitive)))
                                    }
                                })
                            } else if (parameter.allowedValues.isNotEmpty()) {
                                put("enum", JsonArray(parameter.allowedValues.map(::JsonPrimitive)))
                            }
                        })
                    }
                })
                put(
                    "required",
                    JsonArray(tool.parameters.filter { it.required }.map { JsonPrimitive(it.name) }),
                )
                put("additionalProperties", false)
            })
        })
    }

    private fun parseUsage(source: JsonObject?): ProviderUsage? {
        val input = source?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0
        val output = source?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0
        if (input < 0 || output < 0) return null
        return ProviderUsage(
            inputTokens = input,
            outputTokens = output,
            costMicrounits = saturatingAdd(
                tokenCost(input, config.inputCostMicrounitsPerMillionTokens),
                tokenCost(output, config.outputCostMicrounitsPerMillionTokens),
            ),
        )
    }

    private fun tokenCost(
        tokens: Long,
        microunitsPerMillion: Long,
    ): Long {
        val whole = saturatingMultiply(tokens / TOKENS_PER_MILLION, microunitsPerMillion)
        val tokenRemainder = tokens % TOKENS_PER_MILLION
        val fractional = saturatingAdd(
            saturatingMultiply(tokenRemainder, microunitsPerMillion / TOKENS_PER_MILLION),
            saturatingMultiply(tokenRemainder, microunitsPerMillion % TOKENS_PER_MILLION) /
                TOKENS_PER_MILLION,
        )
        return saturatingAdd(whole, fractional)
    }

    private fun saturatingMultiply(
        left: Long,
        right: Long,
    ): Long = when {
        left == 0L || right == 0L -> 0
        left > Long.MAX_VALUE / right -> Long.MAX_VALUE
        else -> left * right
    }

    private fun saturatingAdd(
        left: Long,
        right: Long,
    ): Long = if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private suspend fun withCredential(block: suspend (String) -> ProviderResult): ProviderResult {
        val secret: SecretValue = when (val credential = credentialVault.read(credentialKey)) {
            is CredentialReadResult.Success -> credential.secret
            is CredentialReadResult.Failure -> return if (credential.error.code == CredentialVaultErrorCode.NOT_FOUND) {
                failure(ProviderFailureCode.AUTHENTICATION, "API credential is not configured", false)
            } else {
                failure(ProviderFailureCode.UNKNOWN, "Credential vault is unavailable", true)
            }
        }
        return secret.access(block)
    }

    private fun httpFailure(status: HttpStatusCode): ProviderResult.Failure = when (status.value) {
        401, 403 -> failure(ProviderFailureCode.AUTHENTICATION, "Provider rejected the credential", false)
        408 -> failure(ProviderFailureCode.TIMEOUT, "Provider request timed out", true)
        429 -> failure(ProviderFailureCode.RATE_LIMITED, "Provider rate limit was reached", true)
        in 400..499 -> failure(ProviderFailureCode.INVALID_REQUEST, "Provider rejected the request", false)
        in 500..599 -> failure(ProviderFailureCode.UNAVAILABLE, "Provider is unavailable", true)
        else -> failure(ProviderFailureCode.UNKNOWN, "Unexpected provider status ${status.value}", false)
    }

    private fun failure(
        code: ProviderFailureCode,
        message: String,
        retryable: Boolean,
    ): ProviderResult.Failure = ProviderResult.Failure(code, message, retryable)

    private data class StreamedToolCall(
        val id: StringBuilder = StringBuilder(),
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder(),
    )

    private data class StreamingAttempt(
        val result: ProviderResult,
        val emittedEvent: Boolean,
    )

    private companion object {
        const val TOKENS_PER_MILLION = 1_000_000L
    }
}

private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonElement.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeUnless { it === JsonNull }?.contentOrNull
