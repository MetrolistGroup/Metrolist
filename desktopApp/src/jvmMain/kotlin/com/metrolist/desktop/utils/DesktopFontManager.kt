/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.settings.FontOption
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jetbrains.skia.FontMgr
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

object DesktopFontManager {
    private const val MAX_FONT_BYTES = 20L * 1024L * 1024L
    private const val MAX_REDIRECTS = 3
    private const val REGULAR_FILE = "Regular.ttf"
    private const val BOLD_FILE = "Bold.ttf"

    private data class Asset(
        val url: String,
        val sha256: String,
    )

    private data class FontSpec(
        val option: FontOption,
        val regular: Asset? = null,
        val bold: Asset? = null,
        val archive: Asset? = null,
        val archiveRegularEntry: String? = null,
        val archiveBoldEntry: String? = null,
    )

    private val specs =
        listOf(
            FontSpec(
                option = FontOption.INTER,
                regular =
                    Asset(
                        "https://fonts.gstatic.com/s/inter/v20/UcCO3FwrK3iLTeHuS_nVMrMxCp50SjIw2boKoduKmMEVuLyfMZg.ttf",
                        "1b08e7fc267a5c7e1d614100f604b83e7e8a0be241f0f288faa2b3ac93a683ba",
                    ),
                bold =
                    Asset(
                        "https://fonts.gstatic.com/s/inter/v20/UcCO3FwrK3iLTeHuS_nVMrMxCp50SjIw2boKoduKmMEVuFuYMZg.ttf",
                        "b37284b5701b6b168dfc770aa1a4ac492106422fd3ba76bc7641e37434e8019c",
                    ),
            ),
            FontSpec(
                option = FontOption.GOOGLE_SANS,
                regular =
                    Asset(
                        "https://fonts.gstatic.com/s/googlesans/v70/4Ua_rENHsxJlGDuGo1OIlJfC6l_24rlCK1Yo_Iqcsih3SAyH6cAwhX9RFD48TE63OOYKtrwEIKlirS7iEw.ttf",
                        "8a5485b127f97b9a196cda5252e075e63aff8cb35c0bff65fd0c90fe1e24a14c",
                    ),
                bold =
                    Asset(
                        "https://fonts.gstatic.com/s/googlesans/v70/4Ua_rENHsxJlGDuGo1OIlJfC6l_24rlCK1Yo_Iqcsih3SAyH6cAwhX9RFD48TE63OOYKtrzjJ6lirS7iEw.ttf",
                        "366109a647b0f2612805a291132c3d49b7c90792b7461055de8d5b468d71d900",
                    ),
            ),
            FontSpec(
                option = FontOption.MAPLE_MONO,
                archive =
                    Asset(
                        "https://github.com/subframe7536/maple-font/releases/download/v7.9/MapleMono-TTF.zip",
                        "3a35f8f0669bef3dded9df208cc4526a6f7573210e134816e9084a8981271d75",
                    ),
                archiveRegularEntry = "MapleMono-Regular.ttf",
                archiveBoldEntry = "MapleMono-Bold.ttf",
            ),
        )
    private val specsByOption = specs.associateBy(FontSpec::option)
    private val client =
        sharedOkHttpClient
            .newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

    val downloadableOptions: List<FontOption> = specs.map(FontSpec::option)

    var revision by mutableIntStateOf(0)
        private set

    fun installedOptions(): Set<FontOption> = downloadableOptions.filterTo(mutableSetOf(), ::isDownloaded)

    fun loadFontFamily(option: FontOption): FontFamily? {
        if (!isDownloaded(option)) return null
        val directory = fontDirectory(option)
        return runCatching {
            FontFamily(
                Font(File(directory, REGULAR_FILE), FontWeight.Normal),
                Font(File(directory, BOLD_FILE), FontWeight.Bold),
            )
        }.getOrNull()
    }

    fun supportsLanguage(
        option: FontOption,
        languageTag: String,
    ): Boolean {
        val codePoints =
            Locale
                .forLanguageTag(languageTag)
                .let { it.getDisplayName(it) }
                .codePoints()
                .filter(Character::isLetter)
                .toArray()
        val typeface = FontMgr.default.makeFromFile(File(fontDirectory(option), REGULAR_FILE).absolutePath) ?: return false
        return typeface.use { face -> codePoints.all { face.getUTF32Glyph(it).toInt() != 0 } }
    }

    suspend fun download(option: FontOption): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val spec = checkNotNull(specsByOption[option]) { "Font is not downloadable" }
                recoverInterruptedInstall(option)
                val destination = fontDirectory(option)
                val staging = stagingDirectory(option).apply { deleteRecursively() }
                check(staging.mkdirs()) { "Could not create the font staging directory" }

