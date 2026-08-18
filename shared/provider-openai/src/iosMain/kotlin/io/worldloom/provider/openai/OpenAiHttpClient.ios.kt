package io.worldloom.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun createOpenAiHttpClient(): HttpClient = HttpClient(Darwin)
