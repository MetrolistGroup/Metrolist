/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.utils

import android.content.Context
import android.graphics.Typeface
import android.system.Os
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_font_atkinson_hyperlegible_next
import com.metrolist.shared.generated.resources.app_font_comic_neue
import com.metrolist.shared.generated.resources.app_font_google_sans
import com.metrolist.shared.generated.resources.app_font_lato
import com.metrolist.shared.generated.resources.app_font_roboto
import com.metrolist.shared.network.sharedOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jetbrains.compose.resources.StringResource
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object QuickFontManager {
    const val GOOGLE_SANS_ID = "google_sans"
    const val LATO_ID = "lato"
    const val ROBOTO_ID = "roboto"
    const val COMIC_NEUE_ID = "comic_neue"
    const val ATKINSON_HYPERLEGIBLE_NEXT_ID = "atkinson_hyperlegible_next"
    const val SYSTEM_ID = "SYSTEM"
    const val CUSTOM_ID = "CUSTOM"

    private const val FONT_DIRECTORY = "fonts"
    private const val MAX_FONT_BYTES = 20L * 1024L * 1024L

    data class QuickFont(
        val id: String,
        val labelRes: StringResource,
        val regularUrl: String,
        val boldUrl: String,
        val directoryName: String = id,
        val regularFileName: String = "$id-Regular.ttf",
        val boldFileName: String = "$id-Bold.ttf",
    )

    val fonts: List<QuickFont> =
        listOf(
            QuickFont(
                GOOGLE_SANS_ID,
                Res.string.app_font_google_sans,
                regularUrl =
                    "https://fonts.gstatic.com/s/googlesans/v70/4Ua_rENHsxJlGDuGo1OIlJfC6l_24rlCK1Yo_Iqcsih3SAyH6cAwhX9RFD48TE63OOYKtrwEIKlirS7iEw.ttf",
                boldUrl =
                    "https://fonts.gstatic.com/s/googlesans/v70/4Ua_rENHsxJlGDuGo1OIlJfC6l_24rlCK1Yo_Iqcsih3SAyH6cAwhX9RFD48TE63OOYKtrzjJ6lirS7iEw.ttf",
                regularFileName = "GoogleSans-Regular.ttf",
                boldFileName = "GoogleSans-Bold.ttf",
            ),
            QuickFont(
                LATO_ID,
                Res.string.app_font_lato,
                regularUrl = "https://fonts.gstatic.com/s/lato/v25/S6uyw4BMUTPHvxk6WQev.ttf",
                boldUrl = "https://fonts.gstatic.com/s/lato/v25/S6u9w4BMUTPHh6UVew-FHi_o.ttf",
                regularFileName = "Lato-Regular.ttf",
                boldFileName = "Lato-Bold.ttf",
            ),
            QuickFont(
                ROBOTO_ID,
                Res.string.app_font_roboto,
                regularUrl = "https://fonts.gstatic.com/s/roboto/v51/KFOMCnqEu92Fr1ME7kSn66aGLdTylUAMQXC89YmC2DPNWubEbWmTgg3Wlg.ttf",
                boldUrl = "https://fonts.gstatic.com/s/roboto/v51/KFOMCnqEu92Fr1ME7kSn66aGLdTylUAMQXC89YmC2DPNWuYjammTgg3Wlg.ttf",
                regularFileName = "Roboto-Regular.ttf",
                boldFileName = "Roboto-Bold.ttf",
            ),
            QuickFont(
                COMIC_NEUE_ID,
                Res.string.app_font_comic_neue,
                regularUrl = "https://fonts.gstatic.com/s/comicneue/v9/4UaHrEJDsxBrF37olUeDx63j4JN0.ttf",
                boldUrl = "https://fonts.gstatic.com/s/comicneue/v9/4UaErEJDsxBrF37olUeD_xHMwpteKQtG.ttf",
                regularFileName = "ComicNeue-Regular.ttf",
                boldFileName = "ComicNeue-Bold.ttf",
            ),
            QuickFont(
                ATKINSON_HYPERLEGIBLE_NEXT_ID,
                Res.string.app_font_atkinson_hyperlegible_next,
                regularUrl =
                    "https://fonts.gstatic.com/s/atkinsonhyperlegiblenext/v7/NaP4cYPdHfdVxJw0IfIP0lvYFqijb-UxCtm5_wdGscKFt4tOOfV4ZmW37LQhtNl73T0.ttf",
                boldUrl =
                    "https://fonts.gstatic.com/s/atkinsonhyperlegiblenext/v7/NaP4cYPdHfdVxJw0IfIP0lvYFqijb-UxCtm5_wdGscKFt4tOOfV4ZmW3C7MhtNl73T0.ttf",
                regularFileName = "AtkinsonHyperlegibleNext-Regular.ttf",
                boldFileName = "AtkinsonHyperlegibleNext-Bold.ttf",
            ),
        )

    private val fontsById = fonts.associateBy(QuickFont::id)

    private val httpClient =
        sharedOkHttpClient
            .newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

    fun find(id: String): QuickFont? = fontsById[id]

    fun isSupportedId(id: String): Boolean = id in fontsById

    fun isDownloaded(
        context: Context,
        id: String,
    ): Boolean {
        val font = find(id) ?: return false
        val directory = fontDirectory(context, font)
        val regular = File(directory, font.regularFileName)
        val bold = File(directory, font.boldFileName)
        if (!regular.isFile || !bold.isFile) return false
        return runCatching {
            Typeface.createFromFile(regular)
            Typeface.createFromFile(bold)
            true
        }.getOrDefault(false)
    }

    fun loadFontFamily(
        context: Context,
        id: String,
    ): FontFamily {
        val font = find(id) ?: return FontFamily.Default
        if (!isDownloaded(context, id)) return FontFamily.Default
        val directory = fontDirectory(context, font)
        val regular = File(directory, font.regularFileName)
        val bold = File(directory, font.boldFileName)

        return runCatching {
            FontFamily(
                Font(regular, FontWeight.Normal, FontStyle.Normal),
                Font(bold, FontWeight.Bold, FontStyle.Normal),
            )
        }.getOrDefault(FontFamily.Default)
    }

    suspend fun downloadedFontIds(context: Context): Set<String> =
        withContext(Dispatchers.IO) {
            fonts.mapNotNullTo(mutableSetOf()) { font ->
                recoverInterruptedInstall(context, font)
                font.id.takeIf { isDownloaded(context, it) }
            }
        }

    suspend fun downloadFonts(
        context: Context,
        id: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            val font = find(id) ?: return@withContext Result.failure(IllegalArgumentException("Unknown quick font"))
            recoverInterruptedInstall(context, font)
            val directory = fontDirectory(context, font)
            val staging =
                stagingDirectory(context, font).apply {
                    deleteRecursively()
                    mkdirs()
                }
            val backup = backupDirectory(context, font)
            val temporaryRegular = File(staging, font.regularFileName)
            val temporaryBold = File(staging, font.boldFileName)

            runCatching {
                downloadToFile(font.regularUrl, temporaryRegular)
                downloadToFile(font.boldUrl, temporaryBold)
                Typeface.createFromFile(temporaryRegular)
                Typeface.createFromFile(temporaryBold)
                activate(directory, staging, backup)
            }.also {
                staging.deleteRecursively()
                if (directory.exists()) backup.deleteRecursively()
            }
        }

    private fun fontDirectory(
        context: Context,
        font: QuickFont,
    ): File = File(context.filesDir, "$FONT_DIRECTORY/${font.directoryName}")

    private fun stagingDirectory(
        context: Context,
        font: QuickFont,
    ): File = File(context.filesDir, "$FONT_DIRECTORY/.${font.directoryName}.installing")

    private fun backupDirectory(
        context: Context,
        font: QuickFont,
    ): File = File(context.filesDir, "$FONT_DIRECTORY/.${font.directoryName}.previous")

    private suspend fun downloadToFile(
        url: String,
        destination: File,
    ) {
        val trustedUrl =
            url.toHttpUrlOrNull()?.takeIf { it.isHttps && it.host == "fonts.gstatic.com" }
                ?: error("Google Fonts returned an untrusted font URL")
        val request =
            Request
                .Builder()
                .url(trustedUrl)
                .build()
        executeRequest(request) { response ->
            requireTrustedResponse(response, "fonts.gstatic.com")
            require(response.isSuccessful) { "Font download returned ${response.code}" }
            response.body.contentLength().takeIf { it > MAX_FONT_BYTES }?.let {
                error("Font is larger than 20 MB")
            }
            response.body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_FONT_BYTES) { "Font is larger than 20 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
    }

    private suspend fun <T> executeRequest(
        request: Request,
        transform: (Response) -> T,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result = runCatching { response.use(transform) }
                        if (continuation.isActive) continuation.resumeWith(result)
                    }
                },
            )
        }

    private fun requireTrustedResponse(
        response: Response,
        expectedHost: String,
    ) {
        val url = response.request.url
        require(url.isHttps && url.host == expectedHost) { "Google Fonts returned an untrusted response" }
        require(!response.isRedirect) { "Google Fonts returned an unexpected redirect" }
    }

    private fun replaceAtomically(
        source: File,
        destination: File,
    ) {
        Os.rename(source.absolutePath, destination.absolutePath)
    }

    private fun activate(
        directory: File,
        staging: File,
        backup: File,
    ) {
        backup.deleteRecursively()
        var movedPrevious = false
        try {
            if (directory.exists()) {
                replaceAtomically(directory, backup)
                movedPrevious = true
            }
            replaceAtomically(staging, directory)
            backup.deleteRecursively()
        } catch (failure: Throwable) {
            if (!directory.exists() && movedPrevious && backup.exists()) {
                replaceAtomically(backup, directory)
            }
            throw failure
        }
    }

    private fun recoverInterruptedInstall(
        context: Context,
        font: QuickFont,
    ) {
        val directory = fontDirectory(context, font)
        val backup = backupDirectory(context, font)
        if (!directory.exists() && backup.exists()) {
            runCatching { replaceAtomically(backup, directory) }
        }
        if (directory.exists()) backup.deleteRecursively()
    }
}
