/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ImageCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_name
import com.metrolist.shared.generated.resources.background_color
import com.metrolist.shared.generated.resources.blurred_artwork
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.customize_colors
import com.metrolist.shared.generated.resources.gradient
import com.metrolist.shared.generated.resources.logo
import com.metrolist.shared.generated.resources.lyrics_share_artwork_failed
import com.metrolist.shared.generated.resources.lyrics_share_auto_fit
import com.metrolist.shared.generated.resources.lyrics_share_color
import com.metrolist.shared.generated.resources.lyrics_share_content_scale
import com.metrolist.shared.generated.resources.lyrics_share_failed
import com.metrolist.shared.generated.resources.lyrics_share_scroll_options
import com.metrolist.shared.generated.resources.lyrics_share_text
import com.metrolist.shared.generated.resources.lyrics_share_text_size
import com.metrolist.shared.generated.resources.player_background_style
import com.metrolist.shared.generated.resources.preview
import com.metrolist.shared.generated.resources.secondary_text_color
import com.metrolist.shared.generated.resources.settings_solid
import com.metrolist.shared.generated.resources.share_as_text
import com.metrolist.shared.generated.resources.text_color
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.Dialog
import com.metrolist.shared.ui.components.ExpressiveFilterChip
import com.metrolist.shared.ui.components.LazyListScrollbar
import com.metrolist.shared.ui.playback.retainPlaybackKeyFocus
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.carouselScroll
import com.metrolist.shared.ui.utils.toImageBitmap
import com.metrolist.shared.utils.ShareUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

import com.metrolist.shared.model.LyricsShareSelection
import com.metrolist.shared.model.durationMs
import com.metrolist.shared.model.hasWordTiming
import com.metrolist.shared.model.activeLineAt
import com.metrolist.shared.model.activeWordRangeAt
import kotlinx.coroutines.Job
import kotlin.time.TimeSource
import com.metrolist.shared.generated.resources.lyrics_card_editor
import com.metrolist.shared.generated.resources.lyrics_card_hint
import com.metrolist.shared.generated.resources.lyrics_card_classic
import com.metrolist.shared.generated.resources.lyrics_card_editorial
import com.metrolist.shared.generated.resources.lyrics_card_spotlight
import com.metrolist.shared.generated.resources.lyrics_card_style
import com.metrolist.shared.generated.resources.lyrics_card_adjust
import com.metrolist.shared.generated.resources.lyrics_card_alignment
import com.metrolist.shared.generated.resources.lyrics_card_left
import com.metrolist.shared.generated.resources.lyrics_card_center
import com.metrolist.shared.generated.resources.lyrics_card_right
import com.metrolist.shared.generated.resources.lyrics_card_plain
import com.metrolist.shared.generated.resources.lyrics_card_lines
import com.metrolist.shared.generated.resources.lyrics_card_words
import com.metrolist.shared.generated.resources.lyrics_card_timing_hint
import com.metrolist.shared.generated.resources.lyrics_card_video_mode
import com.metrolist.shared.generated.resources.lyrics_card_share_image
import com.metrolist.shared.generated.resources.lyrics_card_share_video
import com.metrolist.shared.generated.resources.lyrics_card_share_gif
import com.metrolist.shared.generated.resources.lyrics_card_mp4
import com.metrolist.shared.generated.resources.lyrics_card_gif
import com.metrolist.shared.generated.resources.lyrics_card_video_hint
import com.metrolist.shared.generated.resources.lyrics_card_gif_hint
import com.metrolist.shared.generated.resources.lyrics_card_moving_glow
import com.metrolist.shared.generated.resources.lyrics_card_pick_video
import com.metrolist.shared.generated.resources.lyrics_card_remove_video
import com.metrolist.shared.generated.resources.lyrics_card_video_preparing
import com.metrolist.shared.generated.resources.lyrics_card_local_video_hint
import com.metrolist.shared.generated.resources.lyrics_card_local_video_failed

