/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.components

import android.os.Build
import android.view.ViewTreeObserver
import android.view.WindowInsets.Type
import android.view.WindowInsetsController
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import com.metrolist.android.extensions.findActivity

internal fun shouldUseDesktopCaption(
    sdkInt: Int,
    captionHeight: Int,
    hasPointerDevice: Boolean,
): Boolean = sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM && captionHeight > 0 && hasPointerDevice

@Composable
internal fun DesktopCaptionBar(
    enabled: Boolean,
    darkTheme: Boolean,
    color: Color,
    content: @Composable RowScope.() -> Unit,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || !enabled) return
    val view = LocalView.current
    val window = LocalContext.current.findActivity()?.window ?: return
    val density = LocalDensity.current
    val height = WindowInsets.captionBar.getTop(density)
    if (height <= 0) return

    DisposableEffect(window, darkTheme) {
        val controller = window.insetsController
        val mask =
            WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or
                WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
        val previous = controller?.systemBarsAppearance ?: 0
        val appearance =
            WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or
                if (darkTheme) 0 else WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
        controller?.setSystemBarsAppearance(appearance, mask)
        onDispose { controller?.setSystemBarsAppearance(previous, mask) }
    }

    fun occupiedBounds() =
        view.rootWindowInsets
            ?.getBoundingRects(Type.captionBar())
            .orEmpty()
            .filter { it.top < height && it.bottom > 0 }
            .map { it.left until it.right }

    var occupied by remember(view, height) { mutableStateOf(occupiedBounds()) }
    DisposableEffect(view, height) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnGlobalLayoutListener { occupied = occupiedBounds() }
        observer.addOnGlobalLayoutListener(listener)
        onDispose { if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener) }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().height(with(density) { height.toDp() }).background(color)) {
        val area = captionContentArea(constraints.maxWidth, occupied)
        if (area.isEmpty()) return@BoxWithConstraints
        Row(
            modifier =
                Modifier
                    .absoluteOffset { IntOffset(area.first, 0) }
                    .width(with(density) { (area.last - area.first + 1).coerceAtLeast(0).toDp() })
                    .height(with(density) { height.toDp() })
                    .clipToBounds(),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

internal fun captionContentArea(
    width: Int,
    occupied: List<IntRange>,
): IntRange {
    var cursor = 0
    var best = IntRange.EMPTY
    for (range in occupied.filterNot { it.isEmpty() }.sortedBy { it.first }) {
        val start = range.first.coerceIn(0, width)
        if (start - cursor > best.last - best.first + 1) best = cursor until start
        cursor = maxOf(cursor, (range.last.toLong() + 1).coerceIn(0, width.toLong()).toInt())
    }
    if (width - cursor > best.last - best.first + 1) best = cursor until width
    return best
}
