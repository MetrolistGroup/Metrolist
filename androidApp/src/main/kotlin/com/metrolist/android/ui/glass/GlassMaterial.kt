// Adapted from Square / Convx Project (C) 2026, GPL-3.0; see NOTICE-square.txt.
package com.metrolist.android.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.isSimple
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.glass.backdrop.BackdropEffectScope
import com.metrolist.android.ui.glass.backdrop.backdrops.LayerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop
import com.metrolist.android.ui.glass.backdrop.drawBackdrop
import com.metrolist.android.ui.glass.backdrop.drawPlainBackdrop
import com.metrolist.android.ui.glass.backdrop.effects.blur
import com.metrolist.android.ui.glass.backdrop.effects.colorControls
import com.metrolist.android.ui.glass.backdrop.effects.lens
import com.metrolist.android.ui.glass.backdrop.highlight.Highlight
import com.metrolist.android.ui.glass.backdrop.highlight.HighlightStyle
import com.metrolist.android.ui.glass.backdrop.shadow.Shadow

val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalIndependentGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val GlassRimWidth = 0.8.dp

/** Draws a fallback menu rim without laying out, masking, or moving the glass underneath. */
internal fun Modifier.glassMenuRim(shape: Shape, brush: Brush): Modifier = drawWithCache {
    val drawRim = createGlassRimDraw(shape, brush, size, layoutDirection, this, excludeBottom = true)
    onDrawWithContent {
        drawContent()
        drawRim()
    }
}

private fun createGlassRimDraw(
    shape: Shape,
    brush: Brush,
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
    excludeBottom: Boolean,
): DrawScope.() -> Unit {
    val outline = shape.createOutline(size, layoutDirection, density)
    val width = minOf(kotlin.math.ceil(with(density) { GlassRimWidth.toPx() }), kotlin.math.ceil(size.minDimension / 2f))
    if (outline is Outline.Generic && !excludeBottom) {
        return createGenericRimDraw(outline, brush, width, size, layoutDirection, density)
    }
    val half = width / 2f
    val inset = Offset(half, half)
    val borderSize = Size(size.width - width, size.height - width)
    val stroke = Stroke(width)
    val rounded = (outline as? Outline.Rounded)?.roundRect
    fun CornerRadius.inset(amount: Float) = CornerRadius((x - amount).coerceAtLeast(0f), (y - amount).coerceAtLeast(0f))
    // Match Compose.border's inset stroke and unequal-corner path geometry so
    // excluding the bottom never changes antialiasing along the other edges.
    val roundedPath = rounded?.takeUnless { it.isSimple }?.let { rect ->
        Path().apply {
            addRoundRect(rect)
            val inner = Path().apply {
                addRoundRect(RoundRect(width, width, rect.width - width, rect.height - width,
                    rect.topLeftCornerRadius.inset(width), rect.topRightCornerRadius.inset(width),
                    rect.bottomRightCornerRadius.inset(width), rect.bottomLeftCornerRadius.inset(width)))
            }
            op(this, inner, PathOperation.Difference)
        }
    }
    val paint: DrawScope.() -> Unit = {
        when {
            outline is Outline.Rectangle -> drawRect(brush, inset, borderSize, style = stroke)
            roundedPath != null -> drawPath(roundedPath, brush)
            rounded != null && rounded.topLeftCornerRadius.x < half ->
                clipRect(width, width, size.width - width, size.height - width, ClipOp.Difference) {
                    drawRoundRect(brush, cornerRadius = rounded.topLeftCornerRadius)
                }
            rounded != null -> drawRoundRect(brush, inset, borderSize,
                rounded.topLeftCornerRadius.inset(half), style = stroke)
            else -> drawOutline(outline, brush, style = Stroke(width * 2f))
        }
    }
    return {
        if (excludeBottom) {
            clipRect(left = -width, top = (size.height - width).coerceAtLeast(0f),
                right = size.width + width, bottom = size.height + width,
                clipOp = ClipOp.Difference, block = paint)
        } else paint()
    }
}

