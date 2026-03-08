package com.metrolist.shared.playback.ffmpeg

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArtworkSegmentTest {
    private val base = "https://artwork.example/cover/playlist.m3u8".toHttpUrl()

    @Test
    fun resolvesInitAndExplicitAndImplicitByteRanges() {
        val segments =
            assertNotNull(
                artworkSegments(
                    """
                    #EXTM3U
                    #EXT-X-MAP:URI="init.mp4",BYTERANGE="10@0"
                    #EXT-X-BYTERANGE:20@10
                    video.mp4
                    #EXT-X-BYTERANGE:30
                    video.mp4
                    #EXT-X-ENDLIST
                    """.trimIndent(),
                    base,
                ),
            )
        assertEquals(listOf(10L, 20L, 30L), segments.map { it.length })
        assertEquals(listOf(0L, 10L, 30L), segments.map { it.offset })
        assertEquals("https://artwork.example/cover/video.mp4", segments.last().url.toString())
    }

    @Test
    fun rejectsLiveEncryptedDiscontinuousAndUnsafePlaylists() {
        for (body in listOf(
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\npart.ts",
            "#EXT-X-DISCONTINUITY\npart.ts",
            "#EXT-X-STREAM-INF:BANDWIDTH=100\nvariant.m3u8",
            "https://other.example/part.ts",
            "http://artwork.example/part.ts",
            "#EXT-X-BYTERANGE:20\npart.ts",
            "#EXT-X-BYTERANGE:2@9223372036854775807\npart.ts",
            "#EXT-X-BYTERANGE:0@0\npart.ts",
        )) {
            assertNull(artworkSegments("#EXTM3U\n$body\n#EXT-X-ENDLIST", base), body)
        }
        assertNull(artworkSegments("#EXTM3U\npart.ts", base))
    }
}
