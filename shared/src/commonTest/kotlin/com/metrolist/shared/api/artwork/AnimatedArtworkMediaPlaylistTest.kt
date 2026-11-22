package com.metrolist.shared.api.artwork

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimatedArtworkMediaPlaylistTest {
    private val playlistUrl =
        "https://mvod.itunes.apple.com/itunes-assets/HLSVideo221/v4/79/69/e7/7969e779/P1388724165_Anull_video_gr240_sdr_664x886.m3u8"

    @Test
    fun contiguousSingleMp4ByterangePlaylistCollapsesToSingleFile() {
        val manifest =
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:3
            #EXT-X-VERSION:7
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-MAP:URI="P1388724165_Anull_video_gr240_sdr_664x886-.mp4",BYTERANGE="879@0"
            #EXTINF:3.36667,
            #EXT-X-BYTERANGE:766650@879
            P1388724165_Anull_video_gr240_sdr_664x886-.mp4
            #EXTINF:3.36667,
            #EXT-X-BYTERANGE:928694@767529
            P1388724165_Anull_video_gr240_sdr_664x886-.mp4
            #EXTINF:3.40000,
            #EXT-X-BYTERANGE:911158@1696223
            P1388724165_Anull_video_gr240_sdr_664x886-.mp4
            #EXTINF:3.40000,
            #EXT-X-BYTERANGE:980228@2607381
            P1388724165_Anull_video_gr240_sdr_664x886-.mp4
            #EXTINF:3.40000,
            #EXT-X-BYTERANGE:1011316@3587609
            P1388724165_Anull_video_gr240_sdr_664x886-.mp4
            #EXT-X-ENDLIST
            """.trimIndent()

        val plan = assertIs<AnimatedArtworkDownloadPlan.SingleFile>(parseAnimatedArtworkMediaPlaylist(playlistUrl, manifest))
        assertEquals(
            "https://mvod.itunes.apple.com/itunes-assets/HLSVideo221/v4/79/69/e7/7969e779/P1388724165_Anull_video_gr240_sdr_664x886-.mp4",
            plan.url,
        )
        assertEquals(4_598_925L, plan.byteLength)
    }

    @Test
    fun implicitByterangeOffsetsAndSeparateSegmentsResolveInOrder() {
        val manifest =
            """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:2.0,
            seg1.m4s
            #EXTINF:2.0,
            #EXT-X-BYTERANGE:100@0
            seg2.m4s
            #EXTINF:2.0,
            #EXT-X-BYTERANGE:150
            seg2.m4s
            #EXT-X-ENDLIST
            """.trimIndent()

        val plan = assertIs<AnimatedArtworkDownloadPlan.Segments>(parseAnimatedArtworkMediaPlaylist(playlistUrl, manifest))
        assertEquals(4, plan.segments.size)
        assertEquals("init.mp4", plan.segments[0].url.substringAfterLast('/'))
        assertEquals("seg1.m4s", plan.segments[1].url.substringAfterLast('/'))
        assertEquals("bytes=0-99", plan.segments[2].byteRange?.httpRangeHeader)
        assertEquals("bytes=100-249", plan.segments[3].byteRange?.httpRangeHeader)
    }

    @Test
    fun rejectsEncryptedUntrustedAndMpegTsPlaylists() {
        val encrypted =
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://mvod.itunes.apple.com/key"
            #EXTINF:2.0,
            seg1.mp4
            """.trimIndent()
        assertNull(parseAnimatedArtworkMediaPlaylist(playlistUrl, encrypted))

        val untrusted =
            """
            #EXTM3U
            #EXTINF:2.0,
            https://evil.example.com/seg1.mp4
            """.trimIndent()
        assertNull(parseAnimatedArtworkMediaPlaylist(playlistUrl, untrusted))

        val mpegTs =
            """
            #EXTM3U
            #EXTINF:2.0,
            seg1.ts
            """.trimIndent()
        assertNull(parseAnimatedArtworkMediaPlaylist(playlistUrl, mpegTs))
    }

    @Test
    fun validatesRangedHttpResponsesStrictly() {
        val range = AnimatedArtworkByteRange(length = 100L, offset = 200L)
        assertTrue(isValidAnimatedArtworkRangeResponse(206, "bytes 200-299/500", range, allowFullResponseAtZero = false))
        assertTrue(isValidAnimatedArtworkRangeResponse(206, "bytes 200-299/*", range, allowFullResponseAtZero = false))
        assertFalse(isValidAnimatedArtworkRangeResponse(200, null, range, allowFullResponseAtZero = false))
        assertFalse(isValidAnimatedArtworkRangeResponse(206, "bytes 0-499/500", range, allowFullResponseAtZero = false))
        assertFalse(isValidAnimatedArtworkRangeResponse(206, null, range, allowFullResponseAtZero = false))

        val zeroRange = AnimatedArtworkByteRange(length = 500L, offset = 0L)
        assertTrue(isValidAnimatedArtworkRangeResponse(200, null, zeroRange, allowFullResponseAtZero = true))
        assertFalse(isValidAnimatedArtworkRangeResponse(200, null, zeroRange, allowFullResponseAtZero = false))
    }
}
