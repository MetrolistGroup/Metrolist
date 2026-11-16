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
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal suspend fun pickDesktopFile(
    title: String,
    extensions: List<String> = emptyList(),
): File? =
    desktopFileDialog(
        fileKitDialog = {
            if (extensions.isEmpty()) {
                FileKit.openFilePicker(dialogSettings = FileKitDialogSettings(title = title))?.file
            } else {
                FileKit
                    .openFilePicker(
                        type = FileKitType.File(extensions),
                        dialogSettings = FileKitDialogSettings(title = title),
                    )?.file
            }
        },
        nativeDialog = {
            val chooser =
                JFileChooser().apply {
                    dialogTitle = title
                    if (extensions.isNotEmpty()) fileFilter = FileNameExtensionFilter(null, *extensions.toTypedArray())
                }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        },
    )

internal suspend fun saveDesktopFile(
    title: String,
    suggestedName: String,
    extension: String,
): File? =
    desktopFileDialog(
        fileKitDialog = {
            FileKit
                .openFileSaver(
                    suggestedName = suggestedName,
                    defaultExtension = extension,
                    allowedExtensions = setOf(extension),
                    dialogSettings = FileKitDialogSettings(title = title),
                )?.file
        },
        nativeDialog = {
            val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
            try {
                dialog.file = "$suggestedName.$extension"
                dialog.setFilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
                dialog.isVisible = true
                dialog.files.firstOrNull()
            } finally {
                dialog.dispose()
            }
        },
    )

internal suspend fun <T> desktopFileDialog(
    isLinux: Boolean = OsUtil.isLinux,
    portalAvailable: () -> Boolean = ::isFileChooserPortalAvailable,
    fileKitDialog: suspend () -> T,
    nativeDialog: () -> T,
): T {
    if (!isLinux || withContext(Dispatchers.IO) { portalAvailable() }) return fileKitDialog()
    return withContext(Dispatchers.Swing) { nativeDialog() }
}

private fun isFileChooserPortalAvailable(): Boolean =
    try {
        DBusConnectionBuilder.forSessionBus().build().use { connection ->
            connection
                .getRemoteObject(
                    "org.freedesktop.portal.Desktop",
                    "/org/freedesktop/portal/desktop",
                    Properties::class.java,
                ).Get<UInt32>("org.freedesktop.portal.FileChooser", "version")
        }
        true
    } catch (_: Exception) {
        false
    }
