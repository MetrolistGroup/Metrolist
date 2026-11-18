package com.metrolist.android.ui.glass

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint

/** Gives only an active fade a buffer large enough for the complete held-control envelope. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun AnimatedVisibilityScope.glassOverflowFade(enterMillis: Int, exitMillis: Int): Modifier {
    val opacity = transition.animateFloat(transitionSpec = {
        tween(if (targetState == EnterExitState.Visible) enterMillis else exitMillis)
    }, label = "glassOverflowFade") { if (it == EnterExitState.Visible) 1f else 0f }
    val outset = headerGlassMotionVisualOutset()
    val paint = remember { Paint() }
    return Modifier.drawWithContent {
        val alpha = opacity.value
        if (alpha >= 1f) {
            drawContent()
        } else if (alpha > 0f) {
            val margin = outset.toPx()
            paint.alpha = alpha
            val canvas = drawContext.canvas
            canvas.saveLayer(Rect(-margin, -margin, size.width + margin, size.height + margin), paint)
            try {
                drawContent()
            } finally {
                canvas.restore()
            }
        }
    }
}
