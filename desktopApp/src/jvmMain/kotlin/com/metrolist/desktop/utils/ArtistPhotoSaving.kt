package com.metrolist.desktop.utils

import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_download
import com.metrolist.shared.viewmodels.ArtistPhoto
import com.metrolist.shared.viewmodels.downloadArtistPhoto
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.openFileSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import java.nio.file.Files
import java.nio.file.StandardCopyOption

suspend fun saveArtistPhoto(
    photo: ArtistPhoto,
    artistName: String,
): Boolean {
    val download = downloadArtistPhoto(photo, artistName)
    val destination =
        FileKit
            .openFileSaver(
                suggestedName = download.baseName,
                defaultExtension = download.extension,
                allowedExtensions = setOf(download.extension),
                dialogSettings = FileKitDialogSettings(title = getString(Res.string.action_download)),
            )?.file ?: return false
    withContext(Dispatchers.IO) {
        val temporary = Files.createTempFile(destination.absoluteFile.parentFile.toPath(), ".artist-photo-", ".${download.extension}")
        try {
            Files.write(temporary, download.bytes)
            Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
    return true
}
