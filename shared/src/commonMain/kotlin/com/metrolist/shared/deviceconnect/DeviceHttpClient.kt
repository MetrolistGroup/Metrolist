/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Dedicated WebSocket HTTP client for DeviceConnect (with the WebSockets plugin
 * installed). Mirrors the existing [com.metrolist.shared.network.createHttpClient].
 */

package com.metrolist.shared.deviceconnect

import io.ktor.client.HttpClient

expect fun createDeviceConnectHttpClient(): HttpClient
