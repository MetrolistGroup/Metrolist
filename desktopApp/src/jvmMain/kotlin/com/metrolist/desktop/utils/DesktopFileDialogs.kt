package com.metrolist.desktop.utils

import com.metrolist.shared.utils.OsUtil
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal suspend fun pickDesktopFile(
    title: String,
    extensions: List<String> = emptyList(),
): File? {
    if (!OsUtil.isLinux) {
        if (extensions.isEmpty()) {
            return FileKit.openFilePicker(dialogSettings = FileKitDialogSettings(title = title))?.file
        }
        return FileKit
            .openFilePicker(
                type = FileKitType.File(extensions),
                dialogSettings = FileKitDialogSettings(title = title),
            )?.file
    }
    return onFileDialogThread {
        val chooser =
            JFileChooser().apply {
                dialogTitle = title
                if (extensions.isNotEmpty()) fileFilter = FileNameExtensionFilter(null, *extensions.toTypedArray())
            }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
}

internal suspend fun saveDesktopFile(
    title: String,
    suggestedName: String,
    extension: String,
): File? {
    if (!OsUtil.isLinux) {
        return FileKit
            .openFileSaver(
                suggestedName = suggestedName,
                defaultExtension = extension,
                allowedExtensions = setOf(extension),
                dialogSettings = FileKitDialogSettings(title = title),
            )?.file
    }
    return onFileDialogThread {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
        try {
            dialog.file = "$suggestedName.$extension"
            dialog.setFilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
            dialog.isVisible = true
            dialog.files.firstOrNull()
        } finally {
            dialog.dispose()
        }
    }
}

internal suspend fun <T> onFileDialogThread(block: () -> T): T = withContext(Dispatchers.Swing) { block() }
