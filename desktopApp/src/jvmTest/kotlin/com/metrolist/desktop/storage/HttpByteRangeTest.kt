package com.metrolist.desktop.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpByteRangeTest {
    @Test
    fun byteUnitIsCaseInsensitive() {
        listOf("bytes", "Bytes", "BYTES", "bYtEs").forEach { unit ->
            assertEquals(HttpByteRange(0, 2047, 2048), parseHttpByteRange("$unit 0-2047/2048"), unit)
        }
    }

    @Test
    fun invalidRangesAndUnknownUnitsAreStillRejected() {
        listOf(
            "Bytes 20-19/100",
            "Bytes 10-100/100",
            "Bytes 0-10/*",
            "Bytes 0-1/999999999999999999999",
            "Octets 0-1/2",
            "byte\u017f 0-1/2",
        ).forEach { value ->
            assertNull(parseHttpByteRange(value), value)
        }
    }
}
