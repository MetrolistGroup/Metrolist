/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Platform-agnostic view of a single device-to-device link (client or server side).
 * Adapts Ktor's client/server WebSocket sessions so the common manager never touches
 * platform-specific WebSocket types.
 */

package com.metrolist.shared.deviceconnect

import kotlinx.coroutines.flow.Flow

interface DeviceChannel {
    val remoteHost: String?

    val incoming: Flow<String>

    suspend fun send(text: String)

    suspend fun close()
}
