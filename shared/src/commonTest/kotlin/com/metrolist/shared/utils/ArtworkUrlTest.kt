package com.metrolist.shared.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class ArtworkUrlTest {
    @Test
    fun palettesUseOneSourceForThumbnailSizeVariants() {
        val google = "https://lh3.googleusercontent.com/cover"
        for (transform in listOf("w60-h60", "w544-h544", "s1200", "s2400")) {
            assertEquals("$google=s256-l90-rj#revision", artworkColorSourceUrl("$google=$transform-l90-rj#revision"))
        }
        for ((directory, extension) in listOf("vi" to "jpg", "vi_webp" to "webp")) {
            val base = "https://i.ytimg.com/$directory/video/"
            for (variant in listOf("mqdefault", "hqdefault", "sddefault", "hq720", "maxresdefault")) {
                assertEquals("${base}mqdefault.$extension#revision", artworkColorSourceUrl("$base$variant.$extension?sqp=size#revision"))
            }
        }
        for (url in listOf(
            "https://example.com/vi/video/hqdefault.jpg",
            "https://i.ytimg.com/playlist/cover.jpg?next=/vi/video/hqdefault.jpg",
            "content://covers/song",
        )) {
            assertEquals(url, artworkColorSourceUrl(url))
        }
    }

    @Test
    fun squareCropFlagsKeepWidthAndHeightTransforms() {
        for (host in listOf("yt3.googleusercontent.com", "yt3.ggpht.com", "lh3.googleusercontent.com")) {
            val base = "https://$host/cover"
            for (transform in listOf("w60-h60", "w544-h544", "s1200")) {
                val url = "$base=$transform-s-l90-rj?version=2#metrolist-refresh=1"
                for (size in listOf(256, 544, 1200, 2400)) {
                    val expected = "$base=w$size-h$size-s-l90-rj?version=2#metrolist-refresh=1"
                    assertEquals(expected, sizedArtworkUrl(url, size))
                    assertEquals(expected, bucketedArtworkUrl(url, size))
                    assertEquals(expected, sizedArtworkUrl(expected, size))
                }
                assertEquals(sizedArtworkUrl(url, 1200), canonicalArtworkUrl(url))
                assertEquals(sizedArtworkUrl(url, 2400), highResolutionArtworkUrl(url))
                assertEquals(listOf(256, 544, 1200, 2400).map { sizedArtworkUrl(url, it) }, artworkCacheCandidates(url, 1))
            }
        }
    }

    @Test
    fun videoArtworkUsesLargerSourcesWithoutChangingSmallThumbnails() {
        for ((directory, extension) in listOf("vi" to "jpg", "vi_webp" to "webp")) {
            val base = "https://i.ytimg.com/$directory/video/"
            val small = "${base}mqdefault.$extension#revision"
            val large = "${base}maxresdefault.$extension#revision"
            assertEquals(small, bucketedArtworkUrl("${base}mqdefault.$extension?sqp=transform#revision", 100))
            assertEquals(large, bucketedArtworkUrl(small, 512))
            assertEquals(large, highResolutionArtworkUrl(small))
            assertEquals(large, sizedArtworkUrl(large, 100))
            assertEquals(small, videoArtworkFallbackUrl(large))
            assertEquals(null, videoArtworkFallbackUrl(small))
        }
        assertEquals(null, videoArtworkFallbackUrl("https://example.com/vi/video/maxresdefault.jpg"))
        assertEquals(null, videoArtworkFallbackUrl("https://i.ytimg.com/vi/video/maxresdefault.jpg/unrelated"))
    }

    @Test
    fun retainedVideoArtworkKeepsItsOriginalRenditionKey() {
        for ((directory, extension) in listOf("vi" to "jpg", "vi_webp" to "webp")) {
            val original = "https://i.ytimg.com/$directory/video/mqdefault.$extension"
            assertEquals(original, persistentArtworkUrl(original))
            assertEquals("$original#revision", persistentArtworkUrl("$original?sqp=transform#revision"))
            assertEquals(original.replace("mqdefault", "maxresdefault"), canonicalArtworkUrl(original))
        }
        val google = "https://lh3.googleusercontent.com/cover=w544-h544-s-l90-rj"
        assertEquals(canonicalArtworkUrl(google), persistentArtworkUrl(google))
    }

    @Test
    fun otherTransformsAndArtworkSourcesKeepTheirBehavior() {
        val base = "https://lh3.googleusercontent.com/cover"
        for (transform in listOf("w544-h544", "s544")) {
            assertEquals("$base=s1200-l90-rj", canonicalArtworkUrl("$base=$transform-l90-rj"))
        }
        assertEquals("$base=s2400", canonicalArtworkUrl("$base=s1600"))
        val video = "https://i.ytimg.com/vi/video/hqdefault.jpg"
        assertEquals(
            "$video#revision",
            canonicalArtworkUrl("$video?sqp=transform&rs=signature#revision"),
        )
        for (url in listOf(
            "https://i.ytimg.com/playlist/cover.jpg?sqp=required",
            "https://i.ytimg.com/playlist/cover.jpg?next=/vi/video/mqdefault.jpg",
            "content://covers/song",
            "https://example.com/cover=s544-s",
        )) {
            assertEquals(url, canonicalArtworkUrl(url))
        }
    }
}
