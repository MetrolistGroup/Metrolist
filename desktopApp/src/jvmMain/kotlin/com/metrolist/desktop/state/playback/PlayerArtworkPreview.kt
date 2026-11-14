package com.metrolist.desktop.state.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil3.Image

internal class PlayerArtworkPreview(
    private val songId: String? = null,
    private val url: String? = null,
) {
    var image: Image? by mutableStateOf(null)
        private set

    fun forTrack(
        songId: String?,
        url: String?,
    ): PlayerArtworkPreview = if (this.songId == songId && this.url == url) this else PlayerArtworkPreview(songId, url)

    fun retain(image: Image) {
        val previous = this.image
        if (previous == null || image.width.toLong() * image.height > previous.width.toLong() * previous.height) {
            this.image = image
        }
    }
}
