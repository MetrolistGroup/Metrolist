package com.metrolist.shared.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemPlaybackTest {
    @Test
    fun trackChangesInvalidateOnlyPreviousAndCurrentOccurrences() {
        val currentSongId = mutableStateOf<String?>("song-0")
        val isPlaying = mutableStateOf(true)
        val ids = List(100) { "song-$it" } + "song-1"
        val playback = ids.map { itemPlayback(it, currentSongId, isPlaying) }
        val invalidated = mutableSetOf<Int>()
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            playback.forEachIndexed { index, state ->
                observer.observeReads(index, { invalidated += it }) { state.value }
            }

            Snapshot.withMutableSnapshot { currentSongId.value = "song-1" }

            assertEquals(setOf(0, 1, 100), invalidated)
            assertEquals(ItemPlayback(isCurrent = false, isPlaying = false), playback[0].value)
            assertEquals(ItemPlayback(isCurrent = true, isPlaying = true), playback[1].value)
            assertEquals(playback[1].value, playback[100].value)
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test
    fun pauseAndResumeInvalidateOnlyTheCurrentItem() {
        val currentSongId = mutableStateOf<String?>("current")
        val isPlaying = mutableStateOf(true)
        val current = itemPlayback("current", currentSongId, isPlaying)
        val other = itemPlayback("other", currentSongId, isPlaying)
        val invalidated = mutableSetOf<String>()
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            for (playing in listOf(false, true)) {
                invalidated.clear()
                observer.observeReads("current", { invalidated += it }) { current.value }
                observer.observeReads("other", { invalidated += it }) { other.value }

                Snapshot.withMutableSnapshot { isPlaying.value = playing }

                assertEquals(setOf("current"), invalidated)
                assertEquals(ItemPlayback(isCurrent = true, isPlaying = playing), current.value)
                assertEquals(ItemPlayback(isCurrent = false, isPlaying = false), other.value)
            }
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test
    fun rapidChangesAndClearingPlaybackKeepTheLatestState() {
        val currentSongId = mutableStateOf<String?>(null)
        val isPlaying = mutableStateOf(false)
        val first = itemPlayback("first", currentSongId, isPlaying)
        val second = itemPlayback("second", currentSongId, isPlaying)

        assertEquals(ItemPlayback(isCurrent = false, isPlaying = false), first.value)
        currentSongId.value = "first"
        assertEquals(ItemPlayback(isCurrent = true, isPlaying = false), first.value)
        currentSongId.value = "second"
        isPlaying.value = true
        assertEquals(ItemPlayback(isCurrent = false, isPlaying = false), first.value)
        assertEquals(ItemPlayback(isCurrent = true, isPlaying = true), second.value)
        currentSongId.value = null
        assertEquals(ItemPlayback(isCurrent = false, isPlaying = false), second.value)
    }
}
