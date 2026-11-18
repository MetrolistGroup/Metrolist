package com.metrolist.android.ui.glass

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.ceil
import kotlin.math.max

/**
 * Physical pointers stay inside the window. Bound the existing spring's visual envelope
 * from that maximum travel, rather than cutting a held control at an arbitrary margin.
 * Header actions are 40..48dp thick, with at most two connected 44dp cells.
 */
@Composable
internal fun headerGlassMotionVisualOutset(): Dp {
    val window = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) {
        val minimumThickness = minOf(GlassActionCellWidth, GlassActionCellHeight).toPx()
        val maximumThickness = maxOf(48.dp, GlassActionCellWidth, GlassActionCellHeight).toPx()
        val maximumLength = maxOf(48.dp, GlassActionCellWidth * 2, GlassActionCellHeight * 2).toPx()
        val expansion = 4.dp.toPx()
        // The damped press spring overshoots 1 by < .2; 1.5 bounds its complete tail.
        val pressEnvelope = 1.5f
        val pointerTravel = max(window.width, window.height).toFloat()
        val scaleOutset = expansion * .5f *
            (pressEnvelope * maximumLength / minimumThickness + pointerTravel / minimumThickness)
        ceil(maximumThickness + scaleOutset).toDp()
    }
}

/** One physical spring for the complete connected material, glyphs and separators. */
internal class GlassGroupPressMotion(val highlight: InteractiveHighlight) {
    private var owner: Any? by mutableStateOf(null)
    val held: Boolean get() = owner != null

    fun press(token: Any, position: Offset) {
        if (owner != null && owner !== token) return
        owner = token
        highlight.startPress(position)
    }

    fun move(token: Any, position: Offset) {
        if (owner === token) highlight.movePress(position)
    }

    fun release(token: Any) {
        if (owner !== token) return
        owner = null
        highlight.releasePress()
    }
}

internal val LocalGlassGroupPressMotion = staticCompositionLocalOf<GlassGroupPressMotion?> { null }

/** Ancestors that own a visual clip can raise their complete unmasked content together. */
internal class GlassGroupElevation(private val parent: GlassGroupElevation? = null) {
    private val owners = mutableStateListOf<Any>()
    val raised: Boolean get() = owners.isNotEmpty()
    fun acquire(owner: Any) {
        if (owner !in owners) {
            owners.add(owner)
            parent?.acquire(owner)
        }
    }
    fun release(owner: Any) {
        if (owners.remove(owner)) parent?.release(owner)
    }
}

internal val LocalGlassGroupElevation = staticCompositionLocalOf<GlassGroupElevation?> { null }

/** Draw-only motion keeps hit slots and dropdown source anchors in their original seats. */
@Composable
internal fun GlassActionGroupMotionHost(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    settings: GlassSettings = rememberGlassSettings(),
    content: @Composable BoxScope.() -> Unit,
) {
    val policy = settings.interactionPolicy()
    val inherited = LocalGlassGroupPressMotion.current
    val scope = rememberCoroutineScope()
    val own = remember(scope) { GlassGroupPressMotion(InteractiveHighlight(scope)) }
    val motion = if (enabled && policy.jellyIntensity > 0f) inherited ?: own else null
    val active by remember(own, motion) { derivedStateOf {
        motion === own && (own.held || own.highlight.pressProgress != 0f || own.highlight.offset != Offset.Zero)
    } }
    val elevation = LocalGlassGroupElevation.current
    DisposableEffect(elevation, own, active) {
        if (active) elevation?.acquire(own)
        onDispose { elevation?.release(own) }
    }
    Box(modifier.zIndex(if (active) 1f else 0f).then(if (motion === own) Modifier.drawWithContent {
        val frame = glassButtonMotion(size, 4.dp.toPx(), own.highlight, policy.jellyIntensity)
        withTransform({
            translate(frame.x, frame.y)
            scale(frame.scaleX, frame.scaleY, center)
        }) { this@drawWithContent.drawContent() }
    } else Modifier)) {
        CompositionLocalProvider(LocalGlassGroupPressMotion provides motion,
            LocalGlassInteractionSettings provides settings) { content() }
    }
}

/** Only an actual enabled action can start motion; empty group space has no detector. */
@Composable
internal fun Modifier.glassGroupPressMotion(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    retainPressUntilUp: Boolean = true,
): Modifier {
    val motion = LocalGlassGroupPressMotion.current ?: return this
    if (!enabled) return this
    val token = remember(motion, interactionSource) { Any() }
    val state = remember(token) { GroupCellPressState() }
    DisposableEffect(motion, token) { onDispose { motion.release(token) } }
    LaunchedEffect(motion, token, interactionSource) {
        interactionSource.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    state.press = event
                    if (!state.pointerHeld) motion.press(token, event.pressPosition)
                }
                is PressInteraction.Release -> if (state.press === event.press) {
                    state.press = null
                    if (!state.pointerHeld) motion.release(token)
                }
                is PressInteraction.Cancel -> if (state.press === event.press) {
                    state.press = null
                    if (!state.pointerHeld) motion.release(token)
                }
            }
        }
    }
    return pointerInput(motion, token, retainPressUntilUp) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            state.pointerHeld = retainPressUntilUp
            if (retainPressUntilUp) motion.press(token, down.position)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val primary = event.changes.firstOrNull { it.id == down.id }
                    if (primary == null || !primary.pressed) break
                    if (retainPressUntilUp || state.press != null) motion.move(token, primary.position)
                }
            } finally {
                state.pointerHeld = false
                state.press = null
                motion.release(token)
            }
        }
    }
}

private class GroupCellPressState {
    var press: PressInteraction.Press? = null
    var pointerHeld = false
}
