/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.constants.ExportFolderUriKey
import com.metrolist.music.constants.ExportFormatKey
import com.metrolist.music.di.AudioExporterEntryPoint
import com.metrolist.music.export.AudioExportService
import com.metrolist.music.export.AudioExporter
import com.metrolist.music.export.ConflictPolicy
import com.metrolist.music.export.ExportDestination
import com.metrolist.music.export.ExportError
import com.metrolist.music.export.ExportException
import com.metrolist.music.export.ExportFormat
import com.metrolist.music.export.ExportPlan
import com.metrolist.music.export.ExportRequest
import com.metrolist.music.export.TreeSink
import com.metrolist.music.export.message
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

private sealed interface ExportStep {
    data object Options : ExportStep

    data object Checking : ExportStep

    data class Conflict(val plan: ExportPlan) : ExportStep

    data class Failed(val message: String) : ExportStep
}

@Composable
private fun rememberAudioExporter(): AudioExporter {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, AudioExporterEntryPoint::class.java)
            .audioExporter()
    }
}

/** Menu entry that opens the export dialog, or shows progress and cancels while this song is exporting. */
@Composable
fun rememberExportAudioMenuItem(
    songId: String,
    onClick: () -> Unit,
): Material3MenuItemData {
    val context = LocalContext.current
    val exporter = rememberAudioExporter()
    val states by exporter.states.collectAsStateWithLifecycle()
    val active = states[songId]?.isActive == true

    return if (active) {
        Material3MenuItemData(
            title = { Text(text = stringResource(R.string.export_notification_title)) },
            description = { Text(text = stringResource(R.string.cancel)) },
            icon = {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            },
            onClick = { AudioExportService.cancel(context, songId) },
        )
    } else {
        Material3MenuItemData(
            title = { Text(text = stringResource(R.string.export_audio)) },
            description = { Text(text = stringResource(R.string.export_audio_desc)) },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.storage),
                    contentDescription = null,
                )
            },
            onClick = onClick,
        )
    }
}

@Composable
fun ExportAudioDialog(
    metadata: MediaMetadata,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = rememberAudioExporter()

    val startedText = stringResource(R.string.export_audio_started, metadata.title)

    var format by rememberEnumPreference(ExportFormatKey, ExportFormat.ORIGINAL)
    var folderUri by rememberPreference(ExportFolderUriKey, "")
    var step by remember { mutableStateOf<ExportStep>(ExportStep.Options) }

    // Before Android 10 there is no public-folder access without a storage permission, so a folder is required.
    val folderRequired = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && folderUri.isBlank()

    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }.onFailure { Timber.w(it, "Could not persist folder permission") }
                val previous = folderUri
                if (previous.isNotBlank() && previous != uri.toString()) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            previous.toUri(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                }
                folderUri = uri.toString()
                step = ExportStep.Options
            }
        }

    fun request(policy: ConflictPolicy) =
        ExportRequest(
            songId = metadata.id,
            format = format,
            destination = ExportDestination(folderUri.ifBlank { null }),
            conflictPolicy = policy,
        )

    fun start(policy: ConflictPolicy) {
        if (AudioExportService.start(context, request(policy), metadata.title)) {
            Toast.makeText(context, startedText, Toast.LENGTH_SHORT).show()
            onDismiss()
        } else {
            step = ExportStep.Failed(context.getString(R.string.export_error_cannot_start))
        }
    }

    fun check() {
        step = ExportStep.Checking
        scope.launch {
            try {
                exporter.ensureSongKnown(metadata)
                val plan = exporter.plan(request(ConflictPolicy.KEEP_BOTH))
                if (plan.alreadyExists) step = ExportStep.Conflict(plan) else start(ConflictPolicy.KEEP_BOTH)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ExportException) {
                if (failure.error == ExportError.AccessDenied) folderUri = ""
                step = ExportStep.Failed(failure.error.message(context))
            } catch (failure: Exception) {
                Timber.w(failure, "Export preflight failed")
                step = ExportStep.Failed(ExportError.NoStream.message(context))
            }
        }
    }

    val location =
        if (folderUri.isBlank()) {
            stringResource(R.string.export_audio_default_folder)
        } else {
            TreeSink.describeTree(folderUri.toUri())
        }

    DefaultDialog(
        onDismiss = { if (step != ExportStep.Checking) onDismiss() },
        icon = { Icon(painter = painterResource(R.drawable.storage), contentDescription = null) },
        title = { Text(stringResource(R.string.export_audio_title)) },
        buttons = {
            when (step) {
                ExportStep.Options, is ExportStep.Failed -> {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(
                        enabled = !folderRequired,
                        onClick = ::check,
                    ) { Text(stringResource(R.string.export_audio_button)) }
                }
                ExportStep.Checking -> {
                    TextButton(onClick = {}, enabled = false) { Text(stringResource(R.string.export_audio_checking)) }
                }
                is ExportStep.Conflict -> {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { start(ConflictPolicy.KEEP_BOTH) }) {
                        Text(stringResource(R.string.export_audio_keep_both))
                    }
                    TextButton(onClick = { start(ConflictPolicy.REPLACE) }) {
                        Text(stringResource(R.string.export_audio_replace))
                    }
                }
            }
        },
        horizontalAlignment = Alignment.Start,
    ) {
        when (val current = step) {
            is ExportStep.Conflict ->
                Text(
                    text = stringResource(R.string.export_audio_exists, current.plan.fileName, location),
                    style = MaterialTheme.typography.bodyMedium,
                )
            else -> {
                FormatOption(
                    selected = format == ExportFormat.ORIGINAL,
                    title = stringResource(R.string.export_audio_original),
                    description = stringResource(R.string.export_audio_original_desc),
                    enabled = step != ExportStep.Checking,
                    onClick = { format = ExportFormat.ORIGINAL },
                )
                FormatOption(
                    selected = format == ExportFormat.MP3,
                    title = stringResource(R.string.export_audio_mp3),
                    description = stringResource(R.string.export_audio_mp3_desc),
                    enabled = step != ExportStep.Checking,
                    onClick = { format = ExportFormat.MP3 },
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.export_audio_save_to),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text =
                                if (folderRequired) {
                                    stringResource(R.string.export_audio_folder_required)
                                } else {
                                    location
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    TextButton(
                        enabled = step != ExportStep.Checking,
                        onClick = { folderPicker.launch(null) },
                    ) { Text(stringResource(R.string.export_audio_choose_folder)) }
                }
                if (folderUri.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    TextButton(
                        enabled = step != ExportStep.Checking,
                        onClick = { folderUri = "" },
                    ) { Text(stringResource(R.string.export_audio_reset_folder)) }
                }

                (step as? ExportStep.Failed)?.let {
                    Text(
                        text = it.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatOption(
    selected: Boolean,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
