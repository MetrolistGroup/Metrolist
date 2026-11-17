package com.metrolist.shared.model

import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioQueuePolicyTest {
    @Test
    fun artistQueueRejectsVideosAcrossContinuationShuffleAndRestore() {
        val seed = song("seed")
        val audio = song("audio").copy(musicVideoType = "MUSIC_VIDEO_TYPE_ATV")
        val video = song("video").copy(musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
        val podcast = song("podcast").copy(podcastId = "podcast")
        val queue = MusicQueue().apply { setQueue(listOf(seed), recommendationsSongsOnly = true) }
        queue.toggleShuffle()
        val saved = Json.decodeFromString<MusicQueueSnapshot>(Json.encodeToString(queue.snapshot()))
        val restored = MusicQueue().apply { restore(saved.forRestore(rememberShuffle = true)) }

        fun appendPage(items: List<SongItem>) {
            buildRadioQueueRecommendations(
                items,
                restored.items.value.mapTo(mutableSetOf()) { it.id },
                "generated",
                { false },
                songsOnly = restored.recommendationsSongsOnly,
            ).forEach(restored::addToQueue)
        }
        appendPage(listOf(seed, video, audio, podcast))
        appendPage(listOf(video, audio, song("next")))
        assertEquals(
            setOf("seed", "audio", "next"),
            restored.items.value
                .map { it.id }
                .toSet(),
        )
        restored.setQueue(listOf(video))
        assertEquals(false, restored.recommendationsSongsOnly)
        assertEquals(
            listOf(video),
            buildRadioQueueRecommendations(listOf(video), emptySet(), "generated", {
                false
            }).map { it.copy(queueSourceTitle = null) },
        )
    }

    @Test
    fun repeatedRadioQueuesCanVaryWithoutLosingSongs() {
        val songs = listOf(song("a"), song("b"), song("c"), song("d"))
        val varied = varyRadioRecommendations(songs, classic = false, random = Random(1))

        assertNotEquals(songs, varied)
        assertEquals(songs.toSet(), varied.toSet())
    }

    @Test
    fun classicRadioKeepsYoutubeOrderWithoutLosingSongs() {
        val songs = listOf(song("a"), song("b"), song("c"), song("d"))

        assertEquals(songs, varyRadioRecommendations(songs, classic = true, random = Random(1)))
    }

    @Test
    fun classicRadioKeepsFirstPageOrderAndPreviouslyRecommendedSongs() =
        runTest {
            val history = RadioRecommendationHistory()
            val firstPage = RadioQueuePage(listOf(song("a"), song("b")), emptyList(), continuation = "next")
            val filter: (List<SongItem>) -> List<SongItem> = { it }

            val first =
                history.select(firstPage, classic = true, filter = filter, isCurrent = { true }) {
                    error("Classic recommendations must not load more")
                }!!
            history.remember(first.songs)
            val second =
                history.select(firstPage, classic = true, filter = filter, isCurrent = { true }) {
                    error("Classic recommendations must not load more")
                }!!

            assertEquals(listOf("a", "b"), first.songs.map(SongItem::id))
            assertEquals(listOf("a", "b"), second.songs.map(SongItem::id))
            assertEquals("next", second.continuation)
        }

    @Test
    fun classicRadioStopsWhenTheSearchIsStale() =
        runTest {
            val history = RadioRecommendationHistory()
            val firstPage = RadioQueuePage(listOf(song("a")), emptyList())

            assertNull(
                history.select(firstPage, classic = true, filter = { it }, isCurrent = { false }) {
                    error("Stale searches must not load more")
                },
            )
        }

    @Test
    fun recommendationsExcludeSeedBlockedAndDuplicateSongs() {
        val recommendations =
            buildRadioQueueRecommendations(
                songs = listOf(song("seed"), song("blocked"), song("one"), song("one"), song("two")),
                queuedSongIds = setOf("seed"),
                generatedQueueSourceTitle = "generated",
                isBlocked = { it.id == "blocked" },
            )

        assertEquals(listOf("one", "two"), recommendations.map(SongItem::id))
        assertTrue(recommendations.all { it.queueSourceTitle == "generated" })
    }

    @Test
    fun existingQueueSourceIsPreserved() {
        val recommendation = song("one").copy(queueSourceTitle = "existing")

        val result =
            buildRadioQueueRecommendations(
                songs = listOf(recommendation),
                queuedSongIds = emptySet(),
                generatedQueueSourceTitle = "generated",
                isBlocked = { false },
            )

        assertEquals("existing", result.single().queueSourceTitle)
    }

    private fun song(id: String) =
        SongItem(
            id = id,
            title = id,
            thumbnail = null,
            artists = emptyList(),
            album = null,
        )
}