private enum class LyricsShareMode { PLAIN, LINES, WORDS }
private enum class LyricsShareFormat { MP4, GIF }

@Composable
fun LyricsShareDialog(
    text: String,
    songId: String,
    title: String,
    artist: String,
    artworkUrl: String?,
    exportLabel: String,
    onExport: suspend (ImageBitmap) -> Boolean,
    onShareText: (String) -> Unit,
    onDismiss: () -> Unit,
    onExportAnimation: (suspend (ImageBitmap) -> Boolean)? = null,
    timedSelection: LyricsShareSelection? = null,
    onExportTimedAnimation: (suspend (LyricsAnimationExport) -> Boolean)? = null,
    onExportGif: (suspend (LyricsAnimationExport) -> Boolean)? = null,
    videoBackground: LyricsVideoBackground? = null,
    videoPreparing: Boolean = false,
    videoFailed: Boolean = false,
    onPickVideo: (() -> Unit)? = null,
    onRemoveVideo: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var previewFailed by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }
    var previewReady by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var adjusting by remember { mutableStateOf(false) }
    var mode by remember(timedSelection) { mutableStateOf(LyricsShareMode.PLAIN) }
    var format by remember { mutableStateOf(if (onExportAnimation == null && onExportTimedAnimation == null && onExportGif != null) LyricsShareFormat.GIF else LyricsShareFormat.MP4) }
    var glow by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var style by remember {
        mutableStateOf(decodeLyricsImageStyle(Settings.shared.lyricsShareImageStyle, when (Settings.shared.lyricsTextPosition) {
            "LEFT" -> TextAlign.Left
            "RIGHT" -> TextAlign.Right
            else -> TextAlign.Center
        }))
    }
    val latestStyle by rememberUpdatedState(style)
    DisposableEffect(Unit) { onDispose { Settings.shared.lyricsShareImageStyle = latestStyle.encodeForStorage() } }
    val activeVideo = videoBackground.takeIf { style.template == LyricsCardTemplate.SPOTLIGHT }
    var videoLease by remember { mutableStateOf<LyricsVideoBackgroundLease?>(null) }
    var leaseFailed by remember { mutableStateOf(false) }
    DisposableEffect(activeVideo) {
        val lease = runCatching { activeVideo?.acquireLease() }.getOrNull()
        videoLease = lease
        leaseFailed = activeVideo != null && lease == null
        onDispose { lease?.close(); videoLease = null }
    }
    val usesVideo = activeVideo != null && videoLease != null
    val usesGlow = glow && style.template == LyricsCardTemplate.SPOTLIGHT && activeVideo == null
    val duration = if (mode == LyricsShareMode.PLAIN) activeVideo?.durationMs ?: 6_000L else timedSelection?.durationMs ?: 6_000L
    LaunchedEffect(mode, timedSelection, activeVideo, usesVideo, usesGlow, busy) {
        if (!busy) {
            elapsedMs = 0L
            if (mode != LyricsShareMode.PLAIN || usesVideo || usesGlow) {
                val start = TimeSource.Monotonic.markNow()
                while (true) { elapsedMs = start.elapsedNow().inWholeMilliseconds % duration; delay(50) }
            }
        }
    }
    val activeLine = if (mode == LyricsShareMode.PLAIN) null else timedSelection?.activeLineAt(elapsedMs) ?: -1
    val activeWord = if (mode == LyricsShareMode.WORDS) timedSelection?.activeWordRangeAt(elapsedMs) else null
    val context = LocalPlatformContext.current
    var artwork by remember { mutableStateOf<ImageBitmap?>(null) }
    var blurredArtwork by remember { mutableStateOf<ImageBitmap?>(null) }
    var palette by remember { mutableStateOf(emptyList<Color>()) }
    var loadingArtwork by remember { mutableStateOf(artworkUrl != null) }
    var artworkFailed by remember { mutableStateOf(false) }
    LaunchedEffect(artworkUrl) {
        loadingArtwork = artworkUrl != null
        artworkFailed = false
        artwork = null
        blurredArtwork = null
        palette = emptyList()
        try {
            if (artworkUrl != null) {
                val result = SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(artworkUrl).size(1024).build())
                val image = result.image?.toImageBitmap()
                if (image == null) artworkFailed = true else {
                    val prepared = withContext(Dispatchers.Default) { prepareLyricsArtwork(image) }
                    artwork = image; palette = prepared.first; blurredArtwork = prepared.second
                }
            }
        } catch (error: CancellationException) { throw error } catch (_: Exception) { artworkFailed = true } finally { loadingArtwork = false }
    }
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val renderMutex = remember { Mutex() }
    val logo = painterResource(Res.drawable.logo)
    val appName = stringResource(Res.string.app_name)
    val direction = LocalLayoutDirection.current
    val previewDescription = stringResource(Res.string.preview)
    val shareText = stringResource(Res.string.lyrics_share_text, text, title, artist, ShareUrls.song(songId))
    fun frozenRenderer(): (Int, Int?, IntRange?, LyricsRenderLayer) -> ImageBitmap {
        val s = style; val a = artwork; val b = blurredArtwork; val p = palette
        return { pixels, line, word, layer -> renderLyricsImage(text, title, artist, appName, a, b, p, s, measurer, logo, direction, pixels, line, word, layer) }
    }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var baseBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var baseKey by remember { mutableStateOf<List<Any?>?>(null) }
    LaunchedEffect(text, title, artist, style, artwork, blurredArtwork, palette, loadingArtwork, direction, activeLine, activeWord, usesGlow, usesVideo) {
        previewReady = false
        if (!loadingArtwork) {
            if (mode == LyricsShareMode.PLAIN && !usesGlow && !usesVideo) delay(80)
            val render = frozenRenderer()
            val key = listOf(style, artwork, blurredArtwork, palette, direction)
            try {
                val image = withContext(Dispatchers.Default) {
                    renderMutex.withLock {
                        if (usesGlow && baseKey != key) { baseBitmap = render(540, null, null, LyricsRenderLayer.BACKGROUND); baseKey = key }
                        render(540, activeLine, activeWord, if (usesGlow || usesVideo) LyricsRenderLayer.FOREGROUND else LyricsRenderLayer.COMPLETE)
                    }
                }
                bitmap = image; previewFailed = false; previewReady = true
            } catch (error: CancellationException) { throw error } catch (_: Exception) { previewFailed = true }
        }
    }
    fun exportImage() {
        val render = frozenRenderer(); val video = activeVideo; val motion = usesGlow; val position = elapsedMs
        val line = activeLine; val word = activeWord; val color = palette.firstOrNull() ?: Color(0xFFB89BE8)
        busy = true; exportFailed = false
        exportJob = scope.launch {
            try {
                val image = withContext(Dispatchers.Default) {
                    renderMutex.withLock {
                        if (video == null && !motion) render(2160, line, word, LyricsRenderLayer.COMPLETE) else {
                            val foreground = render(2160, line, word, LyricsRenderLayer.FOREGROUND)
                            val base = if (motion) render(2160, null, null, LyricsRenderLayer.BACKGROUND) else null
                            val lease = video?.acquireLease()
                            try {
                                ImageBitmap(2160, 2160).also { output ->
                                    CanvasDrawScope().draw(Density(1f), direction, ImageCanvas(output), Size(2160f, 2160f)) {
                                        if (lease != null) drawLyricsVideo(lease, foreground, position) else drawLyricsMotion(checkNotNull(base), foreground, color, position, duration)
                                    }
                                }
                            } finally { lease?.close() }
                        }
                    }
                }
                if (onExport(image)) onDismiss() else exportFailed = true
            } catch (error: CancellationException) { throw error } catch (_: Exception) { exportFailed = true } finally { busy = false }
        }
    }
    fun exportAnimation() {
        val render = frozenRenderer(); val video = activeVideo; val motion = usesGlow; val selection = timedSelection
        val selectedMode = mode; val selectedFormat = format; val clipDuration = duration
        val color = (palette.firstOrNull() ?: Color(0xFFB89BE8)).toArgb()
        busy = true; exportFailed = false
        exportJob = scope.launch {
            try {
                val success = renderMutex.withLock {
                    val base = if (motion) withContext(Dispatchers.Default) { render(720, null, null, LyricsRenderLayer.BACKGROUND) } else null
                    val request = LyricsAnimationExport(
                        durationMs = clipDuration,
                        frameKeyAt = { time -> when (selectedMode) {
                            LyricsShareMode.PLAIN -> 0
                            LyricsShareMode.LINES -> (selection?.activeLineAt(time) ?: -1) + 1
                            LyricsShareMode.WORDS -> selection?.activeWordRangeAt(time)?.first?.plus(1) ?: -((selection?.activeLineAt(time) ?: -1) + 2)
                        } },
                        renderFrame = { time -> render(720,
                            if (selectedMode == LyricsShareMode.PLAIN) null else selection?.activeLineAt(time) ?: -1,
                            if (selectedMode == LyricsShareMode.WORDS) selection?.activeWordRangeAt(time) else null,
                            if (motion || video != null) LyricsRenderLayer.FOREGROUND else LyricsRenderLayer.COMPLETE)
                        },
                        motionBackground = base, motionColor = color, videoBackground = video,
                    )
                    when {
                        selectedFormat == LyricsShareFormat.GIF -> onExportGif?.invoke(request) ?: false
                        onExportTimedAnimation != null -> onExportTimedAnimation(request)
                        selectedMode == LyricsShareMode.PLAIN && !motion && video == null && onExportAnimation != null -> {
                            val image = withContext(Dispatchers.Default) { request.renderFrame(0) }; onExportAnimation(image)
                        }
                        else -> false
                    }
                }
                if (success) onDismiss() else exportFailed = true
            } catch (error: CancellationException) { throw error } catch (_: Exception) { exportFailed = true } finally { busy = false }
        }
    }
    val canExport = previewReady && !busy && !videoPreparing && (activeVideo == null || videoLease != null)
    val canAnimate = onExportTimedAnimation != null || onExportAnimation != null || onExportGif != null
    val scrollState = rememberLazyListState()
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Surface(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 880.dp).fillMaxHeight(0.94f), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.lyrics_card_editor), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(Res.string.lyrics_card_hint), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f)) {
                    DragScrollableColumn(state = scrollState, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
                        item {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                                bitmap?.let { foreground ->
                                    val base = baseBitmap; val lease = videoLease
                                    if ((usesGlow && base != null) || (usesVideo && lease != null)) {
                                        Canvas(Modifier.fillMaxSize().semantics { contentDescription = previewDescription }) {
                                            if (usesVideo && lease != null) drawLyricsVideo(lease, foreground, elapsedMs) else drawLyricsMotion(checkNotNull(base), foreground, palette.firstOrNull() ?: Color(0xFFB89BE8), elapsedMs, duration)
                                        }
                                    } else Image(foreground, previewDescription, Modifier.fillMaxSize())
                                }
                                if (bitmap == null && !previewFailed) ContainedLoadingIndicator()
                                if (bitmap != null && !previewReady && !previewFailed) LoadingIndicator(Modifier.align(Alignment.BottomEnd).size(24.dp))
                            }
                        }
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ExpressiveFilterChip(selected = !adjusting, enabled = !busy, onClick = { adjusting = false }, label = { Text(stringResource(Res.string.lyrics_card_style)) })
                                ExpressiveFilterChip(selected = adjusting, enabled = !busy, onClick = { adjusting = true }, label = { Text(stringResource(Res.string.lyrics_card_adjust)) })
                            }
                        }
                        if (!adjusting) item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LyricsCardTemplate.entries.forEach { template ->
                                    val preset = style.withTemplate(template)
                                    Surface(onClick = { if (style.template != template) style = preset }, enabled = !busy, color = preset.backgroundColor, shape = RoundedCornerShape(16.dp)) {
                                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(stringResource(when(template) { LyricsCardTemplate.CLASSIC -> Res.string.lyrics_card_classic; LyricsCardTemplate.EDITORIAL -> Res.string.lyrics_card_editorial; LyricsCardTemplate.SPOTLIGHT -> Res.string.lyrics_card_spotlight }), color = preset.textColor, style = MaterialTheme.typography.titleMedium)
                                            if (style.template == template) Text("✓", color = preset.textColor)
                                        }
                                    }
                                }
                            }
                        }
                        if (adjusting) {
                            item {
                                Text(stringResource(Res.string.lyrics_share_text_size, (style.textScale * 100).roundToInt()))
                                val textSlider = rememberSliderState(value = style.textScale, trackRange = 0.5f..2f); textSlider.value = style.textScale
                                Slider(state = textSlider, onValueChange = { style = style.copy(textScale = it) }, enabled = !busy)
                                Text(stringResource(Res.string.lyrics_share_content_scale, (style.contentScale * 100).roundToInt()))
                                val contentSlider = rememberSliderState(value = style.contentScale, trackRange = 0.6f..1.35f); contentSlider.value = style.contentScale
                                Slider(state = contentSlider, onValueChange = { style = style.copy(contentScale = it) }, enabled = !busy)
                                TextButton(onClick = { style = style.copy(textScale = 1f, contentScale = 1f) }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.lyrics_share_auto_fit)) }
                            }
                            item {
                                Text(stringResource(Res.string.lyrics_card_alignment))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(TextAlign.Left, TextAlign.Center, TextAlign.Right).forEach { alignment ->
                                        ExpressiveFilterChip(selected = style.alignment == alignment, enabled = !busy, onClick = { style = style.copy(alignment = alignment) }, label = { Text(stringResource(when(alignment) { TextAlign.Left -> Res.string.lyrics_card_left; TextAlign.Right -> Res.string.lyrics_card_right; else -> Res.string.lyrics_card_center })) })
                                    }
                                }
                            }
                            if (style.template != LyricsCardTemplate.SPOTLIGHT) item {
                                Text(stringResource(Res.string.player_background_style))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LyricsImageBackground.entries.forEach { background ->
                                        ExpressiveFilterChip(selected = style.background == background, enabled = !busy && (background == LyricsImageBackground.SOLID || artwork != null), onClick = { style = style.copy(background = background) }, label = { Text(stringResource(when(background) { LyricsImageBackground.SOLID -> Res.string.settings_solid; LyricsImageBackground.BLUR -> Res.string.blurred_artwork; LyricsImageBackground.GRADIENT -> Res.string.gradient })) })
                                    }
                                }
                                LyricsColorSwatches(stringResource(Res.string.background_color), (palette + listOf(Color.Black, Color.White, Color(0xFF242424))).distinct(), style.backgroundColor, !busy) { style = style.copy(backgroundColor = it) }
                            }
                            item { LyricsColorSwatches(stringResource(Res.string.text_color), (palette + listOf(Color.White, Color.Black)).distinct(), style.textColor, !busy) { style = style.copy(textColor = it) } }
                            item { LyricsColorSwatches(stringResource(Res.string.secondary_text_color), (palette + listOf(Color.White.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.7f))).distinct(), style.secondaryColor, !busy) { style = style.copy(secondaryColor = it) } }
                        }
                        if (onExportTimedAnimation != null) item {
                            Text(stringResource(Res.string.lyrics_card_video_mode), style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LyricsShareMode.entries.forEach { candidate ->
                                    val available = when(candidate) { LyricsShareMode.PLAIN -> true; LyricsShareMode.LINES -> timedSelection?.durationMs != null; LyricsShareMode.WORDS -> timedSelection?.hasWordTiming == true }
                                    ExpressiveFilterChip(selected = mode == candidate, enabled = !busy && available, onClick = { mode = candidate }, label = { Text(stringResource(when(candidate) { LyricsShareMode.PLAIN -> Res.string.lyrics_card_plain; LyricsShareMode.LINES -> Res.string.lyrics_card_lines; LyricsShareMode.WORDS -> Res.string.lyrics_card_words })) })
                                }
                            }
                            Text(stringResource(Res.string.lyrics_card_timing_hint), style = MaterialTheme.typography.bodySmall)
                            if (style.template == LyricsCardTemplate.SPOTLIGHT) {
                                ExpressiveFilterChip(selected = glow && activeVideo == null, enabled = !busy && activeVideo == null && !videoPreparing, onClick = { glow = !glow }, label = { Text(stringResource(Res.string.lyrics_card_moving_glow)) })
                                if (onPickVideo != null) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = onPickVideo, enabled = !busy && !videoPreparing, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.lyrics_card_pick_video)) }
                                        if (videoBackground != null || videoPreparing) TextButton(onClick = { onRemoveVideo?.invoke() }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.lyrics_card_remove_video)) }
                                    }
                                    if (videoPreparing) Text(stringResource(Res.string.lyrics_card_video_preparing))
                                    if (videoFailed || leaseFailed) Text(stringResource(Res.string.lyrics_card_local_video_failed), color = MaterialTheme.colorScheme.error)
                                    Text(stringResource(Res.string.lyrics_card_local_video_hint), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (onExportGif != null && (onExportTimedAnimation != null || onExportAnimation != null)) item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LyricsShareFormat.entries.forEach { f -> ExpressiveFilterChip(selected = format == f, enabled = !busy, onClick = { format = f }, label = { Text(stringResource(if (f == LyricsShareFormat.MP4) Res.string.lyrics_card_mp4 else Res.string.lyrics_card_gif)) }) }
                            }
                        }
                        if (canAnimate) item { Text(stringResource(if (format == LyricsShareFormat.GIF) Res.string.lyrics_card_gif_hint else Res.string.lyrics_card_video_hint), style = MaterialTheme.typography.bodySmall) }
                        if (artworkFailed) item { Text(stringResource(Res.string.lyrics_share_artwork_failed), style = MaterialTheme.typography.bodySmall) }
                    }
                    LazyListScrollbar(scrollState, Modifier.align(Alignment.CenterEnd))
                }
                if (previewFailed || exportFailed) Text(stringResource(Res.string.lyrics_share_failed), color = MaterialTheme.colorScheme.error)
                if (busy) LoadingIndicator(Modifier.size(24.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { if (busy) exportJob?.cancel() else onDismiss() }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
                    TextButton(enabled = !busy, onClick = { onShareText(shareText); onDismiss() }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.share_as_text)) }
                    if (canAnimate) Button(enabled = canExport, onClick = { exportAnimation() }, shapes = ButtonDefaults.shapes()) { Text(stringResource(if (format == LyricsShareFormat.GIF) Res.string.lyrics_card_share_gif else Res.string.lyrics_card_share_video)) }
                    Button(enabled = canExport, onClick = { exportImage() }, shapes = ButtonDefaults.shapes()) { Text(if (canAnimate) stringResource(Res.string.lyrics_card_share_image) else exportLabel) }
                }
            }
        }
    }
}

@Composable
private fun LyricsColorSwatches(
    label: String,
    colors: List<Color>,
    selected: Color,
    enabled: Boolean,
    onSelect: (Color) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    val scrollState = rememberScrollState()
    Row(Modifier.fillMaxWidth().carouselScroll(scrollState).horizontalScroll(scrollState)) {
        colors.forEach { color ->
            val description = stringResource(Res.string.lyrics_share_color, label, color.toArgb().toUInt().toString(16))
            Box(
                Modifier
                    .size(56.dp)
                    .semantics { contentDescription = description }
                    .selectable(selected = color == selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(color) })
                    .padding(3.dp)
                    .background(color, RoundedCornerShape(8.dp))
                    .border(
                        2.dp,
                        if (color ==
                            selected
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        RoundedCornerShape(8.dp),
                    ),
            )
        }
    }
}
