package com.metrolist.shared.network

import com.metrolist.shared.youtube.playback.ExtractionHttpCooldown
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

actual val sharedHttpTransportHandlesFailures = false

actual fun Throwable.isHttpTransportException(): Boolean = false

actual fun createHttpClient(): HttpClient =
    HttpClient(Darwin) {
        install(ExtractionHttpCooldown)
        install(HttpFailureLog)
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
        engine {
            configureRequest {
                setAllowsCellularAccess(true)
            }
        }
        expectSuccess = false
    }
