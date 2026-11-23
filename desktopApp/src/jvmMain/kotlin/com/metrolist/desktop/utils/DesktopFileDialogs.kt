package com.metrolist.desktop.utils

import com.metrolist.shared.utils.OsUtil
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
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
                        type = FileKitType.File(extensions.withCaseVariants()),
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

internal suspend fun pickDesktopFiles(
    title: String,
    extensions: List<String>,
): List<File> =
    desktopFileDialog(
        fileKitDialog = {
            FileKit
                .openFilePicker(
                    type = FileKitType.File(extensions.withCaseVariants()),
                    mode = FileKitMode.Multiple(),
                    dialogSettings = FileKitDialogSettings(title = title),
                ).orEmpty()
                .map { it.file }
        },
        nativeDialog = {
            val chooser =
                JFileChooser().apply {
                    dialogTitle = title
                    isMultiSelectionEnabled = true
                    fileFilter = FileNameExtensionFilter(title, *extensions.toTypedArray())
                }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFiles.toList() else emptyList()
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

internal suspend fun pickDesktopDirectory(
    title: String,
    initialDirectory: File? = null,
): File? =
    desktopFileDialog(
        fileKitDialog = {
            FileKit
                .openDirectoryPicker(
                    directory = initialDirectory?.takeIf(File::isDirectory)?.let(::PlatformFile),
                    dialogSettings = FileKitDialogSettings(title = title),
                )?.file
        },
        nativeDialog = {
            val chooser =
                JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = title
                    initialDirectory?.takeIf(File::isDirectory)?.let { currentDirectory = it }
                }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        },
    )

// Portal globs are case-sensitive, unlike the Swing filter they replace, so cover.JPG needs its own pattern.
private fun List<String>.withCaseVariants(): List<String> = flatMap { listOf(it.lowercase(), it.uppercase()) }.distinct()

internal suspend fun <T> desktopFileDialog(
    isLinux: Boolean = OsUtil.isLinux,
    portalAvailable: () -> Boolean = ::isFileChooserPortalAvailable,
    fileKitDialog: suspend () -> T,
    nativeDialog: () -> T,
): T {
    if (!isLinux || WaylandScale.isNativeWaylandActive() || withContext(Dispatchers.IO) { portalAvailable() }) {
        return fileKitDialog()
    }
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
