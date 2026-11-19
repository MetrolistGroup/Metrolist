package com.metrolist.shared.ui.components.lyrics

import androidx.compose.ui.graphics.ImageBitmap

/** User-selected silent backdrop. Platform owns the prepared session. */
interface LyricsVideoBackground {
    val durationMs: Long
    fun acquireLease(): LyricsVideoBackgroundLease
}

/** Borrowed images are only valid inside the synchronous callback: never retain or recycle them. */
interface LyricsVideoBackgroundLease {
    fun withFrame(positionMs: Long, block: (ImageBitmap) -> Unit)
    fun close()
}
