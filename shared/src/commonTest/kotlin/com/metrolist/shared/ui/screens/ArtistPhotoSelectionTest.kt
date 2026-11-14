package com.metrolist.shared.ui.screens

import com.metrolist.shared.viewmodels.ArtistPhoto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtistPhotoSelectionTest {
    @Test
    fun cachedPhotosRegainTheirDetailLinksAndKeepTheirOrder() {
        val first = ArtistPhoto("https://lastfm-img.freetls.fastly.net/i/u/ar0/f1c2577f5d06374747908506356ddd32.jpg")
        val second = ArtistPhoto("https://example.com/photo.jpg", "https://www.last.fm/existing")
        assertEquals(
            listOf(
                first.copy(pageUrl = "https://www.last.fm/music/Test+Artist/+images/f1c2577f5d06374747908506356ddd32"),
                second,
            ),
            artistGalleryPhotos("Test Artist", "youtube-thumbnail", listOf(first, second)),
        )
        assertEquals(listOf(ArtistPhoto("youtube-thumbnail")), artistGalleryPhotos("Test Artist", "youtube-thumbnail", emptyList()))
        assertEquals(emptyList(), artistGalleryPhotos("Test Artist", "", emptyList()))
    }

    @Test
    fun shrinkingAndReorderingKeepsTheSelectedPhotoOrTheNearestRemainingPage() {
        val original = List(20) { ArtistPhoto("photo-$it") }
        val selected = original[15].url
        val replacement = listOf(original[19], original[15], original[3], original[2], original[1])

        assertEquals(1, artistPhotoIndex(replacement, selected, 15))
        assertEquals(4, artistPhotoIndex(original.take(5), selected, 15))
        assertEquals(0, artistPhotoIndex(original.take(1), selected, 4))
        assertNull(artistPhotoIndex(emptyList(), selected, 15))
        assertEquals(0, artistPhotoIndex(original, null, -1))
    }
}
