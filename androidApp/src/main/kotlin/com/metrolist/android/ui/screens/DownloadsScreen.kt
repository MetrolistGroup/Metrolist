@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.android.playback.DownloadFailureCode
import com.metrolist.android.playback.DownloadQueueState
import com.metrolist.android.playback.DownloadRequest
import com.metrolist.android.playback.SongDownloadManager
import com.metrolist.android.playback.isRunnable
import com.metrolist.android.ui.screens.settings.SettingsSubScreen
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.continue_action
import com.metrolist.shared.generated.resources.dismiss
import com.metrolist.shared.generated.resources.download_cancel_all
import com.metrolist.shared.generated.resources.download_error_generic
import com.metrolist.shared.generated.resources.download_error_incomplete
import com.metrolist.shared.generated.resources.download_error_network
import com.metrolist.shared.generated.resources.download_error_storage_access
import com.metrolist.shared.generated.resources.download_error_storage_full
import com.metrolist.shared.generated.resources.download_pause_all
import com.metrolist.shared.generated.resources.download_paused
import com.metrolist.shared.generated.resources.download_queue_pending
import com.metrolist.shared.generated.resources.download_resume_all
import com.metrolist.shared.generated.resources.downloading
import com.metrolist.shared.generated.resources.downloads
import com.metrolist.shared.generated.resources.downloads_empty
import com.metrolist.shared.generated.resources.downloads_empty_hint
import com.metrolist.shared.generated.resources.downloads_failed_banner
import com.metrolist.shared.generated.resources.error
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.queued
import com.metrolist.shared.generated.resources.remove
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.see_all
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
fun DownloadsScreen(onNavigateBack: () -> Unit) {
    val manager: SongDownloadManager = koinInject()
    val requests by manager.requests.collectAsStateWithLifecycle()
    val visible = remember(requests) { requests.pendingDownloads() }
    SettingsSubScreen(
        title = stringResource(Res.string.downloads),
        onNavigateBack = onNavigateBack,
        contentScrollable = false,
        actions = {
            if (visible.any { it.state.isRunnable }) {
                IconButton(onClick = manager::pauseAll, shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Filled.Pause, stringResource(Res.string.download_pause_all))
                }
            }
            if (visible.any { it.state == DownloadQueueState.PAUSED }) {
                IconButton(onClick = manager::resumeAll, shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Filled.PlayArrow, stringResource(Res.string.download_resume_all))
                }
            }
            if (visible.isNotEmpty()) {
                IconButton(onClick = manager::cancelAll, shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Default.Close, stringResource(Res.string.download_cancel_all))
                }
            }
        },
    ) {
        if (visible.isEmpty()) {
            DownloadsEmptyState()
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(visible, key = { it.song.id }) { request ->
                    DownloadQueueRow(request, manager, Modifier.animateItem())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadsEmptyState(modifier: Modifier = Modifier) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.85f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>(),
        label = "downloadsEmptyScale",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>(),
        label = "downloadsEmptyAlpha",
    )
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = contentAlpha
                },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.size(112.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        MetrolistIcons.Outlined.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(52.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(Res.string.downloads_empty),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.downloads_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
    }
}

@Composable
internal fun DownloadQueuePanel(
    manager: SongDownloadManager,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requests by manager.requests.collectAsStateWithLifecycle()
    val pending = remember(requests) { requests.pendingDownloads() }
    if (pending.isEmpty()) return
    val failedCount = pending.count { it.state == DownloadQueueState.FAILED }
    val waitingCount = pending.size - failedCount
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (waitingCount > 0) {
                        Text(
                            pluralStringResource(Res.plurals.download_queue_pending, waitingCount, waitingCount),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    if (failedCount > 0) {
                        Text(
                            pluralStringResource(Res.plurals.downloads_failed_banner, failedCount, failedCount),
                            style = if (waitingCount > 0) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onSeeAll, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.see_all)) }
            }
            pending.take(PANEL_ROWS).forEach { DownloadQueueRow(it, manager) }
            Row(Modifier.align(Alignment.End)) {
                if (failedCount > 0) {
                    TextButton(
                        onClick = manager::dismissFailed,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.dismiss)) }
                    TextButton(onClick = manager::retryFailed, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.retry)) }
                }
                when {
                    pending.any { it.state.isRunnable } -> {
                        TextButton(
                            onClick = manager::pauseAll,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.download_pause_all)) }
                    }

                    pending.any { it.state == DownloadQueueState.PAUSED } -> {
                        TextButton(
                            onClick = manager::resumeAll,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.download_resume_all)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadQueueRow(
    request: DownloadRequest,
    manager: SongDownloadManager,
    modifier: Modifier = Modifier,
) {
    val songId = request.song.id
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(request.song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val progress = request.totalBytes?.takeIf { it > 0 }?.let { (request.downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
            val status =
                when (request.state) {
                    DownloadQueueState.RUNNING -> {
                        stringResource(Res.string.downloading)
                    }

                    DownloadQueueState.QUEUED -> {
                        stringResource(Res.string.queued)
                    }

                    DownloadQueueState.PAUSED -> {
                        stringResource(Res.string.download_paused)
                    }

                    else -> {
                        stringResource(
                            when (request.lastError) {
                                DownloadFailureCode.STORAGE_FULL.name -> Res.string.download_error_storage_full
                                DownloadFailureCode.STORAGE_ACCESS.name -> Res.string.download_error_storage_access
                                DownloadFailureCode.NETWORK.name -> Res.string.download_error_network
                                DownloadFailureCode.INCOMPLETE.name -> Res.string.download_error_incomplete
                                else -> Res.string.download_error_generic
                            },
                        )
                    }
                }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (request.state == DownloadQueueState.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (request.state == DownloadQueueState.RUNNING) {
                if (progress == null) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        when (request.state) {
            DownloadQueueState.RUNNING, DownloadQueueState.QUEUED -> {
                IconButton(onClick = { manager.pause(songId) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Filled.Pause, stringResource(Res.string.pause))
                }
            }

            DownloadQueueState.PAUSED -> {
                IconButton(onClick = { manager.resume(songId) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Filled.PlayArrow, stringResource(Res.string.continue_action))
                }
            }

            else -> {
                IconButton(onClick = { manager.retryFailed(songId) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Filled.Refresh, stringResource(Res.string.retry))
                }
            }
        }
        IconButton(onClick = { manager.cancel(request.song) }, shapes = IconButtonDefaults.shapes()) {
            Icon(MetrolistIcons.Default.Close, stringResource(Res.string.remove))
        }
    }
}

private const val PANEL_ROWS = 3

private fun List<DownloadRequest>.pendingDownloads(): List<DownloadRequest> =
    filter { it.state != DownloadQueueState.CANCELLED && it.state != DownloadQueueState.COMPLETED }
        .sortedWith(compareBy({ it.state != DownloadQueueState.RUNNING }, { it.state.ordinal }, { it.enqueuedAt }))
