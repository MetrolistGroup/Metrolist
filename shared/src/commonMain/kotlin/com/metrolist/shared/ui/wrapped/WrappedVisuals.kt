@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.wrapped

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.screens.StatisticsItem
import com.metrolist.shared.ui.screens.statisticsArtworkShape
import com.metrolist.shared.ui.theme.rememberReducedMotion
import com.metrolist.shared.ui.utils.settled
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

@Composable
internal fun wrappedMotionEnabled(): Boolean {
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    return scale > 0f && !rememberReducedMotion()
}

@Composable
internal fun WrappedBackdrop(
    chapters: List<WrappedChapter>,
    position: () -> Float,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val pureBlack = colors.surface == Color.Black && colors.surfaceContainer == Color.Black
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val polygons =
        remember(chapters) {
            chapters.map {
                when (it) {
                    WrappedChapter.COVER -> MaterialShapes.Cookie9Sided
                    WrappedChapter.TIME -> MaterialShapes.Puffy
                    WrappedChapter.TRACKS -> MaterialShapes.Cookie4Sided
                    WrappedChapter.ARTISTS -> MaterialShapes.Sunny
                    WrappedChapter.ALBUMS -> MaterialShapes.Cookie12Sided
                    WrappedChapter.SUMMARY -> MaterialShapes.Cookie9Sided
                }
            }
        }
    val morphs = remember(polygons) { polygons.zipWithNext { a, b -> Morph(a, b) } }
    val outlines = polygons.map { it.toPath().settled() }
    val fills =
        chapters.map {
            when (it) {
                WrappedChapter.COVER, WrappedChapter.TRACKS -> colors.primaryContainer
                WrappedChapter.TIME, WrappedChapter.ARTISTS -> colors.tertiaryContainer
                WrappedChapter.ALBUMS -> colors.secondaryContainer
                WrappedChapter.SUMMARY -> colors.surfaceContainerHigh
            }
        }
    val path = remember { Path() }
    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val raw = position().coerceIn(0f, chapters.lastIndex.toFloat())
        val progress = if (motionEnabled) raw else raw.roundToInt().toFloat()
        val index = floor(progress).toInt()
        val fraction = progress - index
        val next = (index + 1).coerceAtMost(chapters.lastIndex)
        drawRect(if (pureBlack) Color.Black else lerp(fills[index], fills[next], fraction))
        if (index < morphs.size && motionEnabled) {
            morphs[index].toPath(fraction, path)
        } else {
            path.rewind()
            path.addPath(outlines[index])
        }
        path.settled()
        val diameter = minOf(size.width * 1.35f, size.height * 0.95f)
        val turn = if (motionEnabled) progress * 36f else 0f
        val x = 0.5f + cos(progress * 1.2f) * 0.22f
        val y = 0.25f + sin(progress * 0.8f) * 0.18f
        withTransform({
            translate(size.width * (if (rtl) 1f - x else x) - diameter / 2, size.height * y - diameter / 2)
            scale(diameter, diameter, pivot = Offset.Zero)
            rotate(if (rtl) -turn else turn, pivot = Offset(0.5f, 0.5f))
        }) {
            if (pureBlack) {
                drawPath(path, colors.primary.copy(alpha = 0.3f), style = Stroke(1.5.dp.toPx() / diameter))
            } else {
                drawPath(path, colors.surface.copy(alpha = 0.12f))
            }
        }
    }
}

@Composable
internal fun Modifier.wrappedEntrance(
    visible: Boolean,
    motionEnabled: Boolean,
    order: Int = 0,
): Modifier {
    var seen by rememberSaveable { mutableStateOf(false) }
    val spatial = remember { Animatable(if (seen || !motionEnabled) 1f else 0f) }
    val effects = remember { Animatable(if (seen || !motionEnabled) 1f else 0f) }
    val motion = MaterialTheme.motionScheme
    val distance = with(LocalDensity.current) { 28.dp.toPx() }
    LaunchedEffect(visible, motionEnabled) {
        if (!motionEnabled || seen) {
            spatial.snapTo(1f)
            effects.snapTo(1f)
        } else if (visible) {
            seen = true
            delay(order * 45L)
            coroutineScope {
                launch { spatial.animateTo(1f, motion.defaultSpatialSpec()) }
                launch { effects.animateTo(1f, motion.defaultEffectsSpec()) }
            }
        }
    }
    return graphicsLayer {
        translationY = distance * (1f - spatial.value)
        scaleX = 0.94f + spatial.value * 0.06f
        scaleY = scaleX
        alpha = effects.value.coerceIn(0f, 1f)
    }
}

@Composable
internal fun WrappedFigure(
    value: Long,
    format: (Long) -> String,
    visible: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
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
    Text(
        format((value * progress.value).roundToLong().coerceIn(0L, value)),
        modifier.clearAndSetSemantics { contentDescription = format(value) },
        style =
            MaterialTheme.typography.displayLargeEmphasized.copy(
                lineHeight = 1.1.em,
                fontWeight = FontWeight.ExtraBold,
                fontFeatureSettings = "tnum",
            ),
        autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 96.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun WrappedArtwork(
    item: StatisticsItem,
    index: Int,
    modifier: Modifier,
    shape: Shape = statisticsArtworkShape(item, index),
) {
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (item.thumbnailUrl == null) {
            Icon(MetrolistIcons.Outlined.MusicNote, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        } else {
            AsyncImage(item.thumbnailUrl, Modifier.fillMaxSize(), contentDescription = null, shape = shape)
        }
    }
}
