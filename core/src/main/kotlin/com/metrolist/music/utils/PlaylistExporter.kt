package com.metrolist.music.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.entities.PlaylistSong
import java.io.File
import java.io.FileWriter
import java.io.IOException

object PlaylistExporter {
    fun exportPlaylistAsCSV(
        context: Context,
        playlistName: String,
        songs: List<PlaylistSong>,
    ): Result<File> =
        try {
            val csvContent =
                buildString {
                    // Add CSV header
                    append("Title,Artist,Album,YouTube Video ID\n")

                    // Add each song as a CSV row
                    songs.forEach { playlistSong ->
                        val song = playlistSong.song.song
                        val artists = playlistSong.song.artists
                        val album = playlistSong.song.album
                        append("\"${song.title.replace("\"", "\"\"")}\"")
                        append(",")
                        append("\"${artists.joinToString("; ") { it.name.replace("\"", "\"\"") }}\"")
                        append(",")
                        append("\"${album?.title?.replace("\"", "\"\"") ?: ""}\"")
                        append(",")
                        append("${song.id}")
                        append("\n")
                    }
                }

            // Save to file
            val file = createExportFile(context, "$playlistName.csv")
            FileWriter(file).use { it.write(csvContent) }

            Result.success(file)
        } catch (e: IOException) {
            Result.failure(e)
        }

    fun exportPlaylistAsM3U(
        context: Context,
        playlistName: String,
        songs: List<PlaylistSong>,
    ): Result<File> =
        try {
            val m3uContent =
                buildString {
                    // Add M3U header
                    append("#EXTM3U\n")

                    // Add each song as M3U entry
                    songs.forEach { playlistSong ->
                        val song = playlistSong.song.song
                        val artists = playlistSong.song.artists
                        append("#EXTINF:${song.duration},")
                        append("${artists.joinToString(";") { it.name }} - ${song.title}")
                        append("\n")
                        append("https://youtube.com/watch?v=${song.id}\n")
                    }
                }

            // Save to file
            val file = createExportFile(context, "$playlistName.m3u")
            FileWriter(file).use { it.write(m3uContent) }

            Result.success(file)
        } catch (e: IOException) {
            Result.failure(e)
        }
}

fun exportYouTubePlaylistAsCSV(
    context: Context,
    playlistName: String,
    songs: List<SongItem>,
): Result<File> =
    try {
        val csvContent =
            buildString {
                // Add CSV header
                append("Title,Artist,Album,YouTube Video ID\n")

                // Add each song as a CSV row
                songs.forEach { songItem ->
                    append("\"${songItem.title.replace("\"", "\"\"")}\"")
                    append(",")
                    append("\"${songItem.artists.joinToString("; ") { it.name.replace("\"", "\"\"") }}\"")
                    append(",")
                    append("\"${songItem.album?.name?.replace("\"", "\"\"") ?: ""}\"")
                    append(",")
                    append("${songItem.id}")
                    append("\n")
                }
            }

        // Save to file
        val file = createExportFile(context, "$playlistName.csv")
        FileWriter(file).use { it.write(csvContent) }

        Result.success(file)
    } catch (e: IOException) {
        Result.failure(e)
    }

fun exportYouTubePlaylistAsM3U(
    context: Context,
    playlistName: String,
    songs: List<SongItem>,
): Result<File> =
    try {
        val m3uContent =
            buildString {
                // Add M3U header
                append("#EXTM3U\n")

                // Add each song as M3U entry
                songs.forEach { songItem ->
                    append("#EXTINF:${songItem.duration},")
                    append("${songItem.artists.joinToString(" - ") { it.name }} - ${songItem.title}")
                    append("\n")
                    // For M3U, we would typically include a URL, but since we don't have direct URLs,
                    // we'll use a placeholder that indicates this is a YouTube Music track
                    append("#YTM:${songItem.id}\n")
                }
            }

        // Save to file
        val file = createExportFile(context, "$playlistName.m3u")
        FileWriter(file).use { it.write(m3uContent) }

        Result.success(file)
    } catch (e: IOException) {
        Result.failure(e)
    }

private fun createExportFile(
    context: Context,
    filename: String,
): File {
    // Create directory if it doesn't exist
    val exportDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "MetrolistExports")
    if (!exportDir.exists()) {
        exportDir.mkdirs()
    }

    // Create file with unique name (add timestamp if file exists)
    val baseFilename = filename.substringBeforeLast('.')
    val extension = filename.substringAfterLast('.', "")
    var exportFile = File(exportDir, filename)
    var counter = 1

    while (exportFile.exists()) {
        val newFilename =
            if (extension.isNotEmpty()) {
                "${baseFilename}_$counter.$extension"
            } else {
                "${baseFilename}_$counter"
            }
        exportFile = File(exportDir, newFilename)
        counter++
    }

    exportFile.createNewFile()
    return exportFile
}

private fun getFileUri(
    context: Context,
    file: File,
): Uri =
    FileProvider.getUriForFile(
        context,
        "${context.packageName}.FileProvider",
        file,
    )

fun getExportFileUri(
    context: Context,
    file: File,
): Uri = getFileUri(context, file)

/**
 * Copy a generated export file into the public Documents/MetrolistExports folder using MediaStore (scoped storage).
 * Returns the Uri to the public copy on success.
 */
fun saveToPublicDocuments(
    context: Context,
    source: File,
    mimeType: String,
    subdirectory: String = "MetrolistExports",
): Result<Uri> {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relativePath = Environment.DIRECTORY_DOCUMENTS + "/" + subdirectory

            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

            // Use the primary external volume for generic files
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val destUri =
                resolver.insert(collection, values)
                    ?: return Result.failure(IOException("Failed to create destination in MediaStore"))

            resolver.openOutputStream(destUri)?.use { out ->
                source.inputStream().use { input ->
                    input.copyTo(out)
                }
            } ?: return Result.failure(IOException("Failed to open output stream for MediaStore uri"))

            // Mark as not pending so it becomes visible
            val complete = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(destUri, complete, null, null)

            Result.success(destUri)
        } else {
            // Best-effort fallback: keep the file in app-scoped Documents and return a sharable uri
            // Older Android versions would require WRITE_EXTERNAL_STORAGE for true public Documents
            Result.success(getExportFileUri(context, source))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
