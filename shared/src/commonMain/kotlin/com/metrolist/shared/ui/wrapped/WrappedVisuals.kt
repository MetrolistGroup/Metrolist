@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.wrapped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.wrapped_flex
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.screens.StatisticsItem
import com.metrolist.shared.ui.statistics.StatisticsImageColors
import com.metrolist.shared.ui.statistics.StatisticsImageSection
import com.metrolist.shared.ui.theme.rememberReducedMotion
import com.metrolist.shared.ui.utils.settled
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.Font
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

@Composable
internal fun wrappedMotionEnabled(): Boolean {
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    return scale > 0f && !rememberReducedMotion()
}

/** Poster colors. Fixed roles keep the same tones in light and dark themes, so every chapter stays loud. */
@Immutable
internal data class WrappedPalette(
    val background: Color,
    val ink: Color,
    val fill: Color,
    val sticker: Color,
    val onSticker: Color,
    val pill: Color,
    val onPill: Color,
    val vinyl: Color,
    val glow: Color,
)

@Composable
internal fun wrappedPalette(chapter: WrappedChapter): WrappedPalette {
    val c = MaterialTheme.colorScheme
    val pureBlack = c.surface == Color.Black && c.surfaceContainer == Color.Black
    val darkTheme = c.surface.luminance() < 0.5f
    val light: Color
    val deep: Color
    val container: Color
    val onContainer: Color
    val accent: Color
    val inverted: Boolean
    // Posters alternate light and dark and rotate through the three accent hues.
    when (chapter) {
        WrappedChapter.COVER, WrappedChapter.SUMMARY, WrappedChapter.ARTISTS -> {
            light = c.primaryFixed
            deep = c.onPrimaryFixed
            container = c.primaryContainer
            onContainer = c.onPrimaryContainer
            accent = c.tertiaryFixedDim
            inverted = chapter == WrappedChapter.ARTISTS
        }

        WrappedChapter.TIME, WrappedChapter.MIX -> {
            light = c.tertiaryFixed
            deep = c.onTertiaryFixed
            container = c.tertiaryContainer
            onContainer = c.onTertiaryContainer
            accent = c.primaryFixedDim
            inverted = chapter == WrappedChapter.TIME
        }

        WrappedChapter.TRACKS, WrappedChapter.ALBUMS -> {
            light = c.secondaryFixed
            deep = c.onSecondaryFixed
            container = c.secondaryContainer
            onContainer = c.onSecondaryContainer
            accent = if (chapter == WrappedChapter.TRACKS) c.primaryFixedDim else c.tertiaryFixedDim
            inverted = chapter == WrappedChapter.ALBUMS
        }
    }
    return when {
        pureBlack || inverted -> {
            val background = if (pureBlack) Color.Black else deep
            WrappedPalette(background, light, accent, accent, deep, light, deep, accent, accent)
        }

        // In dark theme the light posters move to the theme's dark containers instead of glowing pastel grounds.
        darkTheme -> {
            WrappedPalette(container, onContainer, accent, accent, deep, onContainer, container, deep, accent)
        }

        else -> {
            WrappedPalette(light, deep, deep, deep, accent, deep, light, deep, accent)
        }
    }
}

/** Poster colors for the shared summary image: a lit background, a dark hero card and one color per ranking. */
@Composable
internal fun wrappedShareColors(): StatisticsImageColors {
    val c = MaterialTheme.colorScheme
    return StatisticsImageColors(
        background = c.primaryFixed,
        glow = c.tertiaryFixedDim,
        content = c.onPrimaryFixed,
        muted = c.onPrimaryFixed,
        accent = c.onPrimaryFixed,
        heroContainer = c.onPrimaryFixed,
        heroContent = c.primaryFixed,
        sections =
            listOf(
                StatisticsImageSection(c.tertiaryFixedDim, c.onTertiaryFixed, c.onTertiaryFixed, c.onTertiaryFixed),
                StatisticsImageSection(c.onSecondaryFixed, c.secondaryFixed, c.tertiaryFixedDim, c.secondaryFixed),
                StatisticsImageSection(c.primaryFixedDim, c.onPrimaryFixed, c.onPrimaryFixed, c.onPrimaryFixed),
            ),
        artworkPlaceholder = c.secondaryFixedDim,
    )
}

