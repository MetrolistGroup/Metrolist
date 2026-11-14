/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Local WebSocket server that accepts incoming DeviceConnect links from peers on
 * the LAN. The platform actual hosts it (Ktor CIO on Android).
 */

package com.metrolist.shared.deviceconnect

expect class LocalDeviceServer() {
    /** Port 0 selects a free port; returns the actual listening port. */
    fun start(
        port: Int,
        onFailure: (Throwable) -> Unit,
        onChannel: suspend (DeviceChannel) -> Unit,
    ): Int

    fun stop()
}
