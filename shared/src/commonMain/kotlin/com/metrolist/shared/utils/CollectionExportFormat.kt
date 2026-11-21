package com.metrolist.shared.utils

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlistimport.M3U_LITERAL_TITLE_COMMENT
import com.metrolist.shared.playlistimport.UniversalPlaylistParser

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
                        val artists =
                            song.artists
                                .map { it.name }
                                .filter(String::isNotBlank)
                                .joinToString(", ")
                        val label = (if (artists.isEmpty()) song.title else "$artists - ${song.title}").replace(m3uLineBreaks, " ")
                        val trimmedLabel = label.trim()
                        if (artists.isEmpty() && UniversalPlaylistParser.splitM3uDisplayLabel(trimmedLabel) != ("" to trimmedLabel)) {
                            // Keep the ordinary display label for other players; our importer can skip guessing.
                            appendLine(M3U_LITERAL_TITLE_COMMENT)
                        }
                        appendLine("#EXTINF:${song.duration ?: -1},$label")
                        appendLine(song.exportUri)
                    }
                }
            }
        }
}

private val m3uLineBreaks = Regex("[\\r\\n]+")

private val SongItem.exportUri: String
    get() = if (isLocal) id else "https://music.youtube.com/watch?v=$id"