/** The poster ground: the chapter color, lit by soft pools of the accent color that drift like light on water. */
@Composable
internal fun WrappedBackdrop(
    chapters: List<WrappedChapter>,
    position: () -> Float,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val palettes = chapters.map { wrappedPalette(it) }
    val drift: State<Float>? =
        if (motionEnabled) {
            rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing)))
        } else {
            null
        }
    Canvas(modifier) {
        val raw = position().coerceIn(0f, palettes.lastIndex.toFloat())
        val progress = if (motionEnabled) raw else raw.roundToInt().toFloat()
        val index = floor(progress).toInt()
        val next = (index + 1).coerceAtMost(palettes.lastIndex)
        val fraction = progress - index
        val background = lerp(palettes[index].background, palettes[next].background, fraction)
        val glow = lerp(palettes[index].glow, palettes[next].glow, fraction)
        val light = background.luminance() > 0.5f
        drawRect(background)
        val turn = (drift?.value ?: 0f) * 2f * PI.toFloat()
        val span = maxOf(size.width, size.height)
        repeat(3) { pool ->
            // Whole turns per cycle keep the loop seamless; each pool moves at its own pace.
            val drift = turn * (pool + 2)
            val center =
                Offset(
                    size.width * (0.5f + 0.38f * sin(drift + pool * 2.1f)) -
                        size.width * 0.2f * sin(fraction * PI.toFloat()) * (pool + 1),
                    size.height * (0.3f + 0.25f * pool + 0.12f * cos(drift + pool * 1.7f)),
                )
            val radius = span * (0.42f + 0.08f * pool)
            val color = if (pool == 1) (if (light) Color.White else glow) else glow
            val alpha = if (light) (if (pool == 1) 0.55f else 0.5f) else (if (pool == 1) 0.18f else 0.3f)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)), center, radius), radius, center)
        }
    }
}

internal enum class WrappedPattern(
    val periodMillis: Int,
) {
    RAYS(48_000),
    DIAL(6_000),
    GROOVES(5_000),
    RINGS(2_600),
    STRIPES(1_800),
    WAVES(3_200),
    CHEVRONS(2_400),
}

/** Draws [pattern] centered on this element, bleeding [bleed] past each edge and [bottomBleed] below it. */
@Composable
internal fun Modifier.wrappedPattern(
    pattern: WrappedPattern,
    palette: WrappedPalette,
    motionEnabled: Boolean,
    spin: () -> Float,
    bleed: Dp = 24.dp,
    bottomBleed: Dp = 64.dp,
): Modifier {
    val phase: State<Float>? =
        if (motionEnabled) {
            rememberInfiniteTransition()
                .animateFloat(0f, 1f, infiniteRepeatable(tween(pattern.periodMillis, easing = LinearEasing)))
        } else {
            null
        }
    return drawBehind {
        val edge = bleed.toPx()
        val bottom = size.height + bottomBleed.toPx()
        val area = Rect(-edge, -edge, size.width + edge, bottom)
        // The pattern fades out at its top and bottom instead of ending in a hard cut.
        val fade = 40.dp.toPx() / area.height
        val tail = bottomBleed.toPx() / area.height
        drawContext.canvas.saveLayer(area, Paint())
        clipRect(area.left, area.top, area.right, area.bottom) {
            val swipe = if (motionEnabled) spin() else 0f
            drawWrappedPattern(pattern, palette, phase?.value ?: 0f, swipe, edge, bottomBleed.toPx())
        }
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                fade to Color.Black,
                (1f - tail).coerceAtLeast(fade) to Color.Black,
                1f to if (tail > 0f) Color.Transparent else Color.Black,
                startY = area.top,
                endY = area.bottom,
            ),
            area.topLeft,
            area.size,
            blendMode = BlendMode.DstIn,
        )
        drawContext.canvas.restore()
    }
}

