package com.metrolist.shared.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeDefaults
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.unknown_artist
import com.metrolist.shared.generated.resources.view_artist
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.ui.components.CollectionPreviews
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.roundToInt

private object ArtistMarqueeDurationScale : MotionDurationScale {
    override val scaleFactor = 1f
}

internal data class ArtistMarqueeCycle(
    val distance: Int,
    val durationMillis: Int,
)

internal fun artistMarqueeCycle(
    contentWidth: Int,
    containerWidth: Int,
    pixelsPerSecond: Float,
): ArtistMarqueeCycle? {
    if (contentWidth <= containerWidth) return null
    val distance = contentWidth + (containerWidth / 3f).roundToInt()
    return ArtistMarqueeCycle(distance, ceil(distance / pixelsPerSecond * 1000).toInt())
}

internal fun artistMarqueeFocusOffset(
    offset: Float,
    bounds: IntRect,
    containerWidth: Int,
): Float =
    when {
        bounds.width >= containerWidth || bounds.left < offset -> bounds.left.toFloat()
        bounds.right > offset + containerWidth -> (bounds.right - containerWidth).toFloat()
        else -> offset
    }

// Clip only horizontal marquee overflow; vertical clipping cuts off extended touch targets.
private val HorizontalOverflowClip =
    object : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
        ): Outline = Outline.Rectangle(Rect(0f, -size.height, size.width, size.height * 2f))
    }

@Composable
fun PlayerArtistLine(
    artists: List<ArtistTiny>?,
    onNavigateToArtist: (String) -> Unit,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    iterations: Int = 1,
    initialDelayMillis: Int = 3000,
    fallbackText: String = stringResource(Res.string.unknown_artist),
) {
    var contentWidth by remember { mutableIntStateOf(0) }
    var containerWidth by remember { mutableIntStateOf(0) }
    val offset = remember { Animatable(0f) }
    val layoutDirection = LocalLayoutDirection.current
    val artistBounds = remember(artists, layoutDirection) { mutableStateMapOf<Int, IntRect>() }
    var focusedArtist by remember { mutableStateOf<Int?>(null) }
    val focusedBounds = focusedArtist?.let(artistBounds::get)
    val pixelsPerSecond = with(LocalDensity.current) { 30.dp.toPx() }
    LaunchedEffect(contentWidth, containerWidth, iterations, initialDelayMillis, pixelsPerSecond, focusedArtist, focusedBounds) {
        if (focusedArtist != null) {
            focusedBounds?.let { offset.animateTo(artistMarqueeFocusOffset(offset.value, it, containerWidth)) }
            return@LaunchedEffect
        }
        offset.snapTo(0f)
        val cycle = artistMarqueeCycle(contentWidth, containerWidth, pixelsPerSecond)
        if (iterations <= 0 || cycle == null) return@LaunchedEffect
        withContext(ArtistMarqueeDurationScale) {
            try {
                repeat(iterations) { index ->
                    delay(if (index == 0) initialDelayMillis.toLong() else MarqueeDefaults.RepeatDelayMillis.toLong())
                    offset.animateTo(cycle.distance.toFloat(), tween(cycle.durationMillis, easing = LinearEasing))
                    offset.snapTo(0f)
                }
            } finally {
                if (focusedArtist == null) offset.snapTo(0f)
            }
        }
    }
    val viewArtistLabel = stringResource(Res.string.view_artist)
    val configuration = LocalViewConfiguration.current
    val linkConfiguration =
        remember(configuration) {
            object : ViewConfiguration by configuration {
                override val minimumTouchTargetSize = DpSize(0.dp, configuration.minimumTouchTargetSize.height)
            }
        }
    CompositionLocalProvider(LocalViewConfiguration provides linkConfiguration) {
        Layout(
            modifier = modifier.clip(HorizontalOverflowClip),
            content = {
                repeat(2) { copy ->
                    Row(
                        modifier = if (copy == 0) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() },
                        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (artists.isNullOrEmpty()) {
                            Text(if (artists == null) fallbackText else "", style = style, color = color, maxLines = 1, softWrap = false)
                        } else {
                            artists.forEachIndexed { index, artist ->
                                if (index > 0) Text(", ", style = style, color = color, maxLines = 1, softWrap = false)
                                val id = artist.id
                                Text(
                                    text = artist.displayName,
                                    style = style,
                                    color = color,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier =
                                        if (id.isNullOrBlank()) {
                                            Modifier
                                        } else {
                                            Modifier
                                                .focusProperties { canFocus = copy == 0 }
                                                .onFocusChanged {
                                                    if (copy == 0) {
                                                        if (it.isFocused) {
                                                            focusedArtist = index
                                                        } else if (focusedArtist == index) {
                                                            focusedArtist = null
                                                        }
                                                    }
                                                }.onPlaced {
                                                    if (copy == 0) {
                                                        val x = it.positionInParent().x.roundToInt()
                                                        val start =
                                                            if (layoutDirection == LayoutDirection.Rtl) {
                                                                val rowWidth = it.parentLayoutCoordinates?.size?.width ?: return@onPlaced
                                                                rowWidth - x - it.size.width
                                                            } else {
                                                                x
                                                            }
                                                        artistBounds[index] = IntRect(start, 0, start + it.size.width, it.size.height)
                                                    }
                                                }.focusedPlayerBorder(RoundedCornerShape(10.dp))
                                                .clickable(role = Role.Button, onClickLabel = viewArtistLabel) {
                                                    CollectionPreviews.record(artist)
                                                    onNavigateToArtist(id)
                                                }
                                        },
                                )
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val copies = measurables.map { it.measure(constraints.copy(maxWidth = Constraints.Infinity)) }
            contentWidth = copies.first().width
            containerWidth = constraints.constrainWidth(contentWidth)
            val distance = contentWidth + (containerWidth / 3f).roundToInt()
            layout(containerWidth, copies.first().height) {
                val x = offset.value.roundToInt()
                copies[0].placeRelativeWithLayer(-x, 0)
                if (contentWidth > containerWidth) copies[1].placeRelativeWithLayer(distance - x, 0)
            }
        }
    }
}
