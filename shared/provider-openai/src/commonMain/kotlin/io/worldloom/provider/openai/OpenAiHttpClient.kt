package io.worldloom.provider.openai

import io.ktor.client.HttpClient

expect fun createOpenAiHttpClient(): HttpClient
