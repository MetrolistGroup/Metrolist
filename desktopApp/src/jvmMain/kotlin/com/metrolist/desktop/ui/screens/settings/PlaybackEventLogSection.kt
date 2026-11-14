package com.metrolist.desktop.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.utils.AppLogger
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.copied
import com.metrolist.shared.generated.resources.devtools_playback_log_cleared
import com.metrolist.shared.generated.resources.devtools_playback_log_export_failed
import com.metrolist.shared.generated.resources.export_saved
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.metrolist.shared.ui.settings.PlaybackEventLogSection as SharedPlaybackEventLogSection

@Composable
fun PlaybackEventLogSection() {
    val scope = rememberCoroutineScope()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val copiedMessage = stringResource(Res.string.copied)
    val clearedMessage = stringResource(Res.string.devtools_playback_log_cleared)
    val exportedMessage = stringResource(Res.string.export_saved)
    val exportFailedMessage = stringResource(Res.string.devtools_playback_log_export_failed)

    SharedPlaybackEventLogSection(
        formatTimestamp = ::formatPlaybackTimestamp,
        onExport = {
            scope.launch {
                statusMessage = if (withContext(Dispatchers.IO) { exportPlaybackLog() }) exportedMessage else exportFailedMessage
            }
        },
        onCopy = {
            scope.launch {
                val text = withContext(Dispatchers.IO) { PlaybackEventLog.exportText() }
                AppState.copyToClipboard(text)
                statusMessage = copiedMessage
            }
        },
        onClear = {
            PlaybackEventLog.clear()
            statusMessage = clearedMessage
        },
        statusMessage = statusMessage,
    )
}

private fun formatPlaybackTimestamp(timestampMs: Long): String =
    DateTimeFormatter
        .ofPattern("HH:mm:ss.SSS")
        .format(Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()))

private fun exportPlaybackLog(): Boolean =
    runCatching {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        File(AppLogger.getLogDirectory(), "metrolist-playback-$timestamp.txt").apply {
            parentFile?.mkdirs()
            writeText(PlaybackEventLog.exportText())
        }
    }.isSuccess
