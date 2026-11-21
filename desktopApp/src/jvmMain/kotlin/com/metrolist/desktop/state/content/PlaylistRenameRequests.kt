package com.metrolist.desktop.state.content

import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

internal class PlaylistRenameRequests {
    private val pending = ConcurrentHashMap.newKeySet<String>()

    suspend fun save(
        playlistId: String,
        rename: suspend () -> Result<Unit>,
    ): Result<Unit> {
        val key = playlistId.removePrefix("VL")
        if (!pending.add(key)) return Result.failure(IllegalStateException("Playlist rename already in progress"))
        return try {
            rename()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            pending.remove(key)
        }
    }
}
