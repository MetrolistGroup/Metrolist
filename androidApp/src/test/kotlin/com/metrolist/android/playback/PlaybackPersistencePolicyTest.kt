package com.metrolist.android.playback

import com.metrolist.android.models.PersistPlayerState
import com.metrolist.android.models.PlaybackPersistenceWriter
import com.metrolist.shared.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPersistencePolicyTest {
    @Test
    fun aTransientEmptyQueueKeepsTheSavedQueueAndPosition() {
        val state = pausedQueueState()
        var stored = ""
        var position = ""
        val writer =
            PlaybackPersistenceWriter(
                writePosition = { position = it },
                writeStoredValue = { stored = it },
            )
        writer.write(writer.request(state))
        val savedState = stored
        val savedPosition = position

        assertFalse(shouldPersistPlaybackState(state, null))
        writer.write(writer.request(null))

        assertEquals(savedState, stored)
        assertEquals(savedPosition, position)
        assertTrue(stored.isNotBlank())
        assertTrue(position.isNotBlank())
    }

    @Test
    fun anExplicitClearRemovesTheSavedQueueAndPosition() {
        var stored = ""
        var position = ""
        val writer =
            PlaybackPersistenceWriter(
                writePosition = { position = it },
                writeStoredValue = { stored = it },
            )
        writer.write(writer.request(pausedQueueState()))
        assertTrue(stored.isNotBlank())
        assertTrue(position.isNotBlank())

        writer.write(writer.requestClear())

        assertEquals("", stored)
        assertEquals("", position)
    }

    private fun pausedQueueState() =
        PersistPlayerState(
            playWhenReady = false,
            repeatMode = 0,
            shuffleModeEnabled = false,
            volume = 1f,
            currentPosition = 10_000L,
            currentMediaItemIndex = 0,
            playbackState = 1,
            queueSongs =
                listOf(
                    SongItem(id = "first", title = "First", thumbnail = null, artists = emptyList(), album = null),
                    SongItem(id = "second", title = "Second", thumbnail = null, artists = emptyList(), album = null),
                ),
        )
}
