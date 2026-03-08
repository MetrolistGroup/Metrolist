package com.metrolist.shared.utils

import com.metrolist.shared.model.SongItem

enum class CollectionExportFormat(
    val extension: String,
    val mimeType: String,
) {
    CSV("csv", "text/csv"),
    M3U("m3u", "audio/x-mpegurl"),
    ;

    fun render(songs: List<SongItem>): String =
        when (this) {
            CSV -> {
                buildString {
                    appendLine("Title,Artist,Album,URL")
                    songs.forEach { song ->
                        appendLine(
                            listOf(
                                song.title,
                                song.artists.joinToString(", ") { it.name },
                                song.album?.name.orEmpty(),
                                song.exportUri,
                            ).joinToString(",", transform = ::csvCell),
                        )
                    }
                }
            }

            M3U -> {
                buildString {
                    appendLine("#EXTM3U")
                    songs.forEach { song ->
                        appendLine("#EXTINF:${song.duration ?: -1},${song.artists.joinToString(", ") { it.name }} - ${song.title}")
                        appendLine(song.exportUri)
                    }
                }
            }
        }
}

private val SongItem.exportUri: String
    get() = if (isLocal) id else "https://music.youtube.com/watch?v=$id"
