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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.screens.StatisticsItem
import com.metrolist.shared.ui.theme.rememberReducedMotion
import com.metrolist.shared.ui.utils.settled
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val shadow: Color,
    val outline: Color,
    val sticker: Color,
    val onSticker: Color,
    val pill: Color,
    val onPill: Color,
)

@Composable
internal fun wrappedPalette(chapter: WrappedChapter): WrappedPalette {
    val c = MaterialTheme.colorScheme
    val pureBlack = c.surface == Color.Black && c.surfaceContainer == Color.Black
    val light: Color
    val deep: Color
    val accent: Color
    val inverted: Boolean
    when (chapter) {
        WrappedChapter.COVER -> {
            light = c.primaryFixed
            deep = c.onPrimaryFixed
            accent = c.tertiaryFixedDim
            inverted = false
        }

        WrappedChapter.TIME -> {
            light = c.tertiaryFixed
            deep = c.onTertiaryFixed
            accent = c.primaryFixedDim
            inverted = true
        }

        WrappedChapter.TRACKS -> {
            light = c.secondaryFixed
            deep = c.onSecondaryFixed
            accent = c.primaryFixedDim
            inverted = false
        }

        WrappedChapter.ARTISTS -> {
            light = c.primaryFixed
            deep = c.onPrimaryFixed
            accent = c.tertiaryFixedDim
            inverted = true
        }

        WrappedChapter.ALBUMS -> {
            light = c.tertiaryFixed
            deep = c.onTertiaryFixed
            accent = c.primaryFixedDim
            inverted = false
        }

        WrappedChapter.SUMMARY -> {
            light = c.secondaryFixed
            deep = c.onSecondaryFixed
            accent = c.tertiaryFixedDim
            inverted = true
        }
    }
    return if (!inverted && !pureBlack) {
        WrappedPalette(light, deep, accent, deep, deep, deep, accent, deep, light)
    } else {
        val background = if (pureBlack) Color.Black else deep
        WrappedPalette(background, light, light, accent, background, accent, deep, light, deep)
    }
}

