package com.metrolist.shared.ui.components.lyrics

import androidx.compose.ui.graphics.ImageBitmap

/** Immutable rendering inputs. A successful platform action means chooser launch, not delivery. */
class LyricsAnimationExport(
    val durationMs: Long,
    val frameKeyAt: (Long) -> Int,
    val renderFrame: (Long) -> ImageBitmap,
    val motionBackground: ImageBitmap? = null,
    val motionColor: Int = 0,
    val videoBackground: LyricsVideoBackground? = null,
)
