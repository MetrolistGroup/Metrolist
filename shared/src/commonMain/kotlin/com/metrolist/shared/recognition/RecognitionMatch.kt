package com.metrolist.shared.recognition

import com.metrolist.shared.model.SongItem

fun SongItem.matchesRecognition(result: RecognitionResult): Boolean {
    fun String.normalized() = lowercase().filter { it.isLetterOrDigit() }

    val title = result.title.normalized()
    val artist = result.artist.normalized()
    return title.isNotEmpty() &&
        artist.isNotEmpty() &&
        this.title.normalized() == title &&
        (
            artists.any { it.name.normalized() == artist } ||
                artists.joinToString("") { it.name }.normalized() == artist
        )
}
