package com.metrolist.android.ui.glass

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import com.metrolist.android.utils.toast.enqueueGlassToast
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.theme.uiRoundnessShape
import com.metrolist.android.utils.toast.GlassToastDispatcher
import com.metrolist.android.utils.toast.GlassToastMessage
import com.metrolist.android.utils.toast.toastDurationMillis
import com.metrolist.android.utils.toast.BottomNavigationToastPlacement
import kotlinx.coroutines.delay

/** Must be a sibling AFTER the captured scene: its own pixels never enter [backdrop].
 * [bottomInset] is the safe bottom fallback when there is no measured mini-player/navigation.
 */
@Composable
fun GlassToastHost(
    backdrop: Backdrop?,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    settings: GlassSettings = rememberGlassSettings(GlassScope.Toast),
    onPresenceChanged: (Boolean) -> Unit = {},
) {
    val settings = settings.inScope(GlassScope.Toast)
    val enabled by rememberUpdatedState(settings.enabled && settings.mode(GlassArea.Menus) != GlassAreaMode.Disabled &&
        (!settings.mode(GlassArea.Menus).hasMaterial || backdrop != null || glassRenderer(settings) == GlassRenderer.Tint))
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val queue = remember { mutableStateListOf<GlassToastMessage>() }
    val owner = remember { Any() }
    DisposableEffect(owner, view, lifecycle) {
        GlassToastDispatcher.register(owner) { message ->
            if (enabled && view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                enqueueGlassToast(queue, message)
                true
            } else false
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) queue.clear()
        }
        lifecycle.addObserver(observer)
        onDispose {
            GlassToastDispatcher.unregister(owner)
            lifecycle.removeObserver(observer)
            queue.clear()
        }
    }
    val message = queue.firstOrNull()
    // Presence keeps only the source recording live. A toast never enters the modal host.
    val currentPresenceCallback by rememberUpdatedState(onPresenceChanged)
    val lastPresence = remember { booleanArrayOf(false) }
    val present = enabled && message != null
    SideEffect {
        if (lastPresence[0] != present) {
            lastPresence[0] = present
            currentPresenceCallback(present)
        }
    }
    DisposableEffect(owner) {
        onDispose {
            lastPresence[0] = false
            currentPresenceCallback(false)
        }
    }
    val accessibility = LocalAccessibilityManager.current
    val opacity = remember(message?.id) { Animatable(0f) }
    LaunchedEffect(message) {
        if (message != null) {
            val timeout = accessibility?.calculateRecommendedTimeoutMillis(
                toastDurationMillis(message.duration), containsIcons = message.icon != null,
                containsText = true, containsControls = false,
            ) ?: toastDurationMillis(message.duration)
            opacity.animateTo(1f, tween(150))
            delay(timeout)
            // Retain the message and live backdrop until its whole surface finishes fading out.
            opacity.animateTo(0f, tween(150))
            if (queue.firstOrNull() === message) queue.removeAt(0)
        }
    }
    LaunchedEffect(enabled) { if (!enabled) queue.clear() }
    var hostBottom by remember { mutableStateOf<Float?>(null) }
    val top = BottomNavigationToastPlacement.topInWindow()
    val clearance = with(LocalDensity.current) {
        if (top != null && hostBottom != null) (requireNotNull(hostBottom) - top).coerceAtLeast(0f).toDp()
        else bottomInset.coerceAtLeast(0.dp)
    }
    Box(modifier.fillMaxSize().onGloballyPositioned { hostBottom = it.boundsInWindow().bottom }, contentAlignment = Alignment.BottomCenter) {
        if (enabled && message != null) {
            val shape = uiRoundnessShape()
            val material = settings.usesGlassMaterial(GlassArea.Menus)
            val surface = if (material) effectiveSurfaceColor(surfaceBackdropColor(),
                glassFilmColor(settings.forArea(GlassArea.Menus), Color(message.background), menu = false)) else null
            val originalInk = Color(message.foreground)
            val darkness = if (material) glassSurfaceDarkness(settings) else 0f
            val textColor = surface?.let { glassPaneForeground(originalInk, it, TextContrast, darkness) } ?: originalInk
            val iconColor = surface?.let { glassPaneForeground(originalInk, it, IconContrast, darkness, glyph = true) }
                ?: originalInk
            Row(
                Modifier.padding(start = 24.dp, end = 24.dp, bottom = clearance + 8.dp)
                    .widthIn(max = 480.dp)
                    .testTag("glassToast")
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    .graphicsLayer { alpha = opacity.value }
                    .then(if (settings.usesGlassMaterial(GlassArea.Menus)) {
                        Modifier.glassMaterial(settings.forArea(GlassArea.Menus), shape,
                            ownBackdrop = backdrop, filmColor = Color(message.background))
                    } else {
                        Modifier.background(Color(message.background), shape)
                            .glassAreaContour(settings, GlassArea.Menus, shape)
                    })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val drawable = remember(message) { message.icon?.constantState?.newDrawable()?.mutate() ?: message.icon }
                if (drawable != null) Canvas(Modifier.size(24.dp)) {
                    drawIntoCanvas { canvas ->
                        drawToastIcon(drawable, canvas.nativeCanvas, size.width.toInt(), size.height.toInt(), iconColor.toArgb())
                    }
                }
                Text(message.text, color = textColor, fontSize = 14.sp)
            }
        }
    }
}

/** A Drawable without ConstantState remains caller-owned, including when drawing fails. */
internal fun drawToastIcon(drawable: Drawable, canvas: AndroidCanvas, width: Int, height: Int, foreground: Int) {
    val bounds = Rect(drawable.bounds)
    val filter = drawable.colorFilter
    try {
        drawable.setBounds(0, 0, width, height)
        drawable.colorFilter = PorterDuffColorFilter(foreground, PorterDuff.Mode.SRC_IN)
        drawable.draw(canvas)
    } finally {
        drawable.bounds = bounds
        drawable.colorFilter = filter
    }
}
