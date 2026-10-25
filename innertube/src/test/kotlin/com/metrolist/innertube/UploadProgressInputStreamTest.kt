package com.metrolist.innertube

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class UploadProgressInputStreamTest {
    @Test
    fun `reports streamed bytes without exceeding completion`() {
        val content = ByteArray(10) { it.toByte() }
        val progress = mutableListOf<Float>()

        val streamed =
            UploadProgressInputStream(ByteArrayInputStream(content), content.size.toLong(), progress::add)
                .use { it.readBytes() }

        assertArrayEquals(content, streamed)
        assertTrue(progress.zipWithNext().all { (previous, next) -> previous <= next })
        assertEquals(1f, progress.last(), 0f)
    }
}
