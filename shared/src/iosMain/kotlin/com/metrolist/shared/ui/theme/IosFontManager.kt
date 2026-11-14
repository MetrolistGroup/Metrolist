package com.metrolist.shared.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import com.metrolist.shared.settings.FontOption
import com.metrolist.shared.settings.MAX_FONT_IMPORT_BYTES
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.isValidFontImport
import com.metrolist.shared.ui.settings.IosNativeFiles
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.FontMgr
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal object IosFontManager {
    private const val IMPORTED_FONT_FILE = "app_font.ttf"
    private const val IMPORTED_FONT_OTF_FILE = "app_font.otf"

    fun importFont(url: NSURL): String? =
        runCatching {
            val bytes = IosNativeFiles.readPickedFile(url, MAX_FONT_IMPORT_BYTES)
            val fileName = url.lastPathComponent ?: error("Font file name is unavailable")
            require(isValidFontImport(fileName, bytes.size.toLong(), bytes.take(4).toByteArray()))

            val extension = fileName.substringAfterLast('.').lowercase()
            val directory =
                fontDirectory().also {
                    check(
                        NSFileManager.defaultManager.createDirectoryAtPath(
                            it,
                            withIntermediateDirectories = true,
                            attributes = null,
                            error = null,
                        ),
                    )
                }
            val targetName = if (extension == "otf") IMPORTED_FONT_OTF_FILE else IMPORTED_FONT_FILE
            val temporaryPath = "$directory/.font-importing.$extension"
            val targetPath = "$directory/$targetName"
            check(bytes.usePinned { NSData.create(it.addressOf(0), bytes.size.toULong()).writeToFile(temporaryPath, atomically = true) })
            try {
                val typeface = FontMgr.default.makeFromFile(temporaryPath) ?: error("Font data is invalid")
                typeface.close()
                val files = NSFileManager.defaultManager
                val backupPath = "$directory/.font-previous"
                if (files.fileExistsAtPath(backupPath)) {
                    if (files.fileExistsAtPath(targetPath)) {
                        files.removeItemAtPath(backupPath, error = null)
                    } else {
                        check(files.moveItemAtPath(backupPath, toPath = targetPath, error = null))
                    }
                }
                val hadPreviousFont = files.fileExistsAtPath(targetPath)
                if (hadPreviousFont) check(files.moveItemAtPath(targetPath, toPath = backupPath, error = null))
                try {
                    check(files.moveItemAtPath(temporaryPath, toPath = targetPath, error = null))
                } catch (error: Throwable) {
                    if (hadPreviousFont) files.moveItemAtPath(backupPath, toPath = targetPath, error = null)
                    throw error
                }
                if (files.fileExistsAtPath(backupPath)) files.removeItemAtPath(backupPath, error = null)
                if (targetName != IMPORTED_FONT_FILE) files.removeItemAtPath("$directory/$IMPORTED_FONT_FILE", error = null)
                if (targetName != IMPORTED_FONT_OTF_FILE) files.removeItemAtPath("$directory/$IMPORTED_FONT_OTF_FILE", error = null)
                Settings.shared.appFont = FontOption.SYSTEM
                Settings.shared.iosCustomFontFile = targetName
                Settings.shared.iosCustomFontName = fileName.substringBeforeLast('.')
                Settings.shared.iosCustomFontRevision++
                Settings.shared.iosCustomFontName
            } finally {
                NSFileManager.defaultManager.removeItemAtPath(temporaryPath, error = null)
            }
        }.getOrNull()

    fun reset() {
        Settings.shared.iosCustomFontFile = ""
        Settings.shared.iosCustomFontName = ""
        Settings.shared.iosCustomFontRevision++
        NSFileManager.defaultManager.removeItemAtPath("${fontDirectory()}/$IMPORTED_FONT_FILE", error = null)
        NSFileManager.defaultManager.removeItemAtPath("${fontDirectory()}/$IMPORTED_FONT_OTF_FILE", error = null)
    }

    fun loadFontFamily(fileName: String): FontFamily? =
        runCatching {
            if (fileName !in setOf(IMPORTED_FONT_FILE, IMPORTED_FONT_OTF_FILE)) return null
            val path = "${fontDirectory()}/$fileName"
            val typeface = FontMgr.default.makeFromFile(path) ?: return null
            FontFamily(Typeface(typeface))
        }.getOrNull()

    private fun fontDirectory(): String =
        requireNotNull(
            NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).firstOrNull() as? String,
        ) + "/fonts"
}
