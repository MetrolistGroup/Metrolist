package com.metrolist.shared.discord

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

internal var discordSettingsVisible by mutableStateOf(false)

internal data class DiscordPlaybackUpdate(
    val keepConnected: Boolean,
    val songId: String?,
    val activity: DiscordActivity?,
)

internal suspend fun maintainDiscordPlaybackSession(
    updates: Flow<DiscordPlaybackUpdate>,
    connect: () -> Unit,
    disconnect: suspend () -> Unit,
    publish: (DiscordPlaybackUpdate) -> Unit,
) {
    var connected = false
    try {
        updates.collect { update ->
            if (update.keepConnected) {
                if (!connected) {
                    connect()
                    connected = true
                }
                publish(update)
            } else if (connected) {
                disconnect()
                connected = false
            }
        }
    } finally {
        if (connected) withContext(NonCancellable) { disconnect() }
    }
}
