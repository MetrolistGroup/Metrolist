package com.metrolist.shared.state

import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.toSongItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PodcastResumePositionTest {
    private val episode =
        EpisodeItem(
            id = "episode",
            title = "Episode",
            thumbnail = null,
            duration = 7200L,
            playbackProgressPercent = 95,
        ).toSongItem()

    @Test
    fun resumesWithSixMinutesLeftEvenAtNinetyFivePercent() {
        assertEquals(6_840_000L, podcastResumePosition(episode, null))
        assertEquals(6_840_000L, podcastResumePosition(episode.copy(playbackPositionMs = 0L), null))
        assertEquals(6_840_000L, podcastResumePosition(episode.copy(playbackProgressPercent = null), 6_840_000L))
        assertEquals(0L, podcastResumePosition(episode, 7_170_001L))
        assertEquals(0L, podcastResumePosition(episode, 7_200_000L))
        assertEquals(0L, podcastResumePosition(episode.copy(playbackProgressPercent = 100), null))
    }

    @Test
    fun localCheckpointsOverrideStaleAccountProgressIncludingRestartAndCompletion() {
        assertEquals(120_000L, podcastResumePosition(episode, 120_000L))
        assertEquals(0L, podcastResumePosition(episode, 0L))
        assertEquals(0L, podcastResumePosition(episode, 7_200_000L))
        assertEquals(120_000L, podcastResumePosition(episode.copy(playbackProgressPercent = 100), 120_000L))
    }

    @Test
    fun explicitPositionsPreserveSeeksRestoresAndRenewals() {
        for (requested in listOf(0L, 45_000L, 7_180_000L)) {
            assertEquals(requested, podcastResumePosition(episode, 120_000L, requestedPositionMs = requested))
        }
        assertEquals(0L, podcastResumePosition(episode, 120_000L, requestedPositionMs = -1L))
    }

    @Test
    fun exactAccountPositionSurvivesConversionAndQueueSerialization() {
        val item = EpisodeItem("video-id", "Episode", null, duration = 7200L, playbackPositionMs = 2_813_000L, playbackProgressPercent = 39)
        val song = Json.decodeFromString<SongItem>(Json.encodeToString(item.toSongItem()))
        assertEquals(item.id, song.id)
        assertEquals(item.playbackProgressPercent, song.playbackProgressPercent)
        assertEquals(2_813_000L, podcastResumePosition(song, null))
        assertEquals(120_000L, podcastResumePosition(song, 120_000L))
    }

    @Test
    fun unknownDurationAndInvalidProgressDoNotInventAResumePoint() {
        assertEquals(0L, podcastResumePosition(episode.copy(duration = null), null))
        assertEquals(6_840_000L, podcastResumePosition(episode.copy(duration = null), null, durationMs = 7_200_000L))
        assertEquals(120_000L, podcastResumePosition(episode.copy(duration = null), 120_000L))
        assertEquals(120_000L, podcastResumePosition(episode.copy(duration = null, playbackPositionMs = 120_000L), null))
        assertEquals(0L, podcastResumePosition(episode.copy(playbackProgressPercent = 101), -1L))
        assertEquals(0L, podcastResumePosition(episode.copy(playbackProgressPercent = -1, playbackPositionMs = -1L), null))
        assertEquals(0L, podcastResumePosition(episode.copy(musicVideoType = null), 120_000L))
    }
}
