package com.metrolist.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.video_stats_av_sync
import com.metrolist.shared.generated.resources.video_stats_av_sync_value
import com.metrolist.shared.generated.resources.video_stats_close
import com.metrolist.shared.generated.resources.video_stats_codec_bitrate_value
import com.metrolist.shared.generated.resources.video_stats_color_space
import com.metrolist.shared.generated.resources.video_stats_container_format
import com.metrolist.shared.generated.resources.video_stats_current_fps
import com.metrolist.shared.generated.resources.video_stats_date
import com.metrolist.shared.generated.resources.video_stats_dropped_frames
import com.metrolist.shared.generated.resources.video_stats_dropped_frames_value
import com.metrolist.shared.generated.resources.video_stats_fps_value
import com.metrolist.shared.generated.resources.video_stats_latency_value
import com.metrolist.shared.generated.resources.video_stats_media_stream
import com.metrolist.shared.generated.resources.video_stats_media_stream_value
import com.metrolist.shared.generated.resources.video_stats_mv_source
import com.metrolist.shared.generated.resources.video_stats_na
import com.metrolist.shared.generated.resources.video_stats_position_vol
import com.metrolist.shared.generated.resources.video_stats_position_volume_value
import com.metrolist.shared.generated.resources.video_stats_render_latency
import com.metrolist.shared.generated.resources.video_stats_resolution
import com.metrolist.shared.generated.resources.video_stats_resolution_optimal_value
import com.metrolist.shared.generated.resources.video_stats_stream_source
import com.metrolist.shared.generated.resources.video_stats_video_codec
import com.metrolist.shared.generated.resources.video_stats_video_id_cpn
import com.metrolist.shared.utils.formatDuration
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private val StatsBackground = Color.Black.copy(alpha = 0.85f)
private val LabelColor = Color.White
private val ValueColor = Color.White

