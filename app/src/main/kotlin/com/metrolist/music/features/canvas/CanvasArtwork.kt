package com.metrolist.music.features.canvas

import java.text.Normalizer
import java.util.Locale

/** Metadata and remote video URLs for animated album artwork. */
data class CanvasArtwork(
    val name: String? = null,
    val artist: String? = null,
    val albumName: String? = null,
    val animated: String? = null,
    val videoUrl: String? = null,
    val animatedTall: String? = null,
) {
    val preferredAnimationUrl: String?
        get() = animated ?: videoUrl
}

fun String.normalizeForCanvasComparison(): String {
    val withoutMarks = Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
    return withoutMarks
        .lowercase(Locale.ROOT)
        .replace("&", "and")
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()
        .replace("\\s+".toRegex(), " ")
}
