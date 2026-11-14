package com.metrolist.shared.youtube.playback

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackEventPreviewTest {
    private val event = PlaybackEvent(1, 0, PlaybackEventLevel.INFO, PlaybackEventKind.PLAYER, "Ready")

    @Test
    fun preservesMetadataAndSortsSmallDetails() {
        val preview =
            event
                .copy(
                    videoId = "video",
                    clientName = "client",
                    profileId = "profile",
                    details = mapOf("z" to "last", "a" to "first"),
                ).previewText { "time" }

        assertEquals("time INFO PLAYER client=client profile=profile videoId=video\n  Ready\n  a=first\n  z=last", preview)
    }

    @Test
    fun capsHugeMessagesDetailsAndIdentifiersWithoutChangingExports() {
        val huge = "x".repeat(1_000_000) + "end-of-diagnostic"
        val events =
            listOf(
                event.copy(message = huge),
                event.copy(details = mapOf("response" to huge)),
                event.copy(clientName = huge),
                event.copy(profileId = huge),
                event.copy(videoId = huge),
            )
        for (large in events) {
            val preview = large.previewText { "time" }
            assertEquals(PLAYBACK_EVENT_PREVIEW_CHARS, preview.length)
            assertTrue(preview.endsWith('…'))
            assertContains(large.toLogLine(), "end-of-diagnostic")
        }
    }

    @Test
    fun doesNotTruncateAnExactFitOrSplitASurrogatePair() {
        val prefix = event.copy(message = "").previewText { "time" }
        val available = PLAYBACK_EVENT_PREVIEW_CHARS - prefix.length
        val exact = event.copy(message = "x".repeat(available)).previewText { "time" }
        assertEquals(PLAYBACK_EVENT_PREVIEW_CHARS, exact.length)
        assertTrue(exact.endsWith('x'))

        val preview = event.copy(message = "x".repeat(available - 2) + "\uD83D\uDE00tail").previewText { "time" }
        assertTrue(preview.length <= PLAYBACK_EVENT_PREVIEW_CHARS)
        assertTrue(preview.endsWith('…'))
        assertFalse(preview[preview.lastIndex - 1].isHighSurrogate())
    }
}
