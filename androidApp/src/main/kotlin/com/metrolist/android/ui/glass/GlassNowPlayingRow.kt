package com.metrolist.android.ui.glass

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCombinedBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.LayerBackdrop
import com.metrolist.android.ui.glass.backdrop.Backdrop
import timber.log.Timber

/** Builds the native row input without sampling its containing page. */
@Composable
private fun rememberNowPlayingRowInput(backgroundColor: Color): Backdrop {
    val fill = rememberCanvasBackdrop { drawRect(backgroundColor) }
    val parent = (LocalGlassMaterialSource.current ?: LocalGlassContainerBackdrop.current)
        .takeIf { backgroundColor.alpha < 1f }
    val background = if (parent != null) rememberCombinedBackdrop(parent, fill) else fill
    return LocalQueueRowBackdrop.current ?: background
}

/** Active song highlight; the row's artwork, text and actions stay outside the material. */
@Composable
internal fun Modifier.glassNowPlayingRow(
    backgroundColor: Color,
    highlightColor: Color,
    shape: Shape,
    settings: GlassSettings = rememberGlassSettings(),
    exportedBackdrop: LayerBackdrop? = null,
    sceneBackdrop: Backdrop? = null,
): Modifier {
    val base = background(backgroundColor)
    val queueBackdrop = LocalQueueRowBackdrop.current
    if (LocalNativeQueueRows.current && queueBackdrop == null) return base.background(highlightColor)
    if (!settings.usesGlassMaterial(GlassArea.NowPlaying)) return base.background(highlightColor)
        .glassAreaContour(settings, GlassArea.NowPlaying, shape)

    val artworkColor = LocalGlassArtworkColor.current
    LaunchedEffect(settings.source, backgroundColor, highlightColor, artworkColor) {
        Timber.tag("GlassTintFlow").d(
            "NowPlaying inputs: source=%s, background=%08x, highlight=%08x, artwork=%s",
            settings.source, backgroundColor.toArgb(), highlightColor.toArgb(),
            artworkColor?.toArgb()?.toUInt()?.toString(16),
        )
    }

    // Queue scenes exclude this logical row's complete foreground and any objects in front of it.
    val rowBackdrop = sceneBackdrop ?: rememberNowPlayingRowInput(backgroundColor)
    val rowFilm = if (sceneBackdrop != null) queueNowPlayingFilm(settings, highlightColor) else highlightColor
    val latestHighlight = rememberUpdatedState(rowFilm)
    val drawHighlight: (DrawScope.() -> Unit)? = remember(settings.source, sceneBackdrop != null) {
        if (sceneBackdrop != null || settings.source == GlassTintSource.None)
            ({ drawRect(latestHighlight.value) }) else null
    }
    return base.glassMaterial(
        settings = settings.forArea(GlassArea.NowPlaying),
        shape = shape,
        ownBackdrop = rowBackdrop,
        drawFilm = sceneBackdrop == null,
        onDrawTint = drawHighlight,
        exportedBackdrop = exportedBackdrop,
    )
}

@Composable
private fun queueNowPlayingFilm(settings: GlassSettings, highlight: Color): Color =
    glassNativeSurfaceTint(settings, highlight).let { it.copy(alpha = it.alpha * settings.film.coerceIn(0f, 1f)) }

/** Exposes only the immediate row surface; badges never sample a page containing their own glyph. */
@Composable
internal fun GlassSongRowSurface(
    backgroundColor: Color,
    highlightColor: Color?,
    shape: Shape,
    settings: GlassSettings = rememberGlassSettings(),
    useNowPlayingMaterial: Boolean = true,
    paintBackground: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    val exported = rememberLayerBackdrop()
    val queueCapture = rememberQueueRowSceneCapture(content)
    val parent = LocalGlassContainerBackdrop.current
    val material = useNowPlayingMaterial && highlightColor != null &&
        settings.usesGlassMaterial(GlassArea.NowPlaying) &&
        (!LocalNativeQueueRows.current || LocalQueueRowBackdrop.current != null)
    SideEffect {
        queueCapture?.material = exported.takeIf { material }
        queueCapture?.surfaceShape = shape
    }
    val nativeSurface = rememberCanvasBackdrop {
        // These are the exact same flat fills painted by the native row below.
        if (paintBackground) drawRect(backgroundColor)
        if (highlightColor != null) drawRect(highlightColor)
    }
    val nativeUnderlay = if (paintBackground && backgroundColor.alpha >= 1f) null
        else LocalQueueRowBackdrop.current ?: parent
            ?: if (!paintBackground) rememberCanvasBackdrop { drawRect(backgroundColor) } else null
    val backdrop = if (material) exported else if (nativeUnderlay != null)
        rememberCombinedBackdrop(nativeUnderlay, nativeSurface) else nativeSurface
    val queueUnderlay = LocalQueueRowBackdrop.current
    val sceneBackdrop = if (material && queueUnderlay != null) queueCapture?.source(queueUnderlay) else null
    val surface = if (useNowPlayingMaterial && highlightColor != null) {
        Modifier.glassNowPlayingRow(backgroundColor, highlightColor, shape, settings,
            exportedBackdrop = exported, sceneBackdrop = sceneBackdrop)
    } else {
        (if (paintBackground) Modifier.background(backgroundColor) else Modifier)
            .then(if (highlightColor != null) Modifier.background(highlightColor) else Modifier)
    }
    val materialSource = if (sceneBackdrop != null) {
        remember(exported) { RenderedMaterialBackdrop(exported) }
    } else if (material) {
        val raw = rememberNowPlayingRowInput(backgroundColor)
        val accent = rememberCanvasBackdrop {
            if (settings.source == GlassTintSource.None) drawRect(highlightColor)
        }
        rememberCombinedBackdrop(raw, accent)
    } else backdrop
    val capturedSurface = when {
        queueCapture == null -> surface
        material -> surface.then(queueCapture.modifier)
        else -> queueCapture.modifier.then(surface)
    }
    val foregroundSurface = if (material) {
        val background = effectiveSurfaceColor(surfaceBackdropColor(), backgroundColor)
        val film = if (sceneBackdrop != null) queueNowPlayingFilm(settings, highlightColor ?: Color.Transparent)
            else glassFilmColor(settings.forArea(GlassArea.NowPlaying), role = null, menu = false)
        val plate = effectiveSurfaceColor(background, film)
        if (sceneBackdrop == null && settings.source == GlassTintSource.None)
            effectiveSurfaceColor(plate, highlightColor ?: Color.Transparent) else plate
    } else null
    CompositionLocalProvider(LocalGlassContainerBackdrop provides backdrop,
        LocalGlassMaterialSource provides materialSource) {
        GlassArtworkContent(settings, material, foregroundSurface) { content(capturedSurface) }
    }
}