// Generic mask adapted from AndroidX Foundation 1.12.1 Border.kt (AOSP, Apache-2.0).
private fun createGenericRimDraw(
    outline: Outline.Generic,
    brush: Brush,
    width: Float,
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
): DrawScope.() -> Unit {
    if (width * 2f > size.minDimension) return { drawPath(outline.path, brush) }
    val bounds = outline.path.getBounds()
    val bitmapSize = IntSize(kotlin.math.ceil(bounds.width).toInt(), kotlin.math.ceil(bounds.height).toInt())
    if (bitmapSize.width <= 0 || bitmapSize.height <= 0) return {}
    val solid = brush as? SolidColor
    val bitmap = ImageBitmap(bitmapSize.width, bitmapSize.height,
        if (solid != null) ImageBitmapConfig.Alpha8 else ImageBitmapConfig.Argb8888)
    val outside = Path().apply {
        addRect(bounds)
        op(this, outline.path, PathOperation.Difference)
    }
    CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), bitmapSize.toSize()) {
        translate(-bounds.left, -bounds.top) {
            drawPath(outline.path, brush, style = Stroke(width * 2f))
            scale((this.size.width + 1f) / this.size.width, (this.size.height + 1f) / this.size.height) {
                drawPath(outside, brush, blendMode = BlendMode.Clear)
            }
        }
    }
    bitmap.prepareToDraw()
    val tint = solid?.let { ColorFilter.tint(it.value.copy(alpha = 1f)) }
    return {
        translate(bounds.left, bounds.top) {
            drawImage(bitmap, srcSize = bitmapSize, colorFilter = tint)
        }
    }
}

private fun cachedGlassRimDraw(shape: Shape, brush: Brush, excludeBottom: Boolean): DrawScope.() -> Unit {
    var previousSize = Size.Unspecified
    var previousDensity = Float.NaN
    var previousDirection: LayoutDirection? = null
    var paint: (DrawScope.() -> Unit)? = null
    return {
        if (size != previousSize || density != previousDensity || layoutDirection != previousDirection) {
            previousSize = size
            previousDensity = density
            previousDirection = layoutDirection
            paint = createGlassRimDraw(shape, brush, size, layoutDirection, this, excludeBottom)
        }
        paint?.invoke(this)
    }
}

/** Retains square glass optics while leaving unsupported outlines on the tint fallback. */
internal fun glassOpticalShape(shape: Shape): CornerBasedShape? = when (shape) {
    RectangleShape -> RoundedCornerShape(0.dp)
    else -> shape as? CornerBasedShape
}

/** Square records clear panes at full resolution and ramps frost down to 30%. */
fun glassResolutionScale(blurRadiusDp: Float): Float =
    1f - (blurRadiusDp / 8f).coerceIn(0f, 1f) * 0.7f

fun glassSaturation(vibrancy: Float): Float = 1f + 0.5f * vibrancy.coerceIn(0f, 2f)

/**
 * Film has the same range on every surface, including menus and native-window fallbacks.
 */
@Suppress("UNUSED_PARAMETER") // Named callers stay compatible; roles and handoffs impose no hidden opacity floor.
internal fun glassFilmOpacity(configured: Float, overlay: Boolean, progress: Float = 1f,
    fallback: Boolean = false, dark: Boolean = true): Float {
    return configured.coerceIn(0f, 1f)
}

@Composable
fun glassSurfaceTint(settings: GlassSettings): Color {
    val brightness = currentGlassArtworkTint()?.luminance
        ?: LocalGlassArtworkColor.current?.luminance() ?: 0f
    val light = Color(0xFFFAFAFA)
    val dark = lerp(Color(0xFF4A4A4E), Color(0xFF23232A), ((brightness - 0.42f) / 0.3f).coerceIn(0f, 1f))
    val base = when (val darkness = glassSurfaceDarkness(settings)) {
        0f -> light
        1f -> dark
        else -> lerp(light, dark, darkness)
    }
    return blendGlassTint(base, glassTint(settings), settings.tintStrength)
}

