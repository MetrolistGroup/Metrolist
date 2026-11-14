/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.deviceconnect

import com.metrolist.shared.network.HttpFailureLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.websocket.WebSockets

actual fun createDeviceConnectHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(HttpFailureLog)
        install(WebSockets) {
            maxFrameSize = DEVICE_CONNECT_MAX_FRAME_BYTES
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = 30_000
        }
    }
