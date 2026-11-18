package com.metrolist.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.MarqueeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay

@Composable
fun rememberTrackMarquee(
    trackId: String,
    enabled: Boolean,
    initialDelayMillis: Long,
): Modifier {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = enabled && lifecycleState == Lifecycle.State.RESUMED
    var attempted by remember(trackId) { mutableStateOf(false) }
    var running by remember(trackId) { mutableStateOf(false) }
    LaunchedEffect(trackId, active) {
        if (!active) {
            running = false
        } else if (!attempted) {
            delay(initialDelayMillis)
            attempted = true
            running = true
        }
    }
    return if (active && running) {
        Modifier.playbackMarquee(iterations = 1, initialDelayMillis = 0, velocity = 30.dp)
    } else {
        Modifier
    }
}

fun Modifier.singleLineMarquee(): Modifier = playbackMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp)

expect fun Modifier.playbackMarquee(
    iterations: Int = MarqueeDefaults.Iterations,
    initialDelayMillis: Int = MarqueeDefaults.RepeatDelayMillis,
    velocity: Dp = MarqueeDefaults.Velocity,
): Modifier

internal expect suspend fun Animatable<Float, AnimationVector1D>.animateMarquee(
    distance: Int,
    durationMillis: Int,
)