private fun DrawScope.drawWrappedPattern(
    pattern: WrappedPattern,
    palette: WrappedPalette,
    phase: Float,
    swipe: Float,
    edge: Float,
    bottomEdge: Float,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val reach = hypot(size.width / 2f + edge, size.height / 2f + max(edge, bottomEdge))
    val side = min(size.width, size.height)
    val ink = palette.ink
    when (pattern) {
        WrappedPattern.RAYS -> {
            val count = 16
            val sweep = 360f / count
            rotate(phase * 360f + swipe * 90f, center) {
                repeat(count / 2) {
                    drawArc(ink, it * sweep * 2f, sweep, true, center - Offset(reach, reach), Size(reach * 2, reach * 2))
                }
            }
        }

        WrappedPattern.DIAL -> {
            val radius = side * 0.47f
            val hand = phase * 360f + swipe * 120f
            repeat(60) {
                val angle = it * 6f
                val distance = abs(((angle - hand) % 360f + 540f) % 360f - 180f)
                val wave = (1f - distance / 180f).pow(10)
                val length = 10.dp.toPx() + (if (it % 5 == 0) 10.dp.toPx() else 0f) + wave * 34.dp.toPx()
                val radians = (angle - 90f) * PI.toFloat() / 180f
                val direction = Offset(cos(radians), sin(radians))
                drawLine(
                    ink,
                    center + direction * radius,
                    center + direction * (radius - length),
                    if (it % 5 == 0) 6.dp.toPx() else 3.dp.toPx(),
                    StrokeCap.Round,
                )
            }
        }

        WrappedPattern.GROOVES -> {
            val radius = side * 0.49f
            drawCircle(ink, radius, center)
            val groove = palette.background.copy(alpha = 0.22f)
            var r = side * 0.25f
            while (r < radius - 3.dp.toPx()) {
                drawCircle(groove, r, center, style = Stroke(1.dp.toPx()))
                r += 5.dp.toPx()
            }
            rotate(phase * 360f + swipe * 180f, center) {
                val sheen = palette.background.copy(alpha = 0.16f)
                val box = Size(radius * 2, radius * 2)
                drawArc(sheen, -20f, 40f, true, center - Offset(radius, radius), box)
                drawArc(sheen, 160f, 40f, true, center - Offset(radius, radius), box)
            }
        }

        WrappedPattern.RINGS -> {
            val width = 16.dp.toPx()
            val period = width * 2f
            // Rings are born at the center, under the artwork, so none appears out of nowhere.
            var r = ((phase + swipe) % 1f + 1f) % 1f * period
            while (r < reach + width) {
                drawCircle(ink, r, center, style = Stroke(width))
                r += period
            }
        }

        WrappedPattern.STRIPES -> {
            val width = 22.dp.toPx()
            val period = width * 2f
            rotate(-32f, center) {
                var x = center.x - reach - period + ((phase + swipe) % 1f + 1f) % 1f * period
                while (x < center.x + reach) {
                    drawRect(ink, Offset(x, center.y - reach), Size(width, reach * 2))
                    x += period
                }
            }
        }

        WrappedPattern.WAVES -> {
            val spacing = 30.dp.toPx()
            val amplitude = 7.dp.toPx()
            val wavelength = 72.dp.toPx()
            val shift = (phase + swipe) * 2f * PI.toFloat()
            val stroke = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            var y = -edge + spacing / 2f
            var row = 0
            while (y < size.height + bottomEdge) {
                val path = Path()
                var x = -edge
                val offset = if (row % 2 == 0) shift else -shift
                path.moveTo(x, y + sin(x / wavelength * 2f * PI.toFloat() + offset) * amplitude)
                while (x < size.width + edge) {
                    x += 6.dp.toPx()
                    path.lineTo(x, y + sin(x / wavelength * 2f * PI.toFloat() + offset) * amplitude)
                }
                drawPath(path, ink, style = stroke)
                y += spacing
                row++
            }
        }

        WrappedPattern.CHEVRONS -> {
            val spacing = 34.dp.toPx()
            val depth = 12.dp.toPx()
            val run = 22.dp.toPx()
            val shift = ((phase + swipe) % 1f + 1f) % 1f * run * 2f
            val stroke = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            var y = -edge + spacing / 2f
            while (y < size.height + bottomEdge) {
                val path = Path()
                var x = -edge - run * 2f + shift
                var up = true
                path.moveTo(x, y)
                while (x < size.width + edge) {
                    x += run
                    path.lineTo(x, if (up) y - depth else y)
                    up = !up
                }
                drawPath(path, ink, style = stroke)
                y += spacing
            }
        }
    }
}

