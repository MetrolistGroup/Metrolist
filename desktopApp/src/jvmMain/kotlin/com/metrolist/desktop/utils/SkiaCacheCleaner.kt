package com.metrolist.desktop.utils

import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.playback.PlayerState
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.jetbrains.skia.Graphics

object SkiaCacheCleaner {
    private var pendingPurgeCount = 0

    fun schedulePurge() {
        pendingPurgeCount = 2
        AppLogger.info("SkiaCacheCleaner", "lyrics closed, will purge after $pendingPurgeCount song changes")
    }

    fun initialize() {
        Graphics.resourceCacheTotalLimit = minOf(Graphics.resourceCacheTotalLimit, 16 * 1024 * 1024)
        AppState.scope.launch {
            PlayerState.player.currentSong.drop(1).collect {
                if (pendingPurgeCount > 0) {
                    pendingPurgeCount--
                    if (pendingPurgeCount == 0) {
                        val beforeFont = Graphics.fontCacheUsed
                        val beforeResource = Graphics.resourceCacheTotalUsed
                        Graphics.purgeAllCaches()
                        NativeMemoryTrimmer.schedule()
                        val afterFont = Graphics.fontCacheUsed
                        val afterResource = Graphics.resourceCacheTotalUsed
                        AppLogger.info(
                            "SkiaCacheCleaner",
                            "purged caches " +
                                "(font: ${beforeFont / 1024}K->${afterFont / 1024}K, " +
                                "resource: ${beforeResource / 1024}K->${afterResource / 1024}K)",
                        )
                    }
                }
            }
        }
    }
}
