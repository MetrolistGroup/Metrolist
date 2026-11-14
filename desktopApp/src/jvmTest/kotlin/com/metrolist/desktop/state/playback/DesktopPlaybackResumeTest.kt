package com.metrolist.desktop.state.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPlaybackResumeTest {
    @Test
    fun previousRetainsAnUnpreparedRestoredEntryAtZero() {
        val queue = MusicQueue()
        queue.setQueue(listOf("first", "second").map { SongItem(it, it, null, emptyList(), null) }, 1)
        val pending = queue.currentItem()!!.id to 60_000L
        assertEquals("second" to 0L, desktopPreviousRestartPosition(pending, pending.second, false, queue.hasPrevious()))
        assertEquals(1, queue.currentIndex.value)
        assertNull(desktopPreviousRestartPosition(pending, 60_000L, true, true))
        assertNull(desktopPreviousRestartPosition(pending, 2_000L, false, true))
        assertEquals("second" to 0L, desktopPreviousRestartPosition(pending, 2_000L, false, false))
    }

    @Test
    fun resumeUsesTheLatestIntentAfterSubmittingTheSeek() {
        var desiredPlaying = true
        var playing = true
        assertTrue(
            completeDesktopPlaybackResume(
                positionMs = 60_000L,
                prepared = true,
                currentTransition = true,
                desiredPlaying = { desiredPlaying },
                seek = { desiredPlaying = false },
                setPlaying = { playing = it },
            ),
        )
        assertFalse(playing)
        desiredPlaying = false
        completeDesktopPlaybackResume(0L, true, true, { desiredPlaying }, { desiredPlaying = true }, { playing = it })
        assertTrue(playing)
    }

    @Test
    fun resumeWaitsForPreparationAndNeverSeeksASupersedingTransition() {
        val calls = mutableListOf<String>()

        fun complete(
            ready: Boolean,
            current: Boolean,
            paused: Boolean = false,
        ) = completeDesktopPlaybackResume(
            120_000L,
            ready,
            current,
            { !paused },
            { calls += "seek:$it" },
            { calls += "playing:$it" },
        )

        assertFalse(complete(ready = false, current = true))
        assertFalse(complete(ready = true, current = false))
        assertTrue(calls.isEmpty())
        assertTrue(complete(ready = true, current = true))
        assertEquals(listOf("seek:120000", "playing:true"), calls)
        calls.clear()
        assertTrue(complete(ready = true, current = true, paused = true))
        assertEquals(listOf("seek:120000", "playing:false"), calls)
    }
}