/** A one-shot 0-to-1 bouncy reveal that runs the first time its page is visible. */
@Composable
internal fun rememberWrappedReveal(
    visible: Boolean,
    motionEnabled: Boolean,
    delayMillis: Long = 0,
    stiffness: Float = Spring.StiffnessMediumLow,
    damping: Float = 0.52f,
    onLanded: (() -> Unit)? = null,
): Animatable<Float, AnimationVector1D> {
    var seen by rememberSaveable { mutableStateOf(false) }
    val reveal = remember { Animatable(if (seen || !motionEnabled) 1f else 0f) }
    LaunchedEffect(visible, motionEnabled) {
        if (!motionEnabled || seen) {
            reveal.snapTo(1f)
        } else if (visible) {
            seen = true
            delay(delayMillis)
            launch {
                delay(70)
                onLanded?.invoke()
            }
            reveal.animateTo(1f, spring(damping, stiffness))
        }
    }
    return reveal
}

@Composable
internal fun Modifier.wrappedEntrance(
    visible: Boolean,
    motionEnabled: Boolean,
    order: Int = 0,
): Modifier {
    val reveal = rememberWrappedReveal(visible, motionEnabled, order * 60L)
    val distance = with(LocalDensity.current) { 48.dp.toPx() }
    return graphicsLayer {
        val v = reveal.value
        translationY = distance * (1f - v)
        scaleX = 0.86f + v * 0.14f
        scaleY = scaleX
        alpha = (v * 2.5f).coerceIn(0f, 1f)
    }
}

/**
 * One Google Sans Flex instance; the bundled file fixes optical size, so only these axes vary. Its internal family
 * name is changed because some Android ROMs swap any font named Google Sans for their system font.
 */
@Composable
private fun flexFont(
    weight: Int,
    width: Float = 100f,
    round: Float = 0f,
) = Font(
    Res.font.wrapped_flex,
    FontWeight(weight),
    variationSettings =
        FontVariation.Settings(
            FontVariation.weight(weight),
            FontVariation.width(width),
            FontVariation.Setting("ROND", round),
        ),
)

private val WrappedWeights = listOf(400, 500, 600, 700, 800, 900)

/** Google Sans Flex for every text role inside Wrapped, in place of the app's system font. */
@Composable
internal fun wrappedTypography(): Typography {
    val text = FontFamily(WrappedWeights.map { flexFont(it) })
    val display = FontFamily(WrappedWeights.map { flexFont(it, width = 104f, round = 40f) })
    val base = MaterialTheme.typography
    return remember(base, text, display) {
        base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = display),
            displayMedium = base.displayMedium.copy(fontFamily = display),
            displaySmall = base.displaySmall.copy(fontFamily = display),
            headlineLarge = base.headlineLarge.copy(fontFamily = display),
            headlineMedium = base.headlineMedium.copy(fontFamily = display),
            headlineSmall = base.headlineSmall.copy(fontFamily = display),
            titleLarge = base.titleLarge.copy(fontFamily = text),
            titleMedium = base.titleMedium.copy(fontFamily = text),
            titleSmall = base.titleSmall.copy(fontFamily = text),
            bodyLarge = base.bodyLarge.copy(fontFamily = text),
            bodyMedium = base.bodyMedium.copy(fontFamily = text),
            bodySmall = base.bodySmall.copy(fontFamily = text),
            labelLarge = base.labelLarge.copy(fontFamily = text),
            labelMedium = base.labelMedium.copy(fontFamily = text),
            labelSmall = base.labelSmall.copy(fontFamily = text),
            // Emphasized roles go heavy and wide, which is where Wrapped and its share image get their voice.
            displayLargeEmphasized = base.displayLarge.copy(fontFamily = display, fontWeight = FontWeight.Black),
            displayMediumEmphasized = base.displayMedium.copy(fontFamily = display, fontWeight = FontWeight.Black),
            displaySmallEmphasized = base.displaySmall.copy(fontFamily = display, fontWeight = FontWeight.Black),
            headlineLargeEmphasized = base.headlineLarge.copy(fontFamily = display, fontWeight = FontWeight.Black),
            headlineMediumEmphasized = base.headlineMedium.copy(fontFamily = display, fontWeight = FontWeight.Black),
            headlineSmallEmphasized = base.headlineSmall.copy(fontFamily = display, fontWeight = FontWeight.ExtraBold),
            titleLargeEmphasized = base.titleLarge.copy(fontFamily = display, fontWeight = FontWeight.ExtraBold),
            titleMediumEmphasized = base.titleMedium.copy(fontFamily = display, fontWeight = FontWeight.ExtraBold),
            titleSmallEmphasized = base.titleSmall.copy(fontFamily = display, fontWeight = FontWeight.ExtraBold),
            bodyLargeEmphasized = base.bodyLarge.copy(fontFamily = text, fontWeight = FontWeight.Bold),
            bodyMediumEmphasized = base.bodyMedium.copy(fontFamily = text, fontWeight = FontWeight.Bold),
            bodySmallEmphasized = base.bodySmall.copy(fontFamily = text, fontWeight = FontWeight.Bold),
            labelLargeEmphasized = base.labelLarge.copy(fontFamily = text, fontWeight = FontWeight.Bold),
            labelMediumEmphasized = base.labelMedium.copy(fontFamily = text, fontWeight = FontWeight.Bold),
            labelSmallEmphasized = base.labelSmall.copy(fontFamily = text, fontWeight = FontWeight.Bold),
        )
    }
}

