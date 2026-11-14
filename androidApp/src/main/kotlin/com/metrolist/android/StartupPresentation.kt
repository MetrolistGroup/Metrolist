package com.metrolist.android

import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalStartupPresentation = staticCompositionLocalOf<StartupPresentation?> { null }

internal class StartupPresentation(
    private val startedAtMs: Long,
) {
    var contentLaidOut = false
    var homeRequired = false
    var homeLaidOut = false
    var homeCacheReady = false
    var avatarReady = true

    fun keepSplash(
        nowMs: Long,
        startupReady: Boolean,
        recovering: Boolean,
        restoringBackup: Boolean,
    ): Boolean {
        if (recovering || restoringBackup) return false
        if (!startupReady || !contentLaidOut || (homeRequired && !homeLaidOut)) return true
        return nowMs - startedAtMs < 1_200L && (!avatarReady || (homeRequired && !homeCacheReady))
    }
}
