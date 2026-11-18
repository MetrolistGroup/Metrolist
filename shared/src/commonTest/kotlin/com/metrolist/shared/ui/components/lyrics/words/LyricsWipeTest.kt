package com.metrolist.shared.ui.components.lyrics.words

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsWipeTest {
    private fun wipe(
        syllableIndex: Int,
        localStart: Float,
        localEnd: Float,
        activeSylIdx: Int,
        curGX: Float,
        progress: Float,
    ): Int {
        val out = WipeResult()
        computeWipe(
            syllableIndex = syllableIndex,
            lastSyllableIndex = syllableIndex,
            clusterLineIdx = 0,
            clusterWidth = localEnd - localStart,
            isRtlLine = false,
            lineStart = 0f,
            lineWidth = 300f,
            localStart = localStart,
            localEnd = localEnd,
            activeSylIdx = activeSylIdx,
            activeLineIdx = 0,
            curGX = curGX,
            isActiveSylLastOfLine = false,
            activeSylProgress = progress,
            gradientWidth = 40f,
            out = out,
        )
        return out.mode
    }

    @Test
    fun finishedWordIsFullyLitWhileTheNextWordWaits() {
        // First word spans 0..100, the next word starts at 110; the singer pauses after the first word.
        assertEquals(WIPE_PAST, wipe(0, 0f, 100f, activeSylIdx = 0, curGX = 100f, progress = 1f))
        assertEquals(WIPE_BAND, wipe(0, 0f, 100f, activeSylIdx = 0, curGX = 50f, progress = 0.5f))
        assertEquals(WIPE_BAND, wipe(1, 110f, 200f, activeSylIdx = 0, curGX = 100f, progress = 1f))
    }

    @Test
    fun sungWordStaysLitWhenTheNextWordStarts() {
        assertEquals(WIPE_PAST, wipe(0, 0f, 100f, activeSylIdx = 1, curGX = 110f, progress = 0f))
        assertEquals(WIPE_UNLIT, wipe(2, 210f, 300f, activeSylIdx = 1, curGX = 110f, progress = 0f))
    }
}
