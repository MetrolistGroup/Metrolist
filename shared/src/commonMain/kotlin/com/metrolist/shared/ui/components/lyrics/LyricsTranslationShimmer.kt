package com.metrolist.shared.ui.components.lyrics

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.words.LyricsAnimationStyle
import com.metrolist.shared.ui.components.lyrics.words.rememberLyricsAnimationVisibility
import com.metrolist.shared.ui.theme.rememberReducedMotion

@Composable
internal fun rememberTranslationShimmer(enabled: Boolean): State<Float>? {
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val reducedMotion = rememberReducedMotion()
    val visible by rememberLyricsAnimationVisibility().collectAsState(false)
    if (!enabled || !visible || !Settings.shared.lyricsAiTranslationEnabled || motionScale <= 0f || reducedMotion ||
        LyricsAnimationStyle.fromString(Settings.shared.lyricsAnimationStyle) == LyricsAnimationStyle.NONE
    ) {
        return null
    }
    return rememberInfiniteTransition(label = "lyricsTranslation").animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart),
        label = "lyricsTranslationPosition",
    )
}

internal fun Modifier.lyricsTranslationShimmer(
    phase: State<Float>?,
    color: Color,
): Modifier =
    if (phase == null) {
        this
    } else {
        graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
            drawContent()
            val center = size.width * phase.value
            val radius = size.width * 0.45f
            drawRect(
                brush =
                    Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, color.copy(alpha = 0.18f), Color.Transparent),
                        startX = center - radius,
                        endX = center + radius,
                    ),
                blendMode = BlendMode.SrcAtop,
            )
        }
    }
