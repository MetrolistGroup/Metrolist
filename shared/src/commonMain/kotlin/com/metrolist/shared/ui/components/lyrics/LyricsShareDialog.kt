/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.foundation.Image
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
) {
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shareText = stringResource(Res.string.lyrics_share_text, text, title, artist, ShareUrls.song(songId))
    var style by remember {
        mutableStateOf(
            decodeLyricsImageStyle(
                Settings.shared.lyricsShareImageStyle,
                alignment =
                    when (Settings.shared.lyricsTextPosition) {
                        "LEFT" -> TextAlign.Left
                        "RIGHT" -> TextAlign.Right
                        else -> TextAlign.Center
                    },
            ),
        )
    }
    val latestStyle by rememberUpdatedState(style)
    DisposableEffect(Unit) { onDispose { Settings.shared.lyricsShareImageStyle = latestStyle.encodeForStorage() } }
    val context = LocalPlatformContext.current
    var artwork by remember { mutableStateOf<ImageBitmap?>(null) }
    var blurredArtwork by remember { mutableStateOf<ImageBitmap?>(null) }
    var palette by remember { mutableStateOf(emptyList<Color>()) }
    var loadingArtwork by remember { mutableStateOf(artworkUrl != null) }
    var artworkFailed by remember { mutableStateOf(false) }
    LaunchedEffect(artworkUrl) {
        try {
            if (artworkUrl != null) {
                val result =
                    SingletonImageLoader.get(context).execute(
                        ImageRequest
                            .Builder(context)
                            .data(artworkUrl)
                            .size(1024)
                            .build(),
                    )
                val image = result.image?.toImageBitmap()
                if (image != null) {
                    val prepared = withContext(Dispatchers.Default) { prepareLyricsArtwork(image) }
                    artwork = image
                    palette = prepared.first
                    blurredArtwork = prepared.second
                } else {
                    artworkFailed = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            artworkFailed = true
        } finally {
            loadingArtwork = false
        }
    }
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val renderMutex = remember { Mutex() }
    val logo = painterResource(Res.drawable.logo)
    val appName = stringResource(Res.string.app_name)
    val direction = LocalLayoutDirection.current
    var bitmap by remember(text, style, artwork) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(text, title, artist, appName, style, artwork, blurredArtwork, palette, loadingArtwork, direction) {
        if (!loadingArtwork) {
            delay(80)
            failed = false
            try {
                bitmap =
                    withContext(Dispatchers.Default) {
                        renderMutex.withLock {
                            renderLyricsImage(
                                text,
                                title,
                                artist,
                                appName,
                                artwork,
                                blurredArtwork,
                                palette,
                                style,
                                measurer,
                                logo,
                                direction,
                                sizePx = 540,
                            )
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
        }
    }
    val scrollState = rememberLazyListState()
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
    ) {
        Surface(
            modifier =
                Modifier
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = 880.dp)
                    .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.customize_colors), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(Res.string.lyrics_share_scroll_options), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f)) {
                    DragScrollableColumn(
                        state = scrollState,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize().padding(end = 16.dp),
                    ) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                                    bitmap?.let { Image(it, stringResource(Res.string.preview), Modifier.fillMaxWidth()) }
                                    if (bitmap == null && !failed) ContainedLoadingIndicator()
                                }
                            }
                        }
                        if (artworkFailed) item { Text(stringResource(Res.string.lyrics_share_artwork_failed)) }
                        item {
                            Text(stringResource(Res.string.lyrics_share_text_size, (style.textScale * 100).roundToInt()))
                            val textScaleState = rememberSliderState(value = style.textScale, trackRange = 0.5f..2f)
                            textScaleState.value = style.textScale
                            Slider(
                                modifier = Modifier.retainPlaybackKeyFocus(),
                                state = textScaleState,
                                onValueChange = { style = style.copy(textScale = it) },
                                enabled = !busy,
                            )
                            Text(stringResource(Res.string.lyrics_share_content_scale, (style.contentScale * 100).roundToInt()))
                            val contentScaleState = rememberSliderState(value = style.contentScale, trackRange = 0.6f..1.35f)
                            contentScaleState.value = style.contentScale
                            Slider(
                                modifier = Modifier.retainPlaybackKeyFocus(),
                                state = contentScaleState,
                                onValueChange = { style = style.copy(contentScale = it) },
                                enabled = !busy,
                            )
                            TextButton(
                                enabled = !busy,
                                onClick = { style = style.copy(textScale = 1f, contentScale = 1f) },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(Res.string.lyrics_share_auto_fit))
                            }
                        }
                        item {
                            Text(stringResource(Res.string.player_background_style))
                            val scrollState = rememberScrollState()
                            Row(
                                Modifier.fillMaxWidth().carouselScroll(scrollState).horizontalScroll(scrollState),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                LyricsImageBackground.entries.forEach { background ->
                                    ExpressiveFilterChip(
                                        selected = style.background == background,
                                        enabled = !busy && (background == LyricsImageBackground.SOLID || artwork != null),
                                        onClick = { style = style.copy(background = background) },
                                        label = {
                                            Text(
                                                stringResource(
                                                    when (background) {
                                                        LyricsImageBackground.SOLID -> Res.string.settings_solid
                                                        LyricsImageBackground.BLUR -> Res.string.blurred_artwork
                                                        LyricsImageBackground.GRADIENT -> Res.string.gradient
                                                    },
                                                ),
                                                maxLines = 1,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        item {
                            LyricsColorSwatches(
                                stringResource(Res.string.background_color),
                                (
                                    palette +
                                        listOf(
                                            Color(0xFF242424),
                                            Color(0xFF121212),
                                            Color.White,
                                            Color.Black,
                                            Color(0xFFF5F5F5),
                                        )
                                ).distinct(),
                                style.backgroundColor,
                                !busy,
                            ) { style = style.copy(backgroundColor = it) }
                        }
                        item {
                            LyricsColorSwatches(
                                stringResource(Res.string.text_color),
                                (palette + listOf(Color.White, Color.Black, Color(0xFF1DB954))).distinct(),
                                style.textColor,
                                !busy,
                            ) { style = style.copy(textColor = it) }
                        }
                        item {
                            LyricsColorSwatches(
                                stringResource(Res.string.secondary_text_color),
                                (
                                    palette.map { it.copy(alpha = 0.7f) } +
                                        listOf(Color.White.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.7f), Color(0xFF1DB954))
                                ).distinct(),
                                style.secondaryColor,
                                !busy,
                            ) { style = style.copy(secondaryColor = it) }
                        }
                    }
                    LazyListScrollbar(scrollState, Modifier.align(Alignment.CenterEnd))
                }
                if (failed) Text(stringResource(Res.string.lyrics_share_failed), color = MaterialTheme.colorScheme.error)
                if (busy) LoadingIndicator(Modifier.size(24.dp))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        enabled = !busy,
                        onClick = onDismiss,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.cancel)) }
                    Button(
                        enabled = !busy,
                        onClick = {
                            onShareText(shareText)
                            onDismiss()
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.share_as_text)) }
                    Button(
                        enabled = bitmap != null && !busy,
                        onClick = {
                            busy = true
                            failed = false
                            scope.launch {
                                try {
                                    val image =
                                        withContext(Dispatchers.Default) {
                                            renderMutex.withLock {
                                                renderLyricsImage(
                                                    text,
                                                    title,
                                                    artist,
                                                    appName,
                                                    artwork,
                                                    blurredArtwork,
                                                    palette,
                                                    style,
                                                    measurer,
                                                    logo,
                                                    direction,
                                                )
                                            }
                                        }
                                    if (onExport(image)) onDismiss()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    failed = true
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(exportLabel) }
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
