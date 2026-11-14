package com.metrolist.android.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.metrolist.android.utils.startActivitySafely
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.copied
import com.metrolist.shared.generated.resources.devtools_playback_log_cleared
import com.metrolist.shared.generated.resources.devtools_playback_log_export_failed
import com.metrolist.shared.generated.resources.devtools_playback_log_share_title
import com.metrolist.shared.generated.resources.devtools_playback_log_title
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Date
import java.util.Locale
import com.metrolist.shared.ui.settings.PlaybackEventLogSection as SharedPlaybackEventLogSection

@Composable
fun PlaybackEventLogSection() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val timeZone = ZoneId.systemDefault()
    val formatter =
        remember(locale, timeZone) {
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", locale).withDecimalStyle(DecimalStyle.of(locale)).withZone(timeZone)
        }
    val formatTimestamp = remember(formatter) { { timestampMs: Long -> formatter.format(Instant.ofEpochMilli(timestampMs)) } }
    val logClearedMessage = stringResource(Res.string.devtools_playback_log_cleared)
    SharedPlaybackEventLogSection(
        formatTimestamp = formatTimestamp,
        onExport = { scope.launch { exportPlaybackLog(context) } },
        onCopy = { scope.launch { copyPlaybackLog(context) } },
        onClear = {
            PlaybackEventLog.clear()
            Toast.makeText(context, logClearedMessage, Toast.LENGTH_SHORT).show()
        },
    )
}

private suspend fun copyPlaybackLog(context: Context) {
    val text =
        withContext(Dispatchers.IO) {
            PlaybackEventLog.exportText(maxEvents = MAX_CLIPBOARD_EVENTS, maxChars = MAX_CLIPBOARD_CHARS)
        }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText(
            getString(Res.string.devtools_playback_log_title),
            text,
        ),
    )
    Toast.makeText(context, getString(Res.string.copied), Toast.LENGTH_SHORT).show()
}

private suspend fun exportPlaybackLog(context: Context) {
    try {
        val uri =
            withContext(Dispatchers.IO) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val dir = File(context.cacheDir, "playback_logs").apply { mkdirs() }
                val file = File.createTempFile("metrolist-playback-$timestamp-", ".txt", dir)
                file.writeText(PlaybackEventLog.exportText())
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(Res.string.devtools_playback_log_share_title))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivitySafely(
            Intent.createChooser(shareIntent, getString(Res.string.devtools_playback_log_share_title)),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Toast.makeText(context, getString(Res.string.devtools_playback_log_export_failed), Toast.LENGTH_SHORT).show()
    }
}

private const val MAX_CLIPBOARD_EVENTS = 300
private const val MAX_CLIPBOARD_CHARS = 300_000
