/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.utils.AppLogger
import com.metrolist.desktop.utils.readLogTail
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_logs
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.error_with_message
import com.metrolist.shared.generated.resources.export_logs
import com.metrolist.shared.generated.resources.failed_to_export_logs
import com.metrolist.shared.generated.resources.failed_to_load_logs
import com.metrolist.shared.generated.resources.logs_exported_to
import com.metrolist.shared.generated.resources.no_logs_available
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.utils.DragScrollableColumn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun ApplicationLogsDialog(onDismiss: () -> Unit) {
    var logContent by remember { mutableStateOf<String?>(null) }
    var isExportingLogs by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        logContent =
            try {
                withContext(Dispatchers.IO) {
                    val logFiles = AppLogger.getLogFiles()
                    if (logFiles.isEmpty()) {
                        ""
                    } else {
                        logFiles
                            .take(3)
                            .joinToString("\n\n---\n\n") { file ->
                                "--- ${file.name} ---\n${readLogTail(file)}"
                            }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                getString(Res.string.failed_to_load_logs, e.message ?: "")
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.app_logs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                exportMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }

                when {
                    logContent == null -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            ContainedLoadingIndicator()
                        }
                    }

                    logContent.isNullOrBlank() -> {
                        Text(stringResource(Res.string.no_logs_available))
                    }

                    else -> {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp)
                                    .padding(8.dp),
                        ) {
                            DragScrollableColumn {
                                val lines = logContent?.lines() ?: return@DragScrollableColumn
                                items(lines) { line ->
                                    Text(
                                        line,
                                        style =
                                            MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Normal,
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.close)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        isExportingLogs = true
                        try {
                            val logDir = AppLogger.getLogDirectory()
                            val timestamp =
                                java.time.LocalDateTime
                                    .now()
                                    .format(
                                        java.time.format.DateTimeFormatter
                                            .ofPattern("yyyyMMdd-HHmmss"),
                                    )
                            val zipFile = java.io.File(logDir, "metrolist-logs-$timestamp.zip")

                            val success = withContext(Dispatchers.IO) { AppLogger.exportLogs(zipFile) }
                            exportMessage =
                                if (success) {
                                    getString(Res.string.logs_exported_to, zipFile.absolutePath)
                                } else {
                                    getString(Res.string.failed_to_export_logs)
                                }
                        } catch (e: Exception) {
                            exportMessage = getString(Res.string.error_with_message, e.message ?: "")
                            AppLogger.error("ApplicationLogsDialog", "Failed to export logs", e)
                        } finally {
                            isExportingLogs = false
                        }
                    }
                },
                enabled = !isExportingLogs,
                shapes = ButtonDefaults.shapes(),
            ) {
                if (isExportingLogs) {
                    ContainedLoadingIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Text(stringResource(Res.string.export_logs))
                }
            }
        },
        modifier = Modifier.fillMaxWidth(0.9f).widthIn(max = 760.dp).heightIn(max = 600.dp),
    )
}
