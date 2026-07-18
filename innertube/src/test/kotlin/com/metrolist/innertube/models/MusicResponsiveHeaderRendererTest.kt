package com.metrolist.innertube.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicResponsiveHeaderRendererTest {
    @Test
    fun `buttons may be omitted`() {
        val renderer = Json.decodeFromString<MusicResponsiveHeaderRenderer>(
            """{"title":{"runs":[]},"subtitle":{"runs":[]}}""",
        )

        assertTrue(renderer.buttons.isEmpty())
    }
}
