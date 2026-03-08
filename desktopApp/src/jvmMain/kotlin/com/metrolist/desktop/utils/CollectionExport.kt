package com.metrolist.desktop.utils

import com.metrolist.desktop.state.app.AppState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.export_collection
import com.metrolist.shared.generated.resources.export_failed
import com.metrolist.shared.generated.resources.export_saved
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.utils.CollectionExportFormat
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.openFileSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

fun exportCollection(
    title: String,
    format: CollectionExportFormat,
    loadSongs: suspend () -> List<SongItem>,
) {
    AppState.scope.launch {
        try {
            val file =
                FileKit
                    .openFileSaver(
                        suggestedName = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_"),
                        defaultExtension = format.extension,
                        allowedExtensions = setOf(format.extension),
                        dialogSettings = FileKitDialogSettings(title = getString(Res.string.export_collection, title)),
                    )?.file ?: return@launch
            writeCollectionExport(file, format, loadSongs)
            AppState.snackbarHostState.showSnackbar(getString(Res.string.export_saved))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AppState.snackbarHostState.showSnackbar(getString(Res.string.export_failed))
        }
    }
}

internal suspend fun writeCollectionExport(
    file: File,
    format: CollectionExportFormat,
    loadSongs: suspend () -> List<SongItem>,
) {
    val songs = loadSongs()
    withContext(Dispatchers.IO) {
        val temporary = Files.createTempFile(file.absoluteFile.parentFile.toPath(), ".playlist-", ".tmp")
        try {
            Files.writeString(temporary, format.render(songs))
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
