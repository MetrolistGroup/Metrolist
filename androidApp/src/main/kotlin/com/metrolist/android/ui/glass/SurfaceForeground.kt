package com.metrolist.android.ui.glass

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import com.metrolist.android.ui.theme.ColorPalette
import com.metrolist.android.ui.theme.withColor
import com.metrolist.android.ui.theme.paletteDarkness
import com.metrolist.android.ui.theme.LocalAppearance

/** Estimated scene or plate color at the origin of an overlay. */
internal val LocalSurfaceBackdropColor = compositionLocalOf<Color?> { null }
internal val LocalNeutralForegroundOverride = compositionLocalOf<Color?> { null }
internal val LocalNeutralForegroundAmount = compositionLocalOf { 1f }
internal val LocalNeutralForegroundSurface = compositionLocalOf<Color?> { null }
internal val LocalNeutralForegroundTransition = compositionLocalOf<State<NeutralForegroundTransition>?> { null }
internal val LocalForegroundSourcePalette = compositionLocalOf<ColorPalette?> { null }
private val LocalForegroundSurface = compositionLocalOf<Color?> { null }
private val LocalForegroundScrim = compositionLocalOf { Color.Transparent }
internal val LocalGlassInkDarkness = compositionLocalOf<Float?> { null }
internal val LocalPlayerForegroundProgress = compositionLocalOf<Float?> { null }

internal const val TextContrast = 4.5f
internal const val IconContrast = 3f
// Preserve selected neutral ink unless it becomes indistinct.
private const val NeutralRetentionContrast = 2f

internal fun contrastAgainst(foreground: Color, surface: Color): Float {
    val ink = foreground.compositeOver(surface).luminance()
    val fill = surface.luminance()
    return (maxOf(ink, fill) + 0.05f) / (minOf(ink, fill) + 0.05f)
}

internal fun neutralForeground(surface: Color): Color =
    if (contrastAgainst(Color.Black, surface) >= contrastAgainst(Color.White, surface)) Color.Black else Color.White

/** Theme grays have near-zero saturation; a pale or very dark chromatic color is still colored. */
internal fun isNeutralForeground(color: Color): Boolean {
    if (!color.isSpecified) return false
    val maximum = maxOf(color.red, color.green, color.blue)
    val minimum = minOf(color.red, color.green, color.blue)
    val chroma = maximum - minimum
    if (chroma == 0f) return true
    val denominator = 1f - kotlin.math.abs(maximum + minimum - 1f)
    return denominator > 0f && chroma / denominator <= .025f
}

internal fun readableForeground(original: Color, surface: Color, minimum: Float): Color {
    if (!original.isSpecified) return neutralForeground(surface)
    if (!isNeutralForeground(original) || original.alpha == 0f ||
        contrastAgainst(original, surface) >= minOf(minimum, NeutralRetentionContrast)) return original
    return neutralForeground(surface).copy(alpha = original.alpha)
}

private fun isGentleGlyph(original: Color, enabled: Boolean): Boolean {
    val tone = (original.red + original.green + original.blue) / 3f
    return !enabled || tone in .18f.. .82f || original.alpha < .7f
}

/** Deliberately gray or disabled glyphs keep their subdued tone and original opacity. */
internal fun readableGlyphForeground(
    original: Color,
    surface: Color,
    minimum: Float = IconContrast,
    enabled: Boolean = true,
): Color {
    if (!original.isSpecified) return neutralForeground(surface)
    if (!isNeutralForeground(original) || original.alpha == 0f) return original
    if (!isGentleGlyph(original, enabled)) return readableForeground(original, surface, minimum)
    if (contrastAgainst(original, surface) >= minOf(minimum, NeutralRetentionContrast)) return original
    val direction = if (neutralForeground(surface) == Color.White) .08f else -.08f
    return Color((original.red + direction).coerceIn(0f, 1f),
        (original.green + direction).coerceIn(0f, 1f),
        (original.blue + direction).coerceIn(0f, 1f), original.alpha)
}