                try {
                    if (spec.archive != null) {
                        val archive = File(staging, "font.zip")
                        download(spec.archive, archive)
                        extract(archive, checkNotNull(spec.archiveRegularEntry), File(staging, REGULAR_FILE))
                        extract(archive, checkNotNull(spec.archiveBoldEntry), File(staging, BOLD_FILE))
                        check(archive.delete()) { "Could not remove the downloaded font archive" }
                    } else {
                        download(checkNotNull(spec.regular), File(staging, REGULAR_FILE))
                        download(checkNotNull(spec.bold), File(staging, BOLD_FILE))
                    }
                    validateFont(File(staging, REGULAR_FILE))
                    validateFont(File(staging, BOLD_FILE))
                    activate(destination, staging, backupDirectory(option))
                    revision++
                    Unit
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    internal fun hasSha256(
        file: File,
        expected: String,
    ): Boolean =
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
        }

    private fun isDownloaded(option: FontOption): Boolean {
        val directory = fontDirectory(option)
        val regular = File(directory, REGULAR_FILE)
        val bold = File(directory, BOLD_FILE)
        return regular.isFile && bold.isFile &&
            runCatching {
                validateFont(regular)
                validateFont(bold)
            }.isSuccess
    }

    private fun validateFont(file: File) {
        check(file.length() in 1..MAX_FONT_BYTES) { "Downloaded font has an invalid size" }
        checkNotNull(FontMgr.default.makeFromFile(file.absolutePath)) { "Downloaded file is not a supported font" }.close()
    }

    private fun download(
        asset: Asset,
        destination: File,
    ) {
        var url = asset.url.toHttpUrl()
        repeat(MAX_REDIRECTS + 1) { attempt ->
            requireTrustedUrl(url)
            var redirect: HttpUrl? = null
            client
                .newCall(Request.Builder().url(url).build())
                .execute()
                .use { response ->
                    if (response.isRedirect) {
                        check(attempt < MAX_REDIRECTS) { "Font download redirected too many times" }
                        redirect =
                            checkNotNull(response.header("Location")?.let(url::resolve)) { "Font download returned an invalid redirect" }
                    } else {
                        check(response.isSuccessful) { "Font download returned ${response.code}" }
                        response.body.contentLength().takeIf { it > MAX_FONT_BYTES }?.let {
                            error("Font download is larger than 20 MB")
                        }
                        response.body.byteStream().use { input ->
                            destination.outputStream().use { output -> copyBounded(input, output) }
                        }
                    }
                }
            if (redirect != null) {
                url = redirect
            } else {
                check(hasSha256(destination, asset.sha256)) { "Font download failed integrity verification" }
                return
            }
        }
    }

    private fun requireTrustedUrl(url: HttpUrl) {
        check(
            url.isHttps &&
                url.port == 443 &&
                url.username.isEmpty() &&
                url.password.isEmpty() &&
                url.host in setOf("fonts.gstatic.com", "github.com", "release-assets.githubusercontent.com"),
        ) { "Font download returned an untrusted URL" }
    }

    private fun extract(
        archive: File,
        entryName: String,
        destination: File,
    ) {
        ZipFile(archive).use { zip ->
            val entry = checkNotNull(zip.getEntry(entryName)?.takeUnless { it.isDirectory }) { "Font archive is incomplete" }
            check(entry.size <= MAX_FONT_BYTES) { "Archived font is larger than 20 MB" }
            zip.getInputStream(entry).use { input ->
                destination.outputStream().use { output -> copyBounded(input, output) }
            }
        }
    }

    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            check(copied <= MAX_FONT_BYTES) { "Font download is larger than 20 MB" }
            output.write(buffer, 0, count)
        }
        check(copied > 0) { "Font download is empty" }
    }

    private fun activate(
        destination: File,
        staging: File,
        backup: File,
    ) {
        backup.deleteRecursively()
        var movedPrevious = false
        try {
            if (destination.exists()) {
                move(destination, backup)
                movedPrevious = true
            }
            move(staging, destination)
            backup.deleteRecursively()
        } catch (failure: Throwable) {
            if (!destination.exists() && movedPrevious && backup.exists()) move(backup, destination)
            throw failure
        }
    }

    private fun move(
        source: File,
        destination: File,
    ) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun recoverInterruptedInstall(option: FontOption) {
        val destination = fontDirectory(option)
        val backup = backupDirectory(option)
        if (!destination.exists() && backup.exists()) {
            runCatching { move(backup, destination) }
        }
        if (destination.exists()) backup.deleteRecursively()
        stagingDirectory(option).deleteRecursively()
    }

    private fun fontDirectory(option: FontOption): File = File(fontRoot(), option.name.lowercase())

    private fun stagingDirectory(option: FontOption): File = File(fontRoot(), ".${option.name.lowercase()}.installing")

    private fun backupDirectory(option: FontOption): File = File(fontRoot(), ".${option.name.lowercase()}.previous")

    private fun fontRoot(): File = File(XdgUtils.getXdgDataHome(), "metrolist/fonts").apply { check(mkdirs() || isDirectory) }
}
