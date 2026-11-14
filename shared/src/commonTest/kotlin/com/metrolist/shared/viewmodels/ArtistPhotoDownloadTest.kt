package com.metrolist.shared.viewmodels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArtistPhotoDownloadTest {
    @Test
    fun `file names exclude URL queries and sanitize both artist and image names`() {
        assertEquals("Artist photo", artistPhotoBaseName("Artist", "https://example.com/photo?size=large#preview"))
        assertEquals("AC_DC_ portrait_2", artistPhotoBaseName("AC/DC\n", "https://example.com/portrait:2.jpg"))
    }

    @Test
    fun `extensions cannot introduce a path into a photo file name`() {
        for (mime in listOf("image/../../photo", "image/..\\photo", "image/", "image/png\n")) {
            assertFailsWith<IllegalArgumentException> { artistPhotoExtension(mime) }
        }
    }
}
