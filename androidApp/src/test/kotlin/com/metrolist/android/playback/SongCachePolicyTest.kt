package com.metrolist.android.playback

import androidx.media3.common.Player
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.QUEUE_SOURCE_AUTO_MIX
import com.metrolist.shared.playback.QUEUE_SOURCE_RECOMMENDED
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongCachePolicyTest {
    private fun createDummySong(
        id: String,
        queueSourceTitle: String? = null,
    ): SongItem =
        SongItem(
            id = id,
            title = "Title $id",
            artists = emptyList(),
            album = null,
            duration = 180L,
            thumbnail = null,
            queueSourceTitle = queueSourceTitle,
        )

    @Test
    fun autoLoadMoreRequiresTheLastQueuedTrackAndRepeatOff() {
        val queue = (1..5).map { createDummySong("song$it") }
        for (index in listOf(-1, 0, 3, 4, 5)) {
            for (enabled in listOf(false, true)) {
                for (mode in listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)) {
                    assertEquals(index == 4 && enabled && mode == Player.REPEAT_MODE_OFF, shouldAutoLoadMore(index, queue, enabled, mode))
                }
            }
        }
        assertFalse(shouldAutoLoadMore(0, emptyList(), true, Player.REPEAT_MODE_OFF))
        assertTrue(shouldAutoLoadMore(0, queue.take(1), true, Player.REPEAT_MODE_OFF))
    }

    @Test
    fun recommendationsDoNotDelayLoadingButExplicitAndAutoMixTracksDo() {
        val queue =
            listOf(createDummySong("first"), createDummySong("last")) +
                List(2) { createDummySong("suggested-$it", QUEUE_SOURCE_RECOMMENDED) }
        for (index in listOf(-1, 0, 1, 2, 3, 10)) {
            assertEquals(index == 0, hasUpcomingQueuedTracks(queue, index))
        }
        assertFalse(hasUpcomingQueuedTracks(emptyList(), 0))
        assertFalse(shouldAutoLoadMore(0, queue, true, Player.REPEAT_MODE_OFF))
        assertTrue(shouldAutoLoadMore(1, queue, true, Player.REPEAT_MODE_OFF))
        assertTrue(hasUpcomingQueuedTracks(queue.take(1) + createDummySong("mix", QUEUE_SOURCE_AUTO_MIX), 0))
    }

    @Test
    fun automaticCacheSelectsThePlayingTrackImmediately() {
        val song = createDummySong("song1")
        val resource =
            PlaybackResource(
                videoId = song.id,
                url = "https://audio.example/song1",
                headers = emptyMap(),
                loudnessDb = null,
                expiresAt = null,
            )
        assertEquals(listOf(song), automaticCacheTracks(song, resource, true))
        assertTrue(automaticCacheTracks(song, resource, false).isEmpty())
        assertTrue(automaticCacheTracks(createDummySong("song2"), resource, true).isEmpty())
    }

    @Test
    fun sequentialCacheSkipsCachedTracksAndFetchesTheRestInOrder() =
        runBlocking {
            val queue = (1..5).map { createDummySong("song$it") }
            val fetched = mutableListOf<String>()
            repeat(10) {
                cacheQueueSequentially(
                    queue.take(1),
                    { true },
                    { it == "song1" },
                    { fetched += it.id },
                    { _, error -> throw error },
                )
            }
            assertTrue(fetched.isEmpty())
            cacheQueueSequentially(
                queue.take(3),
                { true },
                { it == "song1" },
                { fetched += it.id },
                { _, error -> throw error },
            )
            assertEquals(listOf("song2", "song3"), fetched)
        }

    @Test
    fun cacheWindowRunsInOrderOnceEvenIfEarlierSongsAreEvicted() =
        runBlocking {
            val queue = (1..5).map { createDummySong("song$it") }
            val completed = mutableListOf<String>()
            var cachedId: String? = null
            cacheQueueSequentially(queue.take(3), { true }, { it == cachedId }, {
                cachedId = it.id
                completed += it.id
            }, { _, error -> throw error })
            assertEquals(listOf("song1", "song2", "song3"), completed)
            completed.clear()
            var current = true
            cacheQueueSequentially(queue.take(3), { current }, { it == "song1" }, {
                completed += it.id
                current = false
            }, { _, error -> throw error })
            assertEquals(listOf("song2"), completed)
        }
}
