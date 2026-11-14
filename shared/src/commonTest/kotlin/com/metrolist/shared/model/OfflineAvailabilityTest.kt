package com.metrolist.shared.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineAvailabilityTest {
    @Test
    fun playableItemsRequireLocalMediaOrTheirOwnCacheEntry() {
        val song = SongItem("song", "Song", null, emptyList(), null)
        val episode = EpisodeItem("episode", "Episode", null)
        for (item in listOf(song, episode)) {
            assertFalse(item.isAvailableOffline(emptySet()))
            assertFalse(item.isAvailableOffline(setOf("unrelated")))
            assertTrue(item.isAvailableOffline(setOf(item.id)))
        }
        assertTrue(song.copy(isLocal = true).isAvailableOffline(emptySet()))
        assertFalse(episode.copy(saved = true).isAvailableOffline(emptySet()))
        assertTrue(PodcastItem("podcast", "Podcast", null).isAvailableOffline(emptySet()))
        assertTrue(BrowseItem("browse", "Browse").isAvailableOffline(emptySet()))
    }
}
