package com.metrolist.shared.db

import android.content.Context
import android.util.Log
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import java.io.File

internal class LegacyPlaylistCoverRecovery(
    private val context: Context,
) : RoomDatabase.Callback() {
    override suspend fun onOpen(connection: SQLiteConnection) {
        recoverLegacyPlaylistCovers(connection, context.filesDir, context.cacheDir, context.packageName)
    }
}

internal fun recoverLegacyPlaylistCovers(
    connection: SQLiteConnection,
    filesDir: File,
    cacheDir: File,
    packageName: String,
) {
    val prefix = "content://$packageName.FileProvider/cache/"
    val filename = Regex("playlist_cover_crop_[0-9]+\\.jpg")
    val covers = mutableListOf<Pair<String, String>>()
    connection
        .prepare(
            "SELECT id, thumbnailUrl FROM playlist WHERE browseId IS NULL AND thumbnailUrl IS NOT NULL AND (customThumbnailUrl IS NULL OR customThumbnailUrl = thumbnailUrl)",
        ).use { statement ->
            while (statement.step()) covers += statement.getText(0) to statement.getText(1)
        }
    for ((id, uri) in covers) {
        if (!uri.startsWith(prefix)) continue
        val name = uri.removePrefix(prefix)
        if (!filename.matches(name)) continue
        val source = File(cacheDir, name)
        var created: File? = null
        try {
            if (!source.isFile || source.length() == 0L || source.canonicalFile.parentFile != cacheDir.canonicalFile) continue
            val directory = File(filesDir, "playlist_covers")
            check(directory.isDirectory || directory.mkdirs())
            val destination = File.createTempFile("legacy_cover_", ".jpg", directory)
            created = destination
            source.inputStream().use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(destination.length() > 0L)
            connection
                .prepare(
                    "UPDATE playlist SET thumbnailUrl = ?, customThumbnailUrl = ? WHERE id = ? AND thumbnailUrl = ? AND (customThumbnailUrl IS NULL OR customThumbnailUrl = ?) AND browseId IS NULL",
                ).use { statement ->
                    statement.bindText(1, destination.absolutePath)
                    statement.bindText(2, destination.absolutePath)
                    statement.bindText(3, id)
                    statement.bindText(4, uri)
                    statement.bindText(5, uri)
                    statement.step()
                }
            // Once the write succeeds, never delete a possibly referenced file on a later query failure.
            created = null
            connection.prepare("SELECT changes()").use { statement ->
                if (statement.step() && statement.getLong(0) == 0L) created = destination
            }
        } catch (error: Exception) {
            // A missing or unreadable old cache entry must not prevent opening the library.
            Log.w("LegacyPlaylistCover", "Could not recover a legacy playlist cover", error)
        } finally {
            created?.delete()
        }
    }
}
