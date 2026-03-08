package com.metrolist.android.playback

import androidx.media3.common.Player
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerConnectionPolicyTest {
    private val songs =
        listOf(
            SongItem(id = "first", title = "First", thumbnail = null, artists = emptyList(), album = null),
            SongItem(id = "last", title = "Last", thumbnail = null, artists = emptyList(), album = null),
        )

    @Test
    fun nextAvailabilityMatchesExecutableQueueNavigation() {
        val first = MusicQueueSnapshot(displayedItems = songs, currentIndex = 0)
        val last = first.copy(currentIndex = 1)

        assertTrue(canSkipToNext(true, false, first, Player.REPEAT_MODE_OFF, false))
        assertFalse(canSkipToNext(true, false, last, Player.REPEAT_MODE_OFF, false))
        assertFalse(canSkipToNext(true, false, last, Player.REPEAT_MODE_ONE, false))
        assertTrue(canSkipToNext(true, false, last, Player.REPEAT_MODE_ALL, false))
        assertFalse(canSkipToNext(false, false, first, Player.REPEAT_MODE_OFF, false))
        assertTrue(canSkipToNext(false, true, first, Player.REPEAT_MODE_OFF, false))
        assertFalse(canSkipToNext(true, true, first, Player.REPEAT_MODE_OFF, true))
        assertFalse(canSkipToNext(true, false, first.copy(currentIndex = -1), Player.REPEAT_MODE_ALL, false))
    }

    @Test
    fun unfinishedPlaylistKeepsNextAvailableWithoutBypassingLocksOrAnInvalidQueue() {
        val last = MusicQueueSnapshot(displayedItems = songs, currentIndex = songs.lastIndex)
        assertTrue(canSkipToNext(true, false, last, Player.REPEAT_MODE_OFF, false, hasPlaylistContinuation = true))
        assertTrue(canSkipToNext(true, false, last, Player.REPEAT_MODE_ONE, false, hasPlaylistContinuation = true))
        assertFalse(canSkipToNext(true, false, last, Player.REPEAT_MODE_OFF, true, hasPlaylistContinuation = true))
        assertFalse(canSkipToNext(false, false, last, Player.REPEAT_MODE_OFF, false, hasPlaylistContinuation = true))
        assertFalse(canSkipToNext(true, false, MusicQueueSnapshot(), Player.REPEAT_MODE_OFF, false, hasPlaylistContinuation = true))
        assertFalse(canSkipToNext(true, false, last.copy(currentIndex = -1), Player.REPEAT_MODE_OFF, false, hasPlaylistContinuation = true))
        assertFalse(canSkipToNext(true, false, last, Player.REPEAT_MODE_OFF, false, hasPlaylistContinuation = false))
    }
}
