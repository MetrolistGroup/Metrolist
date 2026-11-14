package com.metrolist.shared.ui.components

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.metrolist.shared.ui.components.lyrics.words.FixedMotionDurationScale
import com.metrolist.shared.ui.components.sliders.rememberWavePhase
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.LocalContentObscured
import com.metrolist.shared.ui.navigation.LocalRootContentActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NowPlayingBars(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    playbackStateDescription: String? = null,
) {
    if (!isPlaying) {
        Icon(
            MetrolistIcons.Default.Pause,
            contentDescription = playbackStateDescription,
            tint = color,
            modifier = modifier.size(20.dp),
        )
        return
    }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val motionEnabled =
        animated && lifecycleState == Lifecycle.State.RESUMED && LocalRootContentActive.current && !LocalContentObscured.current
    val phase = rememberWavePhase(motionEnabled, cycleMillis = 28_000L)

    fun height(
        start: Float,
        end: Float,
        duration: Float,
        easing: Easing,
    ): Float {
        val cycle = (phase.value * 28_000f / duration) % 2f
        val fraction = if (cycle <= 1f) cycle else 2f - cycle
        return start + (end - start) * easing.transform(fraction)
    }

    Canvas(
        modifier =
            modifier
                .size(20.dp)
                .semantics { playbackStateDescription?.let { contentDescription = it } },
    ) {
        val totalW = size.width
        val totalH = size.height
        val barW = totalW * 0.2f
        val gap = (totalW - barW * 3f) / 4f
        val radius = CornerRadius(barW / 2f, barW / 2f)

        val h1 = height(0.45f, 1f, 400f, FastOutSlowInEasing) * totalH
        val h2 = height(1f, 0.3f, 500f, FastOutSlowInEasing) * totalH
        val h3 = height(0.7f, 1f, 350f, LinearEasing) * totalH

        drawRoundRect(
            color = color,
            topLeft = Offset(gap, totalH - h1),
            size = Size(barW, h1),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(gap * 2f + barW, totalH - h2),
            size = Size(barW, h2),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(gap * 3f + barW * 2f, totalH - h3),
            size = Size(barW, h3),
            cornerRadius = radius,
        )
    }
}
