package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadContentLengthTest {
    @Test
    fun `partial response uses total from content range`() {
        assertEquals(4_096L, downloadContentLength(206, "bytes 0-0/4096", "1"))
    }

    @Test
    fun `full response can use content length`() {
        assertEquals(4_096L, downloadContentLength(200, null, "4096"))
    }

    @Test
    fun `partial response does not mistake chunk length for total`() {
        assertNull(downloadContentLength(206, null, "1"))
    }

    @Test
    fun `unsatisfied range can still report total`() {
        assertEquals(4_096L, downloadContentLength(416, "bytes */4096", "0"))
    }

    @Test
    fun `failed and malformed responses leave length unknown`() {
        assertNull(downloadContentLength(403, "bytes 0-0/4096", "4096"))
        assertNull(downloadContentLength(206, "invalid", "1"))
        assertNull(downloadContentLength(206, "items 0-0/4096", "1"))
        assertNull(downloadContentLength(206, "bytes 1-1/4096", "1"))
        assertNull(downloadContentLength(416, "bytes 0-0/4096", "0"))
        assertNull(downloadContentLength(200, null, "0"))
    }
}
