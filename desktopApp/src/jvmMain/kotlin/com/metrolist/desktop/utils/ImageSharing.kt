package com.metrolist.desktop.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.openFileSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

suspend fun saveImage(
    image: ImageBitmap,
    title: String,
    filename: String,
): Boolean {
    val destination =
        FileKit
            .openFileSaver(
                suggestedName = filename.substringBeforeLast('.', filename),
                defaultExtension = "png",
                allowedExtensions = setOf("png"),
                dialogSettings = FileKitDialogSettings(title = title),
            )?.file ?: return false
    withContext(Dispatchers.IO) {
        val temporary = Files.createTempFile(destination.absoluteFile.parentFile.toPath(), ".image-", ".png")
        try {
            check(ImageIO.write(image.toAwtImage(), "png", temporary.toFile()))
            Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
    return true
}
