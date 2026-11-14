package com.metrolist.shared.model

import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
        val varied = varyRadioRecommendations(songs, Random(1))

        assertNotEquals(songs, varied)
        assertEquals(songs.toSet(), varied.toSet())
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
