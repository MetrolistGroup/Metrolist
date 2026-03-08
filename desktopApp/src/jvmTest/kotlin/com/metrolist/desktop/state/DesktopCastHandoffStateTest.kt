package com.metrolist.desktop.state

import com.metrolist.desktop.state.playback.DesktopCastHandoffState
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopCastHandoffStateTest {
    @Test
    fun castUsesCurrentPositionAndControlsOnlyForTheSamePlaybackInstance() {
        val song = SongItem(id = "song", title = "Song", thumbnail = null, artists = emptyList(), album = null)
        val initial = DesktopCastHandoffState(song, null, 1_000L, 180_000L, false, 0.4f, 7L)
        val afterFetch = initial.copy(positionMs = 12_000L, playing = true, volume = 0.8f)
        assertEquals(afterFetch, afterFetch.forHandoff(initial))
        assertNull(afterFetch.copy(song = song.copy(id = "other")).forHandoff(initial))
        assertNull(afterFetch.copy(playbackInstanceId = 8L).forHandoff(initial))
    }
}
