package com.metrolist.music.db.entities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongEntityTest {
    @Test
    fun localOnlyLikeUsesTheSameStateTransition() {
        val original = SongEntity(id = "video-id", title = "Title")

        val liked = original.toggleLike(syncToYouTube = false)
        val unliked = liked.toggleLike(syncToYouTube = false)

        assertTrue(liked.liked)
        assertNotNull(liked.likedDate)
        assertNotNull(liked.inLibrary)
        assertFalse(unliked.liked)
        assertTrue(unliked.likedDate == null)
        assertNotNull(unliked.inLibrary)
    }
}