/** Retains source saturation and tone, including achromatic colours with no defined hue. */
internal fun blendGlassTint(base: Color, tint: Color, strength: Float): Color {
    if (!tint.isSpecified || strength <= 0f) return base
    val chroma = maxOf(tint.red, tint.green, tint.blue) - minOf(tint.red, tint.green, tint.blue)
    val sourceBase = if (chroma < .02f) {
        val tone = (base.red + base.green + base.blue) / 3f
        val neutralBase = Color(tone, tone, tone, base.alpha)
        // Keep exact grays neutral without bringing the colored base back in one frame.
        if (chroma == 0f) neutralBase else lerp(neutralBase, base, chroma / .02f)
    } else base
    return lerp(sourceBase, tint.copy(alpha = 1f), strength.coerceIn(0f, 1f))
}

/** Applies the shared Glass tint to a native fill without changing its coverage. */
@Composable
internal fun glassNativeSurfaceTint(settings: GlassSettings, base: Color): Color {
    if (!base.isSpecified || base.alpha == 0f) return base
    val darkness = glassArtworkDarkness(settings)
    val artwork = if (darkness > 0f) glassArtworkColor()?.copy(alpha = base.alpha) else null
    val sourceBase = when {
        artwork == null -> base
        darkness == 1f -> artwork
        else -> lerp(base, artwork, darkness)
    }
    return blendGlassTint(sourceBase, glassTint(settings), settings.tintStrength).copy(alpha = base.alpha)
}

/** Native alpha describes native rendering; the Glass film slider alone owns material coverage. */
@Composable
internal fun glassRoleFilmTint(settings: GlassSettings, role: Color?): Color =
    if (role == null || !role.isSpecified || role.alpha == 0f) glassSurfaceTint(settings)
    else glassNativeSurfaceTint(settings, role.copy(alpha = 1f))

@Composable
internal fun glassFilmTint(settings: GlassSettings, role: Color?, menu: Boolean, progress: Float): Color {
    val baseFilm = glassSurfaceTint(settings)
    val roleFilm = role?.let { glassRoleFilmTint(settings, it) } ?: return baseFilm
    return if (menu) lerp(baseFilm, roleFilm, progress.coerceIn(0f, 1f)) else roleFilm
}

@Composable
private fun glassRimTint(settings: GlassSettings): Color {
    return blendGlassTint(Color.White, glassTint(settings), settings.tintStrength)
}

