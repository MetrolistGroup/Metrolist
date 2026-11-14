package com.metrolist.android.playlistimport

import android.content.Context
import android.net.Uri
import com.metrolist.shared.playlistimport.ImportErrorKey
import com.metrolist.shared.playlistimport.ImportException
import com.metrolist.shared.playlistimport.ImportedPlaylistData
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.playlistimport.UniversalPlaylistParser
import com.metrolist.shared.playlistimport.readPlaylistText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun containsAndroidInaccessibleReference(tracks: List<PlaylistImportManager.CsvTrack>): Boolean =
    tracks.any {
        it.directReference?.let(PlaylistImportManager::isLocalReference) == true ||
            (it.directReference != null && (it.title.isBlank() || it.artist.isBlank()))
    }

suspend fun readPlaylistFile(
    context: Context,
    uri: Uri,
    fileName: String,
    title: String,
    source: String,
): Result<ImportedPlaylistData> =
    withContext(Dispatchers.IO) {
        try {
            val text =
                context.contentResolver.openInputStream(uri)?.use(::readPlaylistText)
                    ?: throw ImportException(ImportErrorKey.FileReadFailed)
            val data = UniversalPlaylistParser.prepareFile(text, fileName, title, source)
            if (containsAndroidInaccessibleReference(data.tracks)) {
                throw ImportException(ImportErrorKey.UnsupportedPlaylistEntries)
            }
            Result.success(data)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ImportException) {
            Result.failure(error)
        } catch (_: Exception) {
            Result.failure(ImportException(ImportErrorKey.FileReadFailed))
        }
    }