@Composable
internal fun WrappedBackdrop(
    chapters: List<WrappedChapter>,
    position: () -> Float,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val backgrounds = chapters.map { wrappedPalette(it).background }
    Canvas(modifier) {
        val raw = position().coerceIn(0f, backgrounds.lastIndex.toFloat())
        val progress = if (motionEnabled) raw else raw.roundToInt().toFloat()
        val index = floor(progress).toInt()
        val next = (index + 1).coerceAtMost(backgrounds.lastIndex)
        drawRect(lerp(backgrounds[index], backgrounds[next], progress - index))
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
        val amplitude = 7.dp.toPx()
        val wavelength = 44.dp.toPx()
        val shift = (phase?.value ?: 0f) * 2f * PI.toFloat()
        // A wavy lower edge keeps the pattern from ending in a flat cut behind the headline.
        val clip =
            Path().apply {
                moveTo(-edge, -edge)
                lineTo(size.width + edge, -edge)
                var x = size.width + edge
                lineTo(x, bottom)
                while (x > -edge) {
                    x -= 4.dp.toPx()
                    lineTo(x, bottom - amplitude + sin(x / wavelength * 2f * PI.toFloat() + shift) * amplitude)
                }
                close()
            }
        clipPath(clip) {
            val swipe = if (motionEnabled) spin() else 0f
            drawWrappedPattern(pattern, palette, phase?.value ?: 0f, swipe, edge, bottomBleed.toPx())
        }
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
            var r = side * 0.3f + ((phase + swipe) % 1f + 1f) % 1f * period
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

/** Display text with a hard offset shadow and outline, like a screen-printed poster headline. */
@Composable
internal fun WrappedHeadline(
    text: String,
    palette: WrappedPalette,
    modifier: Modifier = Modifier,
    maxFontSize: TextUnit = 96.sp,
    minFontSize: TextUnit = 28.sp,
    maxLines: Int = 2,
    depth: Dp = 5.dp,
    textAlign: TextAlign = TextAlign.Start,
) {
    val outlineWidth = with(LocalDensity.current) { 6.dp.toPx() }
    val style =
        MaterialTheme.typography.displayLargeEmphasized.copy(
            fontWeight = FontWeight.Black,
            lineHeight = 1.02.em,
            letterSpacing = (-0.02).em,
            fontFeatureSettings = "tnum",
            textAlign = textAlign,
        )
    val outline = Stroke(outlineWidth, join = StrokeJoin.Round)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        // Cap the size so the widest word fits on one line; auto-size alone happily splits words.
        val room = constraints.maxWidth - with(LocalDensity.current) { (depth + 6.dp).roundToPx() }
        val widest =
            remember(text, style, maxFontSize, room) {
                text.split(' ').filter { it.isNotEmpty() }.maxOfOrNull {
                    measurer.measure(it, style.copy(fontSize = maxFontSize), softWrap = false, maxLines = 1).size.width
                } ?: 0
            }
        val fitted = if (widest > room && room > 0) maxFontSize * (room.toFloat() / widest) else maxFontSize
        val autoSize =
            TextAutoSize.StepBased(
                minFontSize = minFontSize,
                maxFontSize = if (fitted.value < minFontSize.value) minFontSize else fitted,
            )
        val layer = Modifier.fillMaxWidth().clearAndSetSemantics {}
        Text(
            text,
            layer.offset(depth * 0.8f, depth),
            color = palette.shadow,
            style = style,
            autoSize = autoSize,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text,
            layer.offset(depth * 0.8f, depth),
            color = palette.shadow,
            style = style.copy(drawStyle = outline),
            autoSize = autoSize,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text,
            layer,
            color = palette.outline,
            style = style.copy(drawStyle = outline),
            autoSize = autoSize,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text,
            Modifier.fillMaxWidth(),
            color = palette.fill,
            style = style,
            autoSize = autoSize,
            maxLines = maxLines,
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
    )
}

/** A tilted label that slaps onto the poster with a bounce and a haptic tick. */
@Composable
internal fun WrappedSticker(
    text: String,
    palette: WrappedPalette,
    visible: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    tilt: Float = -4f,
    delayMillis: Long = 260,
) {
    val haptics = rememberMetrolistHaptics()
    val reveal =
        rememberWrappedReveal(visible, motionEnabled, delayMillis, Spring.StiffnessMedium, 0.45f) {
            haptics.perform(HapticEvent.SEGMENT_TICK)
        }
    Text(
        text,
        modifier
            .graphicsLayer {
                val v = reveal.value
                scaleX = 1.7f - 0.7f * v
                scaleY = scaleX
                rotationZ = tilt - 16f * (1f - v)
                alpha = (v * 4f).coerceIn(0f, 1f)
            }.background(palette.sticker, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        color = palette.onSticker,
        style = MaterialTheme.typography.titleLargeEmphasized.copy(fontWeight = FontWeight.Black),
        maxLines = 2,
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
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                color = palette.onPill,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                maxLines = 1,
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
        val path = morph.toPath(progress.coerceIn(0f, 1f), Path())
        path.transform(
            Matrix().apply {
                scale(size.width, size.height)
                translate(0.5f, 0.5f)
                rotateZ(rotation)
                translate(-0.5f, -0.5f)
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
            .pointerInput(motionEnabled) {
                detectTapGestures(
                    onPress = {
                        if (!motionEnabled) return@detectTapGestures
                        haptics.perform(HapticEvent.PRESS)
                        scope.launch { squish.animateTo(0.86f, spring(0.6f, Spring.StiffnessHigh)) }
                        tryAwaitRelease()
                        haptics.perform(HapticEvent.CONFIRM)
                        scope.launch { squish.animateTo(1f, spring(0.3f, Spring.StiffnessMedium)) }
                        scope.launch { poke.animateTo(poke.targetValue + 120f, spring(0.55f, Spring.StiffnessLow)) }
                    },
                )
            }.graphicsLayer {
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
            style = MaterialTheme.typography.displaySmallEmphasized.copy(fontWeight = FontWeight.Black),
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
