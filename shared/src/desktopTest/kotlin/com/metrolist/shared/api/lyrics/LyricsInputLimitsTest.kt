package com.metrolist.shared.api.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsInputLimitsTest {
    @Test
    fun deeplyNestedTtmlIsRejectedBeforeDomParsing() {
        val nested = "<div>".repeat(70) + "<p begin=\"0s\">line</p>" + "</div>".repeat(70)
        val ttml = "<tt><body>$nested</body></tt>"

        assertEquals("", parseTtmlToMlx(ttml, "title", "artist", "test"))
    }

    @Test
    fun oversizedMlxLineIsRejected() {
        val mlx = "[meta]\n[lyrics]\n" + "x".repeat(16 * 1_024 + 1)

        assertTrue(MlxParser.parse(mlx).lines.isEmpty())
    }
}