/** Wide, heavy Google Sans Flex for short titles outside the posters. */
@Composable
internal fun wrappedTitleStyle(): TextStyle =
    TextStyle(fontFamily = FontFamily(flexFont(900, width = 104f, round = 40f)), fontSize = 24.sp, lineHeight = 28.sp)

/** Condensed, heavy Google Sans Flex for stickers and short labels. */
@Composable
internal fun wrappedLabelStyle(): TextStyle =
    TextStyle(fontFamily = FontFamily(flexFont(800, width = 88f)), fontSize = 22.sp, lineHeight = 26.sp)

/** Tabular Google Sans Flex for counts, ranks and stat pills. */
@Composable
internal fun wrappedNumberStyle(): TextStyle =
    TextStyle(
        fontFamily = FontFamily(flexFont(700)),
        fontSize = 16.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = "tnum",
    )

@Composable
internal expect fun WrappedStatusBarAppearance(lightBackground: Boolean)

/**
 * Poster headline in Google Sans Flex. As it enters, its weight and width spring from regular past their
 * final values and settle, so the letters visibly flex into place.
 */
@Composable
internal fun WrappedHeadline(
    text: String,
    palette: WrappedPalette,
    modifier: Modifier = Modifier,
    maxFontSize: TextUnit = 96.sp,
    minFontSize: TextUnit = 28.sp,
    maxLines: Int = 2,
    textAlign: TextAlign = TextAlign.Start,
    visible: Boolean = true,
    motionEnabled: Boolean = false,
) {
    val flex = rememberWrappedReveal(visible, motionEnabled, 60, Spring.StiffnessLow, 0.32f)
    // Font variations are cached per instance, so step the axes instead of building a face every frame.
    val weight = (400 + 520 * flex.value).coerceIn(400f, 1000f).let { (it / 40f).roundToInt() * 40 }
    val width = (84f + 24f * flex.value).coerceIn(75f, 125f).let { (it / 4f).roundToInt() * 4f }
    val finalStyle =
        TextStyle(
            fontFamily = FontFamily(flexFont(920, width = 108f, round = 40f)),
            lineHeight = 1.0.em,
            letterSpacing = (-0.02).em,
            fontFeatureSettings = "tnum",
            textAlign = textAlign,
        )
    val style = finalStyle.copy(fontFamily = FontFamily(flexFont(weight, width = width, round = 40f)))
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        // A small margin keeps rounding in the text layout from tipping an exact fit into an ellipsis.
        val room = (constraints.maxWidth * 0.96f).toInt()
        val (lines, size) =
            remember(text, finalStyle, maxFontSize, minFontSize, room, maxLines) {
                fun width(value: String) =
                    measurer.measure(value, finalStyle.copy(fontSize = maxFontSize), softWrap = false, maxLines = 1).size.width

                fun fit(width: Int) = if (width > room && room > 0) maxFontSize.value * room / width else maxFontSize.value
                val single = fit(width(text))
                // One line reads best; a second line is used only when one line would shrink the text too far.
                if (maxLines == 1 || single >= maxOf(minFontSize.value, maxFontSize.value * 0.4f)) {
                    1 to single
                } else {
                    // Never split a word: the widest word sets the ceiling. Then shrink until the whole title fits the lines.
                    var size = fit(text.split(' ').filter { it.isNotEmpty() }.maxOfOrNull { width(it) } ?: 0)
                    while (size > minFontSize.value &&
                        measurer
                            .measure(
                                text,
                                finalStyle.copy(fontSize = size.sp),
                                maxLines = maxLines,
                                constraints = Constraints(maxWidth = room.coerceAtLeast(1)),
                            ).hasVisualOverflow
                    ) {
                        size -= 2f
                    }
                    maxLines to size
                }
            }
        // Sizes are measured at the heaviest, widest setting, so the lighter frames of the entrance always fit.
        Text(
            text,
            Modifier.fillMaxWidth(),
            color = palette.fill,
            style = style.copy(fontSize = maxOf(size, minFontSize.value).sp),
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A count-up figure set as a poster headline. */
@Composable
internal fun WrappedFigure(
    value: Long,
    format: (Long) -> String,
    palette: WrappedPalette,
    visible: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    maxFontSize: TextUnit = 104.sp,
    textAlign: TextAlign = TextAlign.Start,
) {
    var seen by rememberSaveable { mutableStateOf(false) }
    val progress = remember(value) { Animatable(if (seen || !motionEnabled) 1f else 0f) }
    val motion = MaterialTheme.motionScheme
    LaunchedEffect(value, visible, motionEnabled) {
        if (seen || !motionEnabled) {
            progress.snapTo(1f)
        } else if (visible) {
            seen = true
            progress.animateTo(1f, motion.slowEffectsSpec())
        }
    }
    WrappedHeadline(
        format((value * progress.value).roundToLong().coerceIn(0L, value)),
        palette,
        modifier.clearAndSetSemantics { contentDescription = format(value) },
        maxFontSize = maxFontSize,
        maxLines = 1,
        textAlign = textAlign,
        visible = visible,
        motionEnabled = motionEnabled,
    )
}

/** A slightly tilted label that lands on the poster with a short bounce and a haptic tick. */
@Composable
internal fun WrappedSticker(
    text: String,
    palette: WrappedPalette,
    visible: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    tilt: Float = -2f,
    delayMillis: Long = 260,
) {
    val haptics = rememberMetrolistHaptics()
    val reveal =
        rememberWrappedReveal(visible, motionEnabled, delayMillis, Spring.StiffnessMedium, 0.6f) {
            haptics.perform(HapticEvent.SEGMENT_TICK)
        }
    Text(
        text,
        modifier
            .graphicsLayer {
                val v = reveal.value
                scaleX = 1.25f - 0.25f * v
                scaleY = scaleX
                rotationZ = tilt - 6f * (1f - v)
                alpha = (v * 4f).coerceIn(0f, 1f)
            }.background(palette.sticker, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        color = palette.onSticker,
        style = wrappedLabelStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun WrappedPills(
    labels: List<String>,
    palette: WrappedPalette,
    visible: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    delayMillis: Long = 420,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val reveal = rememberWrappedReveal(visible, motionEnabled, delayMillis + index * 80L, Spring.StiffnessMedium, 0.5f)
            Text(
                label,
                Modifier
                    .graphicsLayer {
                        scaleX = reveal.value
                        scaleY = reveal.value
                    }.background(palette.pill, CircleShape)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                color = palette.onPill,
                style = wrappedNumberStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Clips to [morph] at [progress], rotated by [rotation] degrees around the center. */
private class WrappedMorphShape(
    private val morph: Morph,
    private val progress: Float,
    private val rotation: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val p = progress.coerceIn(0f, 1f)
        // Rotated corners of a unit-square shape reach past the box and get cut flat by the image edge,
        // so shrink the shape until its farthest point fits the box's inscribed circle at any angle.
        val points =
            morph.asCubics(p).flatMap { cubic ->
                (0..8).map { step ->
                    val t = step / 8f
                    val u = 1f - t
                    val a = u * u * u
                    val b = 3f * u * u * t
                    val c = 3f * u * t * t
                    val d = t * t * t
                    Offset(
                        a * cubic.anchor0X + b * cubic.control0X + c * cubic.control1X + d * cubic.anchor1X,
                        a * cubic.anchor0Y + b * cubic.control0Y + c * cubic.control1Y + d * cubic.anchor1Y,
                    )
                }
            }
        val centerX = (points.minOf { it.x } + points.maxOf { it.x }) / 2f
        val centerY = (points.minOf { it.y } + points.maxOf { it.y }) / 2f
        val reach = points.maxOf { hypot(it.x - centerX, it.y - centerY) }
        val fit = if (reach > 0.5f) 0.5f / reach else 1f
        val path = morph.toPath(p, Path())
        path.transform(
            Matrix().apply {
                scale(size.width, size.height)
                translate(0.5f, 0.5f)
                rotateZ(rotation)
                scale(fit, fit)
                translate(-centerX, -centerY)
            },
        )
        return Outline.Generic(path.settled())
    }
}

/**
 * Artwork that pops in from a circle into [polygon], keeps its outline turning while the image stays upright,
 * and squishes and spins when poked.
 */
@Composable
internal fun WrappedShapedArtwork(
    item: StatisticsItem,
    polygon: RoundedPolygon,
    visible: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    delayMillis: Long = 0,
    turnMillis: Int = 0,
    tilt: Float = 0f,
) {
    val morph = remember(polygon) { Morph(MaterialShapes.Circle, polygon) }
    val reveal = rememberWrappedReveal(visible, motionEnabled, delayMillis, Spring.StiffnessLow, 0.48f)
    val turn: State<Float>? =
        if (motionEnabled && turnMillis > 0) {
            rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(turnMillis, easing = LinearEasing)))
        } else {
            null
        }
    val poke = remember { Animatable(0f) }
    val squish = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberMetrolistHaptics()
    Box(
        modifier
            // Without motion the artwork takes no taps, so they reach chapter navigation.
            .then(
                if (motionEnabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                haptics.perform(HapticEvent.PRESS)
                                scope.launch { squish.animateTo(0.86f, spring(0.6f, Spring.StiffnessHigh)) }
                                val released = tryAwaitRelease()
                                scope.launch { squish.animateTo(1f, spring(0.3f, Spring.StiffnessMedium)) }
                                // A press that turned into a scroll or swipe only springs back.
                                if (released) {
                                    haptics.perform(HapticEvent.CONFIRM)
                                    scope.launch { poke.animateTo(poke.targetValue + 120f, spring(0.55f, Spring.StiffnessLow)) }
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ).graphicsLayer {
                val v = reveal.value
                val scale = (0.35f + 0.65f * v) * squish.value
                scaleX = scale
                scaleY = scale
                rotationZ = tilt - 50f * (1f - v)
                alpha = (v * 3f).coerceIn(0f, 1f)
            }.graphicsLayer {
                shape = WrappedMorphShape(morph, reveal.value, (turn?.value ?: 0f) + poke.value)
                clip = true
            }.background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (item.thumbnailUrl == null) {
            Icon(MetrolistIcons.Outlined.MusicNote, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        } else {
            AsyncImage(item.thumbnailUrl, Modifier.fillMaxSize(), contentDescription = null)
        }
    }
}

/** A spinning burst badge carrying a rank. */
@Composable
internal fun WrappedRankBadge(
    rank: String,
    palette: WrappedPalette,
    visible: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val reveal = rememberWrappedReveal(visible, motionEnabled, 520, Spring.StiffnessMedium, 0.4f)
    val turn: State<Float>? =
        if (motionEnabled) {
            rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = LinearEasing)))
        } else {
            null
        }
    val morph = remember { Morph(MaterialShapes.SoftBurst, MaterialShapes.SoftBurst) }
    Box(
        modifier
            .graphicsLayer {
                scaleX = reveal.value
                scaleY = reveal.value
                rotationZ = -12f
            }.graphicsLayer {
                shape = WrappedMorphShape(morph, 1f, turn?.value ?: 0f)
                clip = true
            }.background(palette.sticker),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            rank,
            color = palette.onSticker,
            style = TextStyle(fontFamily = FontFamily(flexFont(900, width = 104f)), fontSize = 34.sp),
            maxLines = 1,
        )
    }
}

/** Static artwork for rankings and the summary trio. */
@Composable
internal fun WrappedArtwork(
    item: StatisticsItem,
    modifier: Modifier,
    shape: Shape,
) {
    Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer, shape), contentAlignment = Alignment.Center) {
        if (item.thumbnailUrl == null) {
            Icon(MetrolistIcons.Outlined.MusicNote, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        } else {
            AsyncImage(item.thumbnailUrl, Modifier.fillMaxSize(), contentDescription = null, shape = shape)
        }
    }
}
