package com.metrolist.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedInputTest {
    @Test
    fun acceptsExactLimitAndRejectsOversizedStreamsWithoutReadingTheRest() {
        assertEquals("abc", "abc".byteInputStream().readBoundedText(3))
        val input = ByteArray(20_000) { 65 }.inputStream()
        assertNull(input.readBoundedText(100))
        assertEquals(19_899, input.available())
        assertEquals("東京", "東京".byteInputStream().readBoundedText(6))
    }
}