private fun smoothGlassStep(fraction: Float): Float {
    val x = fraction.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** Follow the visible plate continuously when neutral ink needs to change polarity. */
internal fun glassPaneForeground(
    original: Color,
    surface: Color,
    minimum: Float,
    darkness: Float,
    glyph: Boolean = false,
    enabled: Boolean = true,
): Color {
    if (original.isSpecified && (!isNeutralForeground(original) || original.alpha == 0f)) return original
    // Black and white have equal contrast near a relative luminance of 0.18.
    // Blend across that range instead of switching either anchor in one frame.
    val darkInk = smoothGlassStep((surface.luminance() - .12f) / .12f)
    if (!original.isSpecified) return lerp(Color.White, Color.Black, darkInk)
    val deficit = minOf(minimum, NeutralRetentionContrast) - contrastAgainst(original, surface)
    if (deficit <= 0f) return original
    // In a light/dark fade the palette already interpolates its ink. Ease out
    // corrective ink through the middle so it cannot reverse that transition.
    val stableInk = 1f - smoothGlassStep(darkness / .25f) * smoothGlassStep((1f - darkness) / .25f)
    val correction = smoothGlassStep(deficit / .18f) * stableInk
    if (glyph && isGentleGlyph(original, enabled)) {
        val shift = (.08f - .16f * darkInk) * correction
        return Color((original.red + shift).coerceIn(0f, 1f),
            (original.green + shift).coerceIn(0f, 1f),
            (original.blue + shift).coerceIn(0f, 1f), original.alpha)
    }
    return lerp(original, lerp(Color.White, Color.Black, darkInk).copy(alpha = original.alpha), correction)
}

@Composable
internal fun surfaceBackdropColor(): Color =
    LocalSurfaceBackdropColor.current ?: LocalAppearance.current.colorPalette.background0.copy(alpha = 1f)

internal fun effectiveSurfaceColor(
    backdrop: Color,
    fill: Color,
    scrim: Color = Color.Transparent,
): Color {
    val underlay = scrim.compositeOver(backdrop)
    return if (fill.isSpecified) fill.compositeOver(underlay) else underlay
}

@Composable
internal fun glassFilmColor(
    settings: GlassSettings,
    role: Color?,
    menu: Boolean = true,
    progress: Float = 1f,
): Color {
    val tint = glassFilmTint(settings, role, menu, progress)
    return tint.copy(alpha = tint.alpha * glassFilmOpacity(settings.film, menu, progress))
}

@Composable
internal fun SurfaceForegroundContent(
    surface: Color?,
    scrim: Color = Color.Transparent,
    preferMeasuredText: Boolean = false,
    glassInkDarkness: Float? = LocalGlassInkDarkness.current,
    content: @Composable () -> Unit,
) {
    val appearance = LocalAppearance.current
    val source = LocalForegroundSourcePalette.current ?: appearance.colorPalette
    val commonNeutral = LocalNeutralForegroundOverride.current
    val amount = LocalNeutralForegroundAmount.current
    val commonSurface = LocalNeutralForegroundSurface.current ?: surface
    val transition = LocalNeutralForegroundTransition.current?.value
    val displayedProgress = LocalPlayerForegroundProgress.current
    val textFrame = remember { PlayerMeasuredInkFrame() }
    val secondaryFrame = remember { PlayerMeasuredInkFrame() }
    val adapt = surface != null || commonNeutral != null || transition != null
    val inheritedContent = LocalContentColor.current
    fun ink(original: Color, minimum: Float, glyph: Boolean = false, frame: PlayerMeasuredInkFrame? = null): Color =
        if (preferMeasuredText && !glyph && surface != null)
            requireNotNull(frame).resolve(original, surface, minimum, displayedProgress)
        else if (transition != null) transition.resolve(original, minimum, glyph = glyph)
        else if (glassInkDarkness != null && surface != null) glassPaneForeground(original, surface, minimum, glassInkDarkness, glyph)
        else if (glyph && surface != null && isNeutralForeground(original) && isGentleGlyph(original, true))
            readableGlyphForeground(original, commonSurface ?: surface, minimum)
        else if (commonNeutral != null && isNeutralForeground(original) && original.alpha > 0f) {
            val target = if (commonSurface != null &&
                contrastAgainst(original, commonSurface) >= minOf(minimum, NeutralRetentionContrast)) original
                else commonNeutral.copy(alpha = original.alpha)
            lerp(original, target, amount)
        } else if (surface != null) readableForeground(original, surface, minimum) else original
    val palette = if (adapt) appearance.colorPalette.copy(
        text = ink(source.text, TextContrast, frame = textFrame),
        textSecondary = ink(source.textSecondary, TextContrast, frame = secondaryFrame),
        textDisabled = source.textDisabled,
        iconButtonPlayer = ink(source.iconButtonPlayer, IconContrast, glyph = true),
    ) else appearance.colorPalette
    // Keep the same composition tree before the first sample and throughout sheet transitions.
    CompositionLocalProvider(
        LocalAppearance provides if (adapt) appearance.copy(colorPalette = palette,
            typography = appearance.typography.withColor(palette.text)) else appearance,
        LocalContentColor provides if (adapt && (!inheritedContent.isSpecified || isNeutralForeground(inheritedContent)))
            palette.text else inheritedContent,
        LocalForegroundSourcePalette provides source,
        LocalSurfaceBackdropColor provides if (adapt) surface else LocalSurfaceBackdropColor.current,
        LocalForegroundSurface provides if (adapt) surface else LocalForegroundSurface.current,
        LocalForegroundScrim provides if (adapt) scrim else LocalForegroundScrim.current,
        LocalGlassInkDarkness provides glassInkDarkness,
        content = content,
    )
}

internal data class NeutralForegroundDecision(val surface: Color?, val anchor: Color?)
internal data class WeightedNeutralDecision(val decision: NeutralForegroundDecision, val weight: Float)

internal data class NeutralForegroundEndpointKey(
    val decision: NeutralForegroundDecision,
    val ink: Color,
    val minimum: Float,
    val plate: Color?,
    val localSurface: Color?,
    val glyph: Boolean,
    val enabled: Boolean,
    val measuredText: Boolean = false,
)

internal class PlayerForegroundMemo(private val capacity: Int = 96) {
    private val endpoints = LinkedHashMap<NeutralForegroundEndpointKey, Color>(16, .75f, true)

    fun resolve(key: NeutralForegroundEndpointKey, calculate: () -> Color): Color = synchronized(endpoints) {
        endpoints[key] ?: calculate().also { result ->
            endpoints[key] = result
            if (endpoints.size > capacity) endpoints.remove(endpoints.keys.first())
        }
    }

    fun clear() = synchronized(endpoints) { endpoints.clear() }
}

private val playerForegroundMemo = PlayerForegroundMemo()

internal fun invalidatePlayerForegroundMemo() = playerForegroundMemo.clear()

internal fun measuredPlayerTextForeground(original: Color, surface: Color, minimum: Float = TextContrast): Color =
    measuredPlayerForeground(original, surface, minimum)

/** Reuses the shared clock, retaining the actual ink when that clock is retargeted. */
internal class PlayerMeasuredInkFrame {
    private var previousProgress: Float? = null
    private var origin: Color? = null
    private var displayed: Color? = null

    fun resolve(original: Color, surface: Color, minimum: Float, progress: Float?): Color {
        val endpoint = measuredPlayerForeground(original, surface, minimum)
        val current = progress?.coerceIn(0f, 1f)
        if (current == null || current >= 1f || !isNeutralForeground(original) || original.alpha == 0f) {
            origin = null
            previousProgress = current
            displayed = endpoint
            return endpoint
        }
        if (origin == null || previousProgress?.let { current < it } == true) origin = displayed ?: original
        previousProgress = current
        return lerp(requireNotNull(origin), endpoint, current).also { displayed = it }
    }
}

internal fun measuredPlayerForeground(original: Color, surface: Color, minimum: Float): Color {
    if (!original.isSpecified) return neutralForeground(surface)
    if (!isNeutralForeground(original) || original.alpha == 0f) return original
    val key = NeutralForegroundEndpointKey(NeutralForegroundDecision(surface, null), original, minimum,
        plate = null, localSurface = null, glyph = false, enabled = true, measuredText = true)
    return playerForegroundMemo.resolve(key) {
        if (contrastAgainst(original, surface) >= minimum) original
        else neutralForeground(surface).copy(alpha = original.alpha)
    }
}

@Composable
internal fun playerControlGlyphColor(
    original: Color,
    plate: Color = Color.Transparent,
    minimum: Float = IconContrast,
    enabled: Boolean = true,
): Color {
    val measuredFrame = remember { PlayerMeasuredInkFrame() }
    val backdrop = LocalForegroundSurface.current
    val progress = LocalPlayerForegroundProgress.current
    if (enabled && backdrop != null && progress != null && progress < 1f) {
        val palette = LocalAppearance.current.colorPalette
        val source = LocalForegroundSourcePalette.current
        // Labels may already carry the measured correction. Resolve their source role once,
        // on the shared frame, before applying this glyph's own plate contrast.
        val ink = if (plate.alpha > 0f) original else when (original) {
            palette.text -> source?.text ?: original
            palette.textSecondary -> source?.textSecondary ?: original
            palette.iconButtonPlayer -> source?.iconButtonPlayer ?: original
            else -> original
        }
        return measuredFrame.resolve(ink, effectiveSurfaceColor(backdrop, plate), minimum, progress)
    }
    val color = surfaceGlyphColor(original, plate, minimum, enabled)
    return if (enabled && backdrop != null)
        measuredFrame.resolve(color, effectiveSurfaceColor(backdrop, plate), minimum,
            progress) else color
}

/** Every role resolves both endpoints from its source ink and uses this single shared clock. */
internal data class NeutralForegroundTransition(
    val from: List<WeightedNeutralDecision>,
    val to: NeutralForegroundDecision,
    val progress: Float,
    private val memo: PlayerForegroundMemo? = null,
) {
    fun resolve(original: Color, minimum: Float = IconContrast, plate: Color? = null,
        localSurface: Color? = null, glyph: Boolean = false, enabled: Boolean = true): Color {
        if (!original.isSpecified || !isNeutralForeground(original) || original.alpha == 0f) return original
        fun resolveEndpoint(decision: NeutralForegroundDecision): Color {
            val background = decision.surface ?: return original
            val actual = if (plate != null && plate.alpha > 0f)
                effectiveSurfaceColor(localSurface ?: background, plate) else background
            if (glyph && isGentleGlyph(original, enabled))
                return readableGlyphForeground(original, actual, minimum, enabled)
            if (plate != null && plate.alpha > 0f)
                return readableForeground(original, actual, minimum)
            if (contrastAgainst(original, background) >= minimum) return original
            return decision.anchor?.copy(alpha = original.alpha) ?: readableForeground(original, background, minimum)
        }
        fun endpoint(decision: NeutralForegroundDecision): Color {
            if (decision.surface == null || memo == null) return resolveEndpoint(decision)
            val key = NeutralForegroundEndpointKey(decision, original, minimum, plate, localSurface, glyph, enabled)
            return memo.resolve(key) { resolveEndpoint(decision) }
        }
        val end = endpoint(to)
        if (progress >= 1f) return end
        var start = original
        var accumulated = 0f
        for ((decision, weight) in from) if (weight > 0f) {
            start = if (accumulated == 0f) endpoint(decision)
                else lerp(start, endpoint(decision), weight / (accumulated + weight))
            accumulated += weight
        }
        return when {
            start == end -> end
            progress <= 0f -> start
            else -> lerp(start, end, progress)
        }
    }

    internal fun frozenBlend(): List<WeightedNeutralDecision> =
        (from.map { it.copy(weight = it.weight * (1f - progress)) } + WeightedNeutralDecision(to, progress))
            .filter { it.weight > 0.00001f }
}

@Composable
internal fun rememberNeutralForegroundForSurface(
    surface: () -> Color?,
): State<NeutralForegroundTransition> {
    // The surface already interpolates, so another ink tween would trail it.
    val currentSurface = rememberUpdatedState(surface)
    return remember {
        derivedStateOf {
            val color = currentSurface.value()
            val decision = NeutralForegroundDecision(color, color?.let(::neutralForeground))
            NeutralForegroundTransition(emptyList(), decision, 1f, playerForegroundMemo)
        }
    }
}

/** Semantic ink before any ancestor surface adjustment, for controls with their own plate. */
@Composable
internal fun surfaceSourcePalette(): ColorPalette =
    LocalForegroundSourcePalette.current ?: LocalAppearance.current.colorPalette

/** Resolve an explicit glyph against the current surface or against its own translucent plate. */
@Composable
internal fun surfaceGlyphColor(original: Color, plate: Color? = null, minimum: Float = IconContrast,
    enabled: Boolean = true): Color {
    val transition = LocalNeutralForegroundTransition.current?.value
    val commonNeutral = LocalNeutralForegroundOverride.current
    val surface = LocalForegroundSurface.current ?: LocalSurfaceBackdropColor.current ?:
        LocalNeutralForegroundSurface.current ?: transition?.to?.surface ?: transition?.from?.firstOrNull()?.decision?.surface
    if (!isNeutralForeground(original) || original.alpha == 0f) return original
    val palette = LocalAppearance.current.colorPalette
    val source = LocalForegroundSourcePalette.current
    // Neutral role inputs may already contain the parent's shared fade. Resolve once from source.
    fun isRole(role: Color): Boolean = original.copy(alpha = role.alpha) == role
    val ink = if (plate != null && plate.alpha > 0f) original else when {
        isRole(palette.text) -> source?.text?.copy(alpha = original.alpha) ?: original
        isRole(palette.textSecondary) -> source?.textSecondary?.copy(alpha = original.alpha) ?: original
        isRole(palette.iconButtonPlayer) -> source?.iconButtonPlayer?.copy(alpha = original.alpha) ?: original
        else -> original
    }
    if (transition != null) return transition.resolve(ink, minimum, plate, surface, glyph = true, enabled = enabled)
    if (surface == null) return original
    val actual = plate?.let { effectiveSurfaceColor(surface, it) } ?: surface
    val decisionSurface = if (plate == null || plate.alpha == 0f)
        LocalNeutralForegroundSurface.current ?: actual else actual
    LocalGlassInkDarkness.current?.let {
        return glassPaneForeground(ink, decisionSurface, minimum, it, glyph = true, enabled = enabled)
    }
    val target = if (isGentleGlyph(ink, enabled)) readableGlyphForeground(ink, decisionSurface, minimum, enabled)
        else if (contrastAgainst(ink, decisionSurface) >= minOf(minimum, NeutralRetentionContrast)) ink
        else if (commonNeutral != null && (plate == null || plate.alpha == 0f)) commonNeutral.copy(alpha = ink.alpha)
        else readableForeground(ink, actual, minimum)
    return if (commonNeutral == null) target else lerp(ink, target, LocalNeutralForegroundAmount.current)
}

/** Keep an inactive track visually distinct when it approaches the adjusted progress color. */
@Composable
internal fun surfaceProgressTrackColor(progress: Color, track: Color, plate: Color? = null): Color {
    val surface = LocalForegroundSurface.current ?: return track
    if (!track.isSpecified || track.alpha == 0f || !isNeutralForeground(track)) return track
    // Player tracks follow the same source roles and clock instead of a second local recoloring.
    if (LocalNeutralForegroundTransition.current != null) return surfaceGlyphColor(track, plate, 1.5f)
    val actual = plate?.let { effectiveSurfaceColor(surface, it) } ?: surface
    val visibleProgress = progress.compositeOver(actual)
    val visibleTrack = track.compositeOver(actual)
    return if (contrastAgainst(visibleProgress, visibleTrack) >= 1.5f) track
        else neutralForeground(actual).copy(alpha = 0.38f)
}

@Composable
internal fun surfaceForegroundScrim(): Color = LocalForegroundScrim.current

/** Keeps decoration polarity on the same continuous local surface as the visible text. */
@Composable
internal fun surfaceNeutralForeground(default: Color): Color =
    LocalForegroundSurface.current?.let { surface ->
        glassPaneForeground(Color.Unspecified, surface, TextContrast, paletteDarkness())
    } ?: default
