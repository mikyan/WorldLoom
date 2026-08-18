package io.worldloom.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun createOpenAiHttpClient(): HttpClient = HttpClient(OkHttp)