@Composable
fun VideoStatsForNerdsOverlay(
    modifier: Modifier = Modifier,
    onClose: () -> Unit = { UiState.showVideoStatsForNerds = false },
) {
    var stats by remember { mutableStateOf(PlayerState.getVideoStats()) }
    var dragOffset by remember { mutableStateOf(IntOffset.Zero) }
    var currentDateTime by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val formatter = DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'Z (z)", Locale.getDefault())
        while (true) {
            stats = PlayerState.getVideoStats()
            currentDateTime = ZonedDateTime.now().format(formatter)
            delay(250.milliseconds)
        }
    }

    val totalFrames = stats.renderedFrames + stats.droppedFrames
    val totalFramesCount = totalFrames.coerceAtLeast(stats.decodedFrames)
    val dropPercent = if (totalFrames > 0) (stats.droppedFrames.toDouble() / totalFrames) * 100.0 else 0.0
    val totalDurationMs = PlayerState.player.duration.value
    val syncOffset = stats.avSyncOffsetMs
    val sign = if (syncOffset >= 0) "+" else ""

    Box(
        modifier =
            modifier
                .offset { dragOffset }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        dragOffset =
                            IntOffset(
                                (dragOffset.x + dragAmount.x).roundToInt(),
                                (dragOffset.y + dragAmount.y).roundToInt(),
                            )
                    }
                }.clip(RoundedCornerShape(6.dp))
                .background(StatsBackground)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .width(IntrinsicSize.Max)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.5.dp)) {
            StatRow(
                label = stringResource(Res.string.video_stats_video_id_cpn),
                value = "${stats.videoId.ifBlank {
                    stringResource(
                        Res.string.video_stats_na,
                    )
                }} /\u00a0${stats.cpn.ifBlank { stringResource(Res.string.video_stats_na) }}",
                trailingContent = {
                    Text(
                        text = stringResource(Res.string.video_stats_close),
                        color = Color.White,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier
                                .clickable { onClose() }
                                .padding(start = 8.dp),
                    )
                },
            )

            if (stats.counterpartSource.isNotBlank()) {
                StatRow(stringResource(Res.string.video_stats_mv_source), stats.counterpartSource)
            }

            val videoCodecInfo =
                if (stats.videoCodec.isNotBlank()) {
                    if (stats.videoBitrateKbps > 0) {
                        stringResource(Res.string.video_stats_codec_bitrate_value, stats.videoCodec, stats.videoBitrateKbps)
                    } else {
                        stats.videoCodec
                    }
                } else {
                    stringResource(Res.string.video_stats_na)
                }
            StatRow(stringResource(Res.string.video_stats_video_codec), videoCodecInfo)

            StatRow(
                stringResource(Res.string.video_stats_container_format),
                stats.containerFormat.ifBlank {
                    stringResource(Res.string.video_stats_na)
                },
            )

            val resText =
                if (stats.videoResolution.isNotBlank()) {
                    if (stats.optimalResolution.isNotBlank() && stats.optimalResolution != stats.videoResolution) {
                        stringResource(Res.string.video_stats_resolution_optimal_value, stats.videoResolution, stats.optimalResolution)
                    } else {
                        stats.videoResolution
                    }
                } else {
                    stringResource(Res.string.video_stats_na)
                }
            StatRow(stringResource(Res.string.video_stats_resolution), resText)

            val fpsText = stringResource(Res.string.video_stats_fps_value, "%.2f".format(stats.fps), "%.2f".format(stats.targetFps))
            StatRow(stringResource(Res.string.video_stats_current_fps), fpsText)

            val frameStatsText =
                stringResource(
                    Res.string.video_stats_dropped_frames_value,
                    stats.droppedFrames,
                    totalFramesCount,
                    "%.2f".format(dropPercent),
                )
            StatRow(stringResource(Res.string.video_stats_dropped_frames), frameStatsText)

            val syncText =
                stringResource(
                    Res.string.video_stats_av_sync_value,
                    "$sign$syncOffset",
                    formatDuration(stats.videoPtsMs),
                    formatDuration(stats.audioClockMs),
                )
            StatRow(stringResource(Res.string.video_stats_av_sync), syncText)

            StatRow(
                stringResource(Res.string.video_stats_render_latency),
                stringResource(Res.string.video_stats_latency_value, "%.2f".format(stats.renderDurationMs)),
            )

            val colorSpaceText =
                if (stats.pixelFormat.isNotBlank() || stats.colorSpace.isNotBlank()) {
                    buildString {
                        if (stats.pixelFormat.isNotBlank()) append(stats.pixelFormat)
                        if (stats.colorSpace.isNotBlank()) append(" (${stats.colorSpace})")
                    }
                } else {
                    stringResource(Res.string.video_stats_na)
                }
            StatRow(stringResource(Res.string.video_stats_color_space), colorSpaceText)

            StatRow(
                stringResource(Res.string.video_stats_stream_source),
                stats.streamSource.ifBlank { stringResource(Res.string.video_stats_na) },
            )

            if (stats.mediaSizeBytes > 0L) {
                val readMb = stats.streamPositionBytes.toDouble() / (1024 * 1024)
                val totalMb = stats.mediaSizeBytes.toDouble() / (1024 * 1024)
                val pct = (stats.streamPositionBytes.toDouble() / stats.mediaSizeBytes) * 100.0
                StatRow(
                    stringResource(Res.string.video_stats_media_stream),
                    stringResource(
                        Res.string.video_stats_media_stream_value,
                        "%.1f".format(readMb),
                        "%.1f".format(totalMb),
                        "%.1f".format(pct),
                    ),
                )
            }

            val currentPosMs = stats.audioClockMs
            val posStr = "${formatDuration(currentPosMs)} /\u00a0${formatDuration(totalDurationMs)}"
            val volumeStr = "${(stats.volume * 100).roundToInt()}%"
            StatRow(
                stringResource(Res.string.video_stats_position_vol),
                stringResource(Res.string.video_stats_position_volume_value, posStr, volumeStr),
            )

            StatRow(stringResource(Res.string.video_stats_date), currentDateTime)
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = LabelColor,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(135.dp),
        )
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                color = ValueColor,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}
