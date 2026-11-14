@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.playlistimport

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.dismiss
import com.metrolist.shared.generated.resources.import_cancelled_partial
import com.metrolist.shared.generated.resources.import_error_empty_input
import com.metrolist.shared.generated.resources.import_error_empty_playlist
import com.metrolist.shared.generated.resources.import_error_file_read_failed
import com.metrolist.shared.generated.resources.import_error_generic
import com.metrolist.shared.generated.resources.import_error_incomplete_source
import com.metrolist.shared.generated.resources.import_error_local_files_require_local_playlist
import com.metrolist.shared.generated.resources.import_error_no_library_matches
import com.metrolist.shared.generated.resources.import_error_no_tracks_recognized
import com.metrolist.shared.generated.resources.import_error_no_tracks_to_import
import com.metrolist.shared.generated.resources.import_error_resolution_failed
import com.metrolist.shared.generated.resources.import_error_track_limit
import com.metrolist.shared.generated.resources.import_error_unsupported_playlist_entries
import com.metrolist.shared.generated.resources.import_error_youtube_dedicated_flow
import com.metrolist.shared.generated.resources.import_status_completed
import com.metrolist.shared.generated.resources.import_status_failed
import com.metrolist.shared.generated.resources.import_status_fetching_metadata
import com.metrolist.shared.generated.resources.import_status_fetching_tracks
import com.metrolist.shared.generated.resources.import_status_matching_tracks_short
import com.metrolist.shared.generated.resources.import_status_saving_tracks
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@Suppress("FunctionName")
private fun ImportProgressLine(
    progress: Float?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val width = with(LocalDensity.current) { 4.dp.toPx() }
    val stroke = Stroke(width = width, cap = StrokeCap.Round)
    if (progress == null) {
        LinearWavyProgressIndicator(
            modifier = modifier.height(8.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            stroke = stroke,
            trackStroke = stroke,
        )
    } else {
        LinearWavyProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier.height(8.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            stroke = stroke,
            trackStroke = stroke,
        )
    }
}

@Composable
@Suppress("FunctionName")
fun PlaylistImportProgressCard(
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val current by PlaylistImportStatusManager.activeImport.collectAsState()
    val importInfo = current ?: return
    val terminal = !importInfo.isRunning
    val failed = importInfo.state is ImportProgressState.Failed

    LaunchedEffect(importInfo.id, importInfo.state) {
        if (importInfo.state is ImportProgressState.Completed) {
            delay(6000.milliseconds)
            PlaylistImportStatusManager.dismiss(importInfo.id)
        }
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = importInfo.playlistTitle,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    onDismiss?.invoke()
                    if (terminal) PlaylistImportStatusManager.dismiss(importInfo.id) else PlaylistImportStatusManager.cancel(importInfo.id)
                },
                modifier = Modifier.size(32.dp),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    MetrolistIcons.Filled.Close,
                    contentDescription = stringResource(if (terminal) Res.string.dismiss else Res.string.cancel),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        ImportProgressLine(
            progress = importInfo.state.progressFraction(),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = importInfo.state.statusText(),
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (importInfo.state is ImportProgressState.Cancelled) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
@Suppress("FunctionName")
fun PlaylistImportProgressIndicator(modifier: Modifier = Modifier) {
    val current by PlaylistImportStatusManager.activeImport.collectAsState()
    val importInfo = current ?: return
    val terminal = !importInfo.isRunning
    val failed = importInfo.state is ImportProgressState.Failed

    LaunchedEffect(importInfo.id, importInfo.state) {
        if (importInfo.state is ImportProgressState.Completed) {
            delay(6000.milliseconds)
            PlaylistImportStatusManager.dismiss(importInfo.id)
        }
    }

    Column(modifier.width(180.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = importInfo.playlistTitle,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    if (terminal) PlaylistImportStatusManager.dismiss(importInfo.id) else PlaylistImportStatusManager.cancel(importInfo.id)
                },
                modifier = Modifier.size(24.dp),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    MetrolistIcons.Filled.Close,
                    contentDescription = stringResource(if (terminal) Res.string.dismiss else Res.string.cancel),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        ImportProgressLine(
            progress = importInfo.state.progressFraction(),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun ImportProgressState.progressFraction(): Float? =
    when (this) {
        is ImportProgressState.LoadingTracks -> total?.takeIf { it > 0 }?.let { loaded.toFloat() / it }

        is ImportProgressState.ResolvingTracks -> resolved.toFloat() / total.coerceAtLeast(1)

        is ImportProgressState.SavingToLibrary -> saved.toFloat() / total.coerceAtLeast(1)

        is ImportProgressState.Completed,
        is ImportProgressState.Cancelled,
        is ImportProgressState.Failed,
        -> 1f

        else -> null
    }

@Composable
private fun ImportProgressState.statusText(): String =
    when (this) {
        is ImportProgressState.FetchingMetadata -> {
            stringResource(Res.string.import_status_fetching_metadata)
        }

        is ImportProgressState.LoadingTracks -> {
            stringResource(Res.string.import_status_fetching_tracks, loaded, total?.toString() ?: "…")
        }

        is ImportProgressState.ResolvingTracks -> {
            stringResource(Res.string.import_status_matching_tracks_short, resolved, total)
        }

        is ImportProgressState.SavingToLibrary -> {
            stringResource(Res.string.import_status_saving_tracks, saved, total)
        }

        is ImportProgressState.Cancelled -> {
            stringResource(Res.string.import_cancelled_partial, saved, destinationName)
        }

        is ImportProgressState.Completed -> {
            stringResource(Res.string.import_status_completed)
        }

        is ImportProgressState.Failed -> {
            message.ifBlank {
                when (errorKey) {
                    ImportErrorKey.EmptyInput -> {
                        stringResource(Res.string.import_error_empty_input)
                    }

                    ImportErrorKey.YouTubeDedicatedFlow -> {
                        stringResource(Res.string.import_error_youtube_dedicated_flow)
                    }

                    ImportErrorKey.InvalidYouTubeUrl -> {
                        stringResource(Res.string.import_error_empty_playlist)
                    }

                    ImportErrorKey.NoTracksRecognized -> {
                        stringResource(Res.string.import_error_no_tracks_recognized)
                    }

                    ImportErrorKey.NoTracksToImport -> {
                        stringResource(Res.string.import_error_no_tracks_to_import)
                    }

                    ImportErrorKey.ResolutionFailed -> {
                        stringResource(Res.string.import_error_resolution_failed)
                    }

                    ImportErrorKey.IncompleteSource -> {
                        stringResource(Res.string.import_error_incomplete_source)
                    }

                    ImportErrorKey.NoLibraryMatches -> {
                        stringResource(Res.string.import_error_no_library_matches)
                    }

                    ImportErrorKey.UnsupportedPlaylistEntries -> {
                        stringResource(Res.string.import_error_unsupported_playlist_entries)
                    }

                    ImportErrorKey.LocalFilesRequireLocalPlaylist -> {
                        stringResource(
                            Res.string.import_error_local_files_require_local_playlist,
                        )
                    }

                    ImportErrorKey.TooManyTracks -> {
                        stringResource(Res.string.import_error_track_limit, PlaylistImportManager.MAX_PLAYLIST_TRACKS)
                    }

                    ImportErrorKey.FileReadFailed -> {
                        stringResource(Res.string.import_error_file_read_failed)
                    }

                    ImportErrorKey.Generic -> {
                        stringResource(Res.string.import_error_generic)
                    }

                    null -> {
                        stringResource(Res.string.import_status_failed)
                    }
                }
            }
        }

        is ImportProgressState.DuplicateDetected,
        ImportProgressState.Idle,
        -> {
            ""
        }
    }
