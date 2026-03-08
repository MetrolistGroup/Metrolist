/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.metrolist.shared.ui.navigation.LocalContentObscured
import com.metrolist.shared.ui.navigation.LocalRootContentActive
import com.valentinilk.shimmer.shimmer
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private const val ENTRANCE_STAGGER_MS = 70L

@Composable
fun Modifier.loadingShimmer(): Modifier {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = lifecycleState == Lifecycle.State.RESUMED && LocalRootContentActive.current && !LocalContentObscured.current
    return if (animate) shimmer() else this
}

@Composable
fun Modifier.expressiveEntrance(index: Int = 0): Modifier {
    val progress = remember { Animatable(0f) }
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        delay((index * ENTRANCE_STAGGER_MS).milliseconds)
        progress.animateTo(1f, spatial)
    }
    val rise = 24.dp
    return graphicsLayer {
        val value = progress.value
        alpha = value.coerceIn(0f, 1f)
        translationY = (1f - value) * rise.toPx()
        val scale = 0.96f + 0.04f * value
        scaleX = scale
        scaleY = scale
    }
}
