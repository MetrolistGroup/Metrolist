package com.metrolist.android.ui.glass

import kotlin.math.abs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.first

import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.glass.backdrop.backdrops.layerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCombinedBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.metrolist.android.ui.component.toggles.rememberSwitchGestureState
import com.metrolist.android.ui.component.toggles.switchInput
import com.metrolist.android.ui.theme.exactUiRoundnessShape

// Geometry adapted from Kyant LiquidToggle (Apache-2.0), modified for shared NZik material and gestures.
// https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidToggle.kt
/** Controlled liquid toggle; passive decorations leave all input and semantics to their row. */
@Composable
internal fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier,
    enabled: Boolean,
    settings: GlassSettings,
) {
    val motion = isGlassMotionAllowed()
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val gesture = rememberSwitchGestureState(checked, enabled)
    val policy = settings.interactionPolicy()
    val jelly = if (motion) policy.jellyIntensity else 0f
    val position = remember { Animatable(if (checked) 1f else 0f, .001f) }
    val target = if (gesture.dragging) gesture.position else if (checked) 1f else 0f
    LaunchedEffect(target, motion) {
        if (motion) position.animateTo(target, spring(1f, 1000f, .001f)) else position.snapTo(target)
    }
    val held = pressed || gesture.dragging
    var enlarged by remember { mutableStateOf(false) }
    // DampedDragAnimation.press / release: external transitions press too; release
    // waits one frame and keeps the lens enlarged until within 2.5% of its target.
    LaunchedEffect(target, held, motion) {
        if (!motion) { enlarged = false; return@LaunchedEffect }
        if (held || abs(position.value - target) > .001f) enlarged = true
        if (!held) {
            withFrameNanos { }
            snapshotFlow { abs(position.value - position.targetValue) }.first { it < .025f }
            enlarged = false
        }
    }
    val pressProgress by animateFloatAsState(if (enlarged) 1f else 0f,
        if (motion) spring(1f, 1000f, .001f) else snap(), label = "glassSwitchPress")
    val thumbScaleX by animateFloatAsState(if (enlarged) 1.5f else 1f,
        spring(.6f, 250f, .001f), label = "glassSwitchScaleX")
    val thumbScaleY by animateFloatAsState(if (enlarged) 1.5f else 1f,
        spring(.7f, 250f, .001f), label = "glassSwitchScaleY")
    val velocity by animateFloatAsState(if (gesture.dragging) position.velocity / 50f else 0f,
        spring(.5f, 300f, .01f), label = "glassSwitchVelocity")
    val palette = colorPalette()
    val track = lerp(palette.red, palette.accent, position.value)
    val elevation = LocalGlassGroupElevation.current
    val owner = remember { Any() }
    val expanded = pressProgress > 0f && jelly > 0f
    DisposableEffect(elevation, expanded) {
        if (expanded) elevation?.acquire(owner)
        onDispose { elevation?.release(owner) }
    }
    val material = settings.usesGlassMaterial(GlassArea.Switches)
    val background = colorPalette().background0
    val drawBackground: DrawScope.() -> Unit = remember(background) { { drawRect(background) } }
    // Only an exported container or an independent canvas is safe from self-sampling.
    val container = LocalGlassContainerBackdrop.current
    val backdrop = if (material) when {
        container is RenderedMaterialBackdrop -> container
        container != null -> LocalGlassMaterialSource.current ?: container
        else -> rememberCanvasBackdrop(drawBackground)
    } else null
    val trackBackdrop = if (material) rememberLayerBackdrop() else null
    val thumbBackdrop = if (backdrop != null && trackBackdrop != null)
        rememberCombinedBackdrop(backdrop, trackBackdrop) else null
    val input = Modifier.switchInput(checked, onCheckedChange, enabled, interactions, gesture, position.value, 20.dp)
    Box(modifier.then(input).zIndex(if (expanded) 3f else 0f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(64.dp, 28.dp).alpha(if (enabled) 1f else .38f)) {
            Box(Modifier.matchParentSize()
                .then(if (trackBackdrop != null) Modifier.layerBackdrop(trackBackdrop) else Modifier)
                .glassButtonFeedback(interactions, exactUiRoundnessShape(), enabled = enabled && onCheckedChange != null,
                    animateMotion = false, settings = settings)
                .background(track, CircleShape)
                .then(if (material) Modifier.glassMaterial(settings, CircleShape,
                    ownBackdrop = backdrop, filmColor = track, drawShadow = false)
                    else Modifier.glassAreaContour(settings, GlassArea.Switches, CircleShape)))
            Box(Modifier.align(Alignment.CenterStart)
                .offset(x = (2f + 20f * position.value).dp)
                .size(40.dp, 24.dp)
                .graphicsLayer {
                    this.scaleX = (1f + (thumbScaleX - 1f) * jelly) / (1f - (velocity * .75f).coerceIn(-.2f, .2f) * jelly)
                    this.scaleY = (1f + (thumbScaleY - 1f) * jelly) * (1f - (velocity * .25f).coerceIn(-.2f, .2f) * jelly)
                }
                .then(if (material) Modifier.glassMaterial(settings, CircleShape,
                    ownBackdrop = thumbBackdrop, filmColor = Color.White, drawShadow = false)
                    else Modifier.glassAreaContour(settings, GlassArea.Switches, CircleShape))
                .background(Color.White.copy(alpha = if (material)
                    1f - pressProgress * maxOf(policy.rippleIntensity, jelly) else 1f), CircleShape))
        }
    }
}
