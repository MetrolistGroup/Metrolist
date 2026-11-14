@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.statistics

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_name
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.preview
import com.metrolist.shared.generated.resources.statistics
import com.metrolist.shared.generated.resources.statistics_no_data
import com.metrolist.shared.generated.resources.statistics_plays_label
import com.metrolist.shared.generated.resources.statistics_share
import com.metrolist.shared.generated.resources.statistics_share_failed
import com.metrolist.shared.generated.resources.statistics_time
import com.metrolist.shared.generated.resources.statistics_top_albums
import com.metrolist.shared.generated.resources.statistics_top_artists
import com.metrolist.shared.generated.resources.statistics_top_tracks
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.screens.StatisticsUiState
import com.metrolist.shared.ui.screens.hasMeasuredPlayTime
import com.metrolist.shared.ui.screens.statisticsArtworkShape
import com.metrolist.shared.ui.screens.statisticsDuration
import com.metrolist.shared.ui.utils.configurePreparedArtwork
import com.metrolist.shared.ui.utils.segmentedGroupShape
import com.metrolist.shared.ui.utils.toImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun StatisticsShareDialog(
    state: StatisticsUiState,
    period: String,
    summary: String,
    exportLabel: String,
    onExport: suspend (ImageBitmap) -> Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val direction = LocalLayoutDirection.current
    val appName = stringResource(Res.string.app_name)
    val title = stringResource(Res.string.statistics)
    val sections =
        listOf(
            stringResource(Res.string.statistics_top_tracks) to state.tracks,
            stringResource(Res.string.statistics_top_albums) to state.albums,
            stringResource(Res.string.statistics_top_artists) to state.artists,
        ).filter { it.second.isNotEmpty() }
    val artworkShapes =
        sections
            .flatMap { (_, items) ->
                items.take(3).mapIndexed { index, item -> item.id to statisticsArtworkShape(item, index) }
            }.toMap()
    val context = LocalPlatformContext.current
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val heroShape = MaterialTheme.shapes.extraLarge
    val sectionShapes = sections.indices.map { segmentedGroupShape(it == 0, it == sections.lastIndex, false) }
    val duration =
        if (hasMeasuredPlayTime(state.totalTimeMs)) {
            statisticsDuration(state.totalTimeMs)
        } else {
            stringResource(Res.string.statistics_no_data)
        }
    val playsLabel = stringResource(Res.string.statistics_plays_label)
    val timeLabel = stringResource(Res.string.statistics_time)
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(
        state,
        period,
        summary,
        appName,
        title,
        sections,
        direction,
        colors,
        typography,
        heroShape,
        duration,
        playsLabel,
        timeLabel,
    ) {
        bitmap = null
        failed = false
        try {
            val artwork =
                coroutineScope {
                    sections
                        .flatMap { it.second.take(3) }
                        .mapNotNull { it.thumbnailUrl }
                        .distinct()
                        .map { url ->
                            async {
                                val image =
                                    try {
                                        withTimeoutOrNull(10_000) {
                                            SingletonImageLoader
                                                .get(context)
                                                .execute(
                                                    ImageRequest
                                                        .Builder(context)
                                                        .data(url)
                                                        .size(256)
                                                        .configurePreparedArtwork()
                                                        .build(),
                                                ).image
                                                ?.toImageBitmap()
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        null
                                    }
                                image?.let { url to it }
                            }
                        }.awaitAll()
                        .filterNotNull()
                        .toMap()
                }
            bitmap =
                withContext(Dispatchers.Default) {
                    renderStatisticsImage(
                        appName,
                        title,
                        period,
                        summary,
                        state.totalPlays.toString(),
                        duration,
                        playsLabel,
                        timeLabel,
                        sections,
                        artwork,
                        artworkShapes,
                        colors,
                        typography,
                        heroShape,
                        sectionShapes,
                        measurer,
                        direction,
                    )
                }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = { Text(stringResource(Res.string.statistics_share)) },
        text = {
            Column {
                bitmap?.let { image ->
                    Image(image, stringResource(Res.string.preview), Modifier.fillMaxWidth().heightIn(max = 480.dp))
                }
                if ((bitmap == null && !failed) || busy) ContainedLoadingIndicator()
                if (failed) Text(stringResource(Res.string.statistics_share_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.cancel))
            }
        },
        confirmButton = {
            Button(
                enabled = bitmap != null && !busy,
                shapes = ButtonDefaults.shapes(),
                onClick = {
                    val image = bitmap ?: return@Button
                    busy = true
                    failed = false
                    scope.launch {
                        try {
                            if (onExport(image)) onDismiss()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(exportLabel) }
        },
    )
}
