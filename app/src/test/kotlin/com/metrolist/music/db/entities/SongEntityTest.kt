package com.metrolist.music.db.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

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

    @Test
    fun libraryMembershipSyncDoesNotChangeLikeState() {
        val likedAt = LocalDateTime.of(2026, 8, 30, 12, 0)
        val original =
            SongEntity(
                id = "video-id",
                title = "Title",
                liked = true,
                likedDate = likedAt,
                inLibrary = likedAt,
            )

        val removed = original.withLibraryMembership(isInLibrary = false)
        val restored = removed.withLibraryMembership(isInLibrary = true, addedAt = likedAt.plusHours(1))

        assertTrue(removed.liked)
        assertEquals(likedAt, removed.likedDate)
        assertNull(removed.inLibrary)
        assertTrue(restored.liked)
        assertEquals(likedAt, restored.likedDate)
        assertEquals(likedAt.plusHours(1), restored.inLibrary)
    }
}
