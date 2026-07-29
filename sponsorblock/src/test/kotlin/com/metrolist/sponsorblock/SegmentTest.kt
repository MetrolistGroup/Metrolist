package com.metrolist.sponsorblock

import com.metrolist.sponsorblock.models.ApiSegment
import com.metrolist.sponsorblock.models.Segment
import com.metrolist.sponsorblock.models.SponsorBlockCategory
import com.metrolist.sponsorblock.models.nextSegmentAfter
import com.metrolist.sponsorblock.models.sanitized
import com.metrolist.sponsorblock.models.segmentAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentTest {
    private fun segment(startMs: Long, endMs: Long) =
        Segment(SponsorBlockCategory.MUSIC_OFFTOPIC, startMs, endMs)

    @Test
    fun `hash prefix only leaks four characters of the video id`() {
        // SHA-256("dQw4w9WgXcQ") starts with 5f6b0b4e.
        assertEquals("5f6b", SponsorBlock.hashPrefixOf("dQw4w9WgXcQ"))
        assertEquals(4, SponsorBlock.hashPrefixOf("anything").length)
    }

    @Test
    fun `touching segments merge into a single seek`() {
        // An intro that runs straight into a sponsor read. Skipping these
        // separately would seek twice and sound like a stutter.
        val merged = listOf(segment(0, 5_000), segment(5_000, 12_000)).sanitized()

        assertEquals(1, merged.size)
        assertEquals(0, merged.first().startMs)
        assertEquals(12_000, merged.first().endMs)
    }

    @Test
    fun `overlapping segments merge and keep the furthest end`() {
        val merged = listOf(segment(10_000, 20_000), segment(12_000, 15_000)).sanitized()

        assertEquals(1, merged.size)
        assertEquals(20_000, merged.first().endMs)
    }

    @Test
    fun `unsorted input is ordered and very short segments are dropped`() {
        val merged = listOf(
            segment(30_000, 40_000),
            segment(1_000, 1_200), // 200ms - not worth a seek
            segment(5_000, 9_000),
        ).sanitized()

        assertEquals(listOf(5_000L, 30_000L), merged.map { it.startMs })
    }

    @Test
    fun `lookup finds the covering segment and treats the end as exclusive`() {
        val segments = listOf(segment(5_000, 9_000)).sanitized()

        assertNull(segments.segmentAt(4_999))
        assertEquals(5_000L, segments.segmentAt(5_000)?.startMs)
        assertEquals(5_000L, segments.segmentAt(8_999)?.startMs)
        assertNull("end is exclusive, so this must not skip again", segments.segmentAt(9_000))
    }

    @Test
    fun `next segment is the nearest one ahead of the playhead`() {
        val segments = listOf(segment(5_000, 9_000), segment(30_000, 40_000)).sanitized()

        assertEquals(5_000L, segments.nextSegmentAfter(0)?.startMs)
        assertEquals(30_000L, segments.nextSegmentAfter(9_000)?.startMs)
        assertNull(segments.nextSegmentAfter(40_000))
    }

    @Test
    fun `api entries convert seconds to milliseconds`() {
        val converted = ApiSegment(
            category = "music_offtopic",
            segment = listOf(1.5, 12.25),
        ).toSegment()

        assertEquals(1_500L, converted?.startMs)
        assertEquals(12_250L, converted?.endMs)
        assertEquals(10_750L, converted?.durationMs)
        assertEquals(SponsorBlockCategory.MUSIC_OFFTOPIC, converted?.category)
    }

    @Test
    fun `unusable api entries are rejected rather than skipped blindly`() {
        // A category this build does not know about.
        assertNull(ApiSegment(category = "poi_highlight", segment = listOf(1.0, 2.0)).toSegment())
        // Not a skip action.
        assertNull(
            ApiSegment(category = "sponsor", actionType = "mute", segment = listOf(1.0, 2.0))
                .toSegment(),
        )
        // Voted down by the community.
        assertNull(
            ApiSegment(category = "sponsor", segment = listOf(1.0, 2.0), votes = -3).toSegment(),
        )
        // Malformed or inverted ranges.
        assertNull(ApiSegment(category = "sponsor", segment = listOf(5.0)).toSegment())
        assertNull(ApiSegment(category = "sponsor", segment = listOf(9.0, 4.0)).toSegment())
    }

    @Test
    fun `an empty segment list never reports a skip`() {
        val empty = emptyList<Segment>().sanitized()

        assertTrue(empty.isEmpty())
        assertNull(empty.segmentAt(0))
        assertNull(empty.nextSegmentAfter(0))
    }
}
