package com.metrolist.shared.ui.components.images

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

@Composable
internal fun ImageLoadingPlaceholder(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    if (!animated) {
        Box(modifier = modifier.clearAndSetSemantics { }.background(base))
        return
    }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val progress = remember { Animatable(-1f) }
    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED && progress.value < 2f) {
            progress.animateTo(2f, repeatable(3, tween(1_100), RepeatMode.Restart))
        }
    }
    val highlight = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier =
            modifier
                .clearAndSetSemantics { }
                .drawWithCache {
                    val brush =
                        Brush.linearGradient(
                            colors = listOf(base, highlight, base),
                            start = Offset.Zero,
                            end = Offset(240f, 240f),
                        )
                    onDrawBehind {
                        val offset = progress.value * 360f
                        translate(left = offset) {
                            drawRect(brush, topLeft = Offset(-offset, 0f), size = size)
                        }
                    }
                },
    )
}
