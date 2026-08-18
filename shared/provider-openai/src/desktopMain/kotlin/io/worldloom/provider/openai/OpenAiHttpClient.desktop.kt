package io.worldloom.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

actual fun createOpenAiHttpClient(): HttpClient = HttpClient(CIO)
