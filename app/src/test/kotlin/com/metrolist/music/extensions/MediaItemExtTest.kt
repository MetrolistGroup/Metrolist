/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.extensions

import com.metrolist.music.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaItemExtTest {
    @Test
    fun `updated metadata replaces title artists and artwork`() {
        val original =
            MediaMetadata(
                id = "song",
                title = "Original title",
                artists = listOf(MediaMetadata.Artist(id = "original-artist", name = "Original artist")),
                duration = 100,
                thumbnailUrl = "https://example.com/original.jpg",
            ).toMediaItem()

        val updated =
            original.withUpdatedMetadata(
                MediaMetadata(
                    id = "song",
                    title = "Edited title",
                    artists = listOf(MediaMetadata.Artist(id = "edited-artist", name = "Edited artist")),
                    duration = 100,
                    thumbnailUrl = null,
                ),
            )

        assertEquals("Edited title", updated.mediaMetadata.title)
        assertEquals("Edited artist", updated.mediaMetadata.artist)
        assertNull(updated.mediaMetadata.artworkUri)
        assertNull(updated.mediaMetadata.extras?.getString("artwork_uri"))
    }
}
