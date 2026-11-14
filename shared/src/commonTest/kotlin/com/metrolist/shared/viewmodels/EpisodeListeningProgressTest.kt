package com.metrolist.shared.viewmodels

import com.metrolist.shared.db.entities.EpisodeListeningProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeListeningProgressTest {
    @Test
    fun historyDoesNotInventACompletedPosition() {
        val played = EpisodeListeningProgress("episode", 100, null, true)
        assertTrue(played.hasStarted)
        assertNull(played.fraction())
        assertFalse(played.isComplete())
        assertFalse(played.copy(played = false).hasStarted)
    }

    @Test
    fun checkpointUsesSecondsForDurationAndMillisecondsForPosition() {
        val partial = EpisodeListeningProgress("episode", 100, 25_000, false)
        assertTrue(partial.hasStarted)
        assertEquals(0.25f, partial.fraction())
        assertEquals(0.5f, partial.fraction(50))
        assertEquals(1f, partial.copy(playbackPosition = 150_000).fraction())
        assertNull(partial.copy(duration = -1).fraction())
        assertNull(partial.copy(playbackPosition = -1).fraction())
    }

    @Test
    fun partiallyListenedEpisodeWithHistoryIsNotPlayed() {
        val partial = EpisodeListeningProgress("episode", 2 * 3600 + 23 * 60 + 56, (46 * 60 + 53) * 1000L, true)
        assertFalse(partial.isComplete())
        assertEquals(2813f / 8636f, partial.fraction())
    }

    @Test
    fun completionRequiresLessThanThirtySecondsRemaining() {
        val episode = EpisodeListeningProgress("episode", 3600, 3_419_999, true)
        assertFalse(episode.isComplete())
        assertFalse(episode.copy(playbackPosition = 3_420_000).isComplete())
        assertFalse(episode.copy(playbackPosition = 3_570_000).isComplete())
        assertTrue(episode.copy(playbackPosition = 3_570_001).isComplete())
        assertTrue(episode.copy(playbackPosition = 3_600_000).isComplete())
        assertTrue(episode.copy(playbackPosition = 3_700_000).isComplete())
        val shortEpisode = episode.copy(duration = 300, playbackPosition = 270_000)
        assertFalse(shortEpisode.isComplete())
        assertTrue(shortEpisode.copy(playbackPosition = 270_001).isComplete())
        assertFalse(episode.copy(duration = -1).isComplete())
        assertFalse(episode.copy(playbackPosition = 0).isComplete())
        assertFalse(episode.copy(playbackPosition = -1).isComplete())
        assertFalse(episode.copy(duration = -1).isComplete(3500))
        assertTrue(episode.copy(duration = -1).isComplete(3430))
    }
}
