package com.metrolist.desktop.ui.screens.settings

internal fun desktopImageCacheLimitOptions(currentLimitMb: Int): List<Long> {
    val presets = listOf(0L, 128L, 256L, 512L, 1024L, 2048L, 4096L, 8192L)
    val saved = currentLimitMb.toLong()
    return if (saved < 0 || saved in presets) presets else (presets + saved).sorted()
}