/** Shared Square material for the preview and all production surfaces. */
@Composable
fun Modifier.glassMaterial(
    settings: GlassSettings,
    shape: Shape,
    separateWindow: Boolean = false,
    menu: Boolean = false,
    applyEdgeEffects: Boolean = true,
    blurRadiusDp: Float = settings.blur,
    highlightAlpha: Float = 0.3f,
    ownBackdrop: Backdrop? = null,
    backdropScale: Float? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    onDrawTint: (DrawScope.() -> Unit)? = null,
    menuFilmProgress: Float = 1f,
    filmColor: Color? = null,
    drawFilm: Boolean = true,
    drawShadow: Boolean = true,
    exportedBackdrop: LayerBackdrop? = null,
    // Opt-in geometry implementation; receives the shared resolution-scaled, safe lens reach.
    customLens: (BackdropEffectScope.(height: Float, amount: Float) -> Unit)? = null,
    // Overlay film and blur policy also serve floating dialogs with a complete perimeter.
    excludeBottomRim: Boolean = menu,
    foldInProgress: (() -> Boolean)? = null,
): Modifier {
    if (!settings.enabled) return this
    val independentBackdrop = LocalIndependentGlassBackdrop.current
    val backdrop = independentBackdrop ?: when {
        ownBackdrop is RenderedMaterialBackdrop -> ownBackdrop
        ownBackdrop != null && ownBackdrop === LocalGlassContainerBackdrop.current ->
            LocalGlassMaterialSource.current ?: ownBackdrop
        else -> ownBackdrop ?: LocalGlassBackdrop.current
    }
    val liveFrame = LocalGlassLiveFrame.current
    val opticsState = glassOpticsProgressState(glassOpticsEnabled())
    val opticsProgress = opticsState.value
    val opticalAlpha = remember(opticsState) { { opticsState.value } }
    // Keep the optical node/source identity across the cheap endpoint. Only its
    // processed layer fades; rebuilding the material here also discards exports.
    val renderer = if (separateWindow || backdrop == null) GlassRenderer.Tint else glassRenderer(settings)
    val film = glassFilmTint(settings, filmColor, menu, menuFilmProgress)
    val rimColor = glassRimTint(settings)
    val rimAlpha = (highlightAlpha * settings.rim / 0.55f).coerceIn(0f, 1f)
    val cornerShape = glassOpticalShape(shape)
    val menuOpacity = menuFilmProgress.coerceIn(0f, 1f)
    val fallback = renderer == GlassRenderer.Tint || (cornerShape == null && customLens == null) || backdrop == null
    val opacity = glassFilmOpacity(settings.film, menu, menuOpacity, fallback)
    if (fallback) {
        val exportedRim = if (exportedBackdrop != null) remember(shape, rimColor, rimAlpha, excludeBottomRim) {
            cachedGlassRimDraw(shape, SolidColor(rimColor.copy(alpha = rimAlpha)), excludeBottomRim)
        } else null
        val fill = if (exportedBackdrop == null) {
            Modifier.background(if (drawFilm) film.copy(alpha = film.alpha * opacity) else Color.Transparent)
                .then(if (onDrawTint != null) Modifier.drawBehind { onDrawTint.invoke(this) } else Modifier)
        } else {
            // Foreground controls still sample this passive film while its optics are disabled.
            val drawEmpty: DrawScope.() -> Unit = remember { {} }
            val emptySource = rememberCanvasBackdrop(drawEmpty)
            val shapeBlock: () -> Shape = remember(shape) { { shape } }
            val effects: BackdropEffectScope.() -> Unit = remember { {} }
            val surface: DrawScope.() -> Unit = remember(film, opacity, drawFilm, onDrawTint) {
                {
                    if (drawFilm && opacity > 0f) drawRect(film.copy(alpha = film.alpha * opacity))
                    onDrawTint?.invoke(this)
                }
            }
            Modifier.drawPlainBackdrop(emptySource, shapeBlock, effects,
                exportedBackdrop = exportedBackdrop, onDrawSurface = surface,
                onDrawFront = exportedRim,
                // Empty source and effects: export the passive film without forcing
                // different edge compositing from the ordinary tint fallback.
                compositingStrategy = CompositingStrategy.Auto)
        }
        return then(if (layerBlock != null) Modifier.graphicsLayer(layerBlock) else Modifier)
            // drawPlainBackdrop already owns the exported surface's shape mask.
            // A second mask multiplies antialias coverage along rounded edges.
            .then(if (exportedBackdrop == null) Modifier.clip(shape) else Modifier).then(fill)
            .then(if (exportedBackdrop != null) Modifier
                else if (excludeBottomRim) Modifier.glassMenuRim(shape, SolidColor(rimColor.copy(alpha = rimAlpha)))
                else Modifier.border(GlassRimWidth, rimColor.copy(alpha = rimAlpha), shape))
    }
    val density = LocalDensity.current
    val blurBudget = playerOverlayBlurBudget(
        radiusDp = blurRadiusDp,
        resolution = (backdropScale ?: glassResolutionScale(blurRadiusDp)).coerceIn(.05f, 1f),
        playerActive = LocalGlassPlayerOverlayActive.current && liveFrame == null,
        menu = menu,
        queue = LocalQueueGlassBackdrop.current != null,
    )
    val blurDp = blurBudget.radiusDp
    val resolution = blurBudget.resolution
    // Retuning blur/lens on every fade frame changes padding and forces a new
    // capture. A fade must only composite the live processed layer, otherwise
    // that capture work can consume the entire animation before it is displayed.
    val blurPx = with(density) { blurDp.dp.toPx() } * resolution
    val heightPx = with(density) { (settings.depth * 48f).dp.toPx() } * resolution
    val amountPx = with(density) { (settings.amount * 48f).dp.toPx() } * resolution
    val saturation = glassSaturation(settings.vibrancy)
    val opticalShape = cornerShape ?: shape
    val shapeBlock: () -> Shape = remember(opticalShape) { { opticalShape } }
    val effects: BackdropEffectScope.() -> Unit = remember(
        saturation, blurPx, heightPx, amountPx, renderer, applyEdgeEffects,
        settings.depthEffect, settings.dispersion, customLens,
    ) {
        {
            if (saturation != 1f) colorControls(saturation = saturation)
            if (blurPx > 0f) blur(blurPx)
            if (renderer == GlassRenderer.Refraction && (applyEdgeEffects || customLens != null) && size.minDimension > 24f) {
                val reach = size.minDimension * 0.45f
                if (customLens != null) customLens.invoke(this,
                    heightPx.coerceAtMost(reach), amountPx.coerceAtMost(reach)) else lens(
                    refractionHeight = heightPx.coerceAtMost(reach),
                    refractionAmount = amountPx.coerceAtMost(reach),
                    depthEffect = settings.depthEffect,
                    dispersion = settings.dispersionAmount(),
                )
            }
        }
    }
    val highlight: (() -> Highlight?)? = remember(applyEdgeEffects, rimColor, rimAlpha, excludeBottomRim, opticsProgress) {
        if (applyEdgeEffects && rimAlpha > 0f) ({ Highlight(width = GlassRimWidth,
            style = HighlightStyle.Default(color = rimColor.copy(alpha = rimAlpha), angle = 45f),
            excludeBottom = excludeBottomRim, alpha = opticsProgress) }) else null
    }
    val shadow: (() -> Shadow?)? = remember(applyEdgeEffects, drawShadow, opticsProgress) {
        if (applyEdgeEffects && drawShadow) ({ Shadow.Default.copy(alpha = opticsProgress) }) else null
    }
    val surface: DrawScope.() -> Unit = remember(film, opacity, onDrawTint, drawFilm) {
        {
            if (drawFilm && opacity > 0f) drawRect(film.copy(alpha = film.alpha * opacity))
            onDrawTint?.invoke(this)
        }
    }
    val exportedRim = if (exportedBackdrop != null && opticsProgress < 1f) {
        remember(shape, rimColor, rimAlpha, opticsProgress, excludeBottomRim) {
            cachedGlassRimDraw(shape, SolidColor(rimColor.copy(alpha = rimAlpha * (1f - opticsProgress))), excludeBottomRim)
        }
    } else null
    return drawBackdrop(
        backdrop = backdrop,
        shape = shapeBlock,
        effects = effects,
        highlight = highlight,
        shadow = shadow,
        onDrawSurface = surface,
        onDrawFront = exportedRim,
        opticsAlphaProvider = opticalAlpha,
        backdropScale = resolution,
        liveFrame = liveFrame,
        layerBlock = layerBlock,
        exportedBackdrop = exportedBackdrop,
        frozen = foldInProgress ?: { false },
        heldWhileMoving = foldInProgress ?: { false },
    ).then(if (exportedBackdrop == null && opticsProgress < 1f) {
        val rim = SolidColor(rimColor.copy(alpha = rimAlpha * (1f - opticsProgress)))
        if (excludeBottomRim) Modifier.glassMenuRim(shape, rim) else Modifier.border(GlassRimWidth, rim, shape)
    } else Modifier)
}
