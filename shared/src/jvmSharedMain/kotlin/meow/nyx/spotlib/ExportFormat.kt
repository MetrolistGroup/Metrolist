package meow.nyx.spotlib

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ExportFormat {
    private val prettyJson =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            ignoreUnknownKeys = true
        }

    fun toJson(export: LibraryExport): String = prettyJson.encodeToString(export)

    fun toCsv(export: LibraryExport): String {
        val output = StringBuilder()
        output.append("folder,playlist,playlist_uri,position,type,title,artists,album,album_uri,duration_ms,")
        output.append("track_number,disc_number,explicit,is_local,is_playable,added_at,added_by,track_uri\n")
        for (playlist in export.playlists) {
            val folder = playlist.folderPath.joinToString(" / ")
            for (track in playlist.tracks) {
                val row =
                    listOf(
                        folder,
                        playlist.name,
                        playlist.uri,
                        track.position.toString(),
                        track.type,
                        track.name,
                        track.artists.joinToString(", "),
                        track.album.orEmpty(),
                        track.albumUri.orEmpty(),
                        track.durationMs?.toString().orEmpty(),
                        track.trackNumber?.toString().orEmpty(),
                        track.discNumber?.toString().orEmpty(),
                        track.explicit?.toString().orEmpty(),
                        track.isLocal.toString(),
                        track.isPlayable?.toString().orEmpty(),
                        track.addedAt.orEmpty(),
                        track.addedBy.orEmpty(),
                        track.uri,
                    )
                output.append(row.joinToString(",", transform = ::escape)).append('\n')
            }
        }
        return output.toString()
    }

    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
}
