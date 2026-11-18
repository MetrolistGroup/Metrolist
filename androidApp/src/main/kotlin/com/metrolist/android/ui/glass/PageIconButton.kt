package com.metrolist.android.ui.glass

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.component.UiIconButton
import com.metrolist.android.ui.theme.uiRoundnessShape

/** An inline icon action with the native icon button as its non-Glass fallback. */
@Composable
internal fun PageIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val settings = rememberGlassSettings()
    val shape = uiRoundnessShape()
    if (settings.usesGlassMaterial(GlassArea.ActionButtons)) {
        PageTextButton(
            onClick = onClick,
            modifier = modifier.size(48.dp),
            shape = shape,
            contentPadding = PaddingValues(0.dp),
            settings = settings,
        ) { content() }
    } else {
        UiIconButton(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
        ) {
            if (settings.usesGlassContour(GlassArea.ActionButtons)) {
                Box(Modifier.fillMaxSize().glassAreaContour(settings, GlassArea.ActionButtons, shape),
                    contentAlignment = Alignment.Center) {
                    content()
                }
            } else {
                content()
            }
        }
    }
}
