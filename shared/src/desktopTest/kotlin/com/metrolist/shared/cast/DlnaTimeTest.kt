package com.metrolist.shared.cast

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class DlnaTimeTest {
    @Test
    fun protocolTimestampsUseAsciiDigitsRegardlessOfLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("01:02:03", formatDlnaTime(3_723_000L))
            assertEquals("00:00:00", formatDlnaTime(-1L))
            assertEquals("100:00:00", formatDlnaTime(360_000_000L))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
