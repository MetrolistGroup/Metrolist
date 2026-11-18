package com.metrolist.android.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import com.metrolist.android.ui.theme.uiRoundnessShape

/** Clip the title's own indication without clipping a connected trailing action's drawing. */
@Composable
fun GlassTitleActionRow(
    modifier: Modifier,
    verticalPadding: Dp,
    hasTrailingActions: Boolean,
    onClick: (() -> Unit)?,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = verticalPadding),
    content: @Composable RowScope.() -> Unit,
) {
    val shape = uiRoundnessShape()
    val overflow = hasTrailingActions && rememberGlassSettings().usesGlassMaterial(GlassArea.ActionButtons)
    if (!overflow) {
        Row(modifier.clip(shape).clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically, content = content)
        return
    }
    val parentElevation = LocalGlassGroupElevation.current
    val elevation = remember(parentElevation) { GlassGroupElevation(parentElevation) }
    Box(modifier.zIndex(if (elevation.raised) 1f else 0f).semantics(mergeDescendants = true) {
        if (onClick != null) semanticsOnClick { onClick(); true }
    }) {
        // This empty sibling owns only the title action and its bounded indication.
        // Child controls retain their own recognizers; their material can overdraw.
        if (onClick != null) Box(Modifier.matchParentSize().clearAndSetSemantics { }.clip(shape).clickable(onClick = onClick))
        CompositionLocalProvider(LocalGlassGroupElevation provides elevation) {
            Row(Modifier.fillMaxWidth().padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}
