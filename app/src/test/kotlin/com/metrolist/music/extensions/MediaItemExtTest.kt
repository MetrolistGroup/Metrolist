/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.extensions

import androidx.media3.session.MediaConstants.EXTRAS_KEY_IS_EXPLICIT
import androidx.media3.session.MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT
import com.metrolist.music.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaItemExtTest {
    @Test
    fun `updated metadata replaces title artists artwork and explicit flag`() {
        val original =
            MediaMetadata(
                id = "song",
                title = "Original title",
                artists = listOf(MediaMetadata.Artist(id = "original-artist", name = "Original artist")),
                duration = 100,
                thumbnailUrl = "https://example.com/original.jpg",
            ).toMediaItem()
        assertFalse(requireNotNull(original.mediaMetadata.extras).containsKey(EXTRAS_KEY_IS_EXPLICIT))

        val updated =
            original.withUpdatedMetadata(
                MediaMetadata(
                    id = "song",
                    title = "Edited title",
                    artists = listOf(MediaMetadata.Artist(id = "edited-artist", name = "Edited artist")),
                    duration = 100,
                    thumbnailUrl = null,
                    explicit = true,
                ),
            )

        assertEquals("Edited title", updated.mediaMetadata.title)
        assertEquals("Edited artist", updated.mediaMetadata.artist)
        assertNull(updated.mediaMetadata.artworkUri)
        assertNull(updated.mediaMetadata.extras?.getString("artwork_uri"))
        assertEquals(EXTRAS_VALUE_ATTRIBUTE_PRESENT, updated.mediaMetadata.extras?.getLong(EXTRAS_KEY_IS_EXPLICIT))

        val converted = requireNotNull(updated.metadata).toMediaItem()
        assertEquals(EXTRAS_VALUE_ATTRIBUTE_PRESENT, converted.mediaMetadata.extras?.getLong(EXTRAS_KEY_IS_EXPLICIT))

        val clean = updated.withUpdatedMetadata(requireNotNull(original.metadata))
        assertFalse(requireNotNull(clean.mediaMetadata.extras).containsKey(EXTRAS_KEY_IS_EXPLICIT))
    }
}
