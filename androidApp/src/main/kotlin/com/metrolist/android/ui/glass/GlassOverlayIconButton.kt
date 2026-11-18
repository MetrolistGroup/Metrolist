package com.metrolist.android.ui.glass

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import com.metrolist.android.ui.component.nativeControlIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.theme.uiRoundnessShape

/** Overlay actions keep their existing slot and glyph sizes instead of adopting a toolbar layout. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassOverlayIconButton(
    onClick: () -> Unit,
    icon: Int,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = 24.dp,
    contentPadding: Dp = 0.dp,
    contentDescription: String? = null,
    settings: GlassSettings = rememberGlassSettings(),
) {
    // Keep the original row seat while centering a comfortable 24dp glyph inside a 40dp pane.
    // Visualizer's 50dp row leaves 5dp above/below this pane; a 32dp seat needs only 4dp overdraw.
    Box(modifier.size(iconSize + contentPadding * 2), contentAlignment = Alignment.Center) {
        val pane = Modifier.requiredSize(40.dp)
        val material = GlassFloatingActionIcon(
                painterResource(icon), onClick, pane,
                enabled = enabled, size = 40.dp,
                iconSize = iconSize, tint = color, contentDescription = contentDescription, settings = settings,
                shape = uiRoundnessShape(),
            )
        if (!material) {
            val shape = uiRoundnessShape()
            Image(painterResource(icon), contentDescription, colorFilter = ColorFilter.tint(color),
                modifier = pane.glassAreaContour(settings, GlassArea.ActionButtons, shape).clip(shape)
                    .combinedClickable(interactionSource = remember { MutableInteractionSource() },
                        indication = glassGlobalIndication(nativeControlIndication()), enabled = enabled,
                        role = Role.Button, onClick = onClick)
                    .padding((40.dp - iconSize).coerceAtLeast(0.dp) / 2).size(iconSize))
        }
    }
}
