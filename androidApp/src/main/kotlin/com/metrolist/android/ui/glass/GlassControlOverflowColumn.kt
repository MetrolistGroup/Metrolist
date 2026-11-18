package com.metrolist.android.ui.glass

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex

/**
 * A list header is one drawing owner even when its controls sit in nested rows.
 * Raise that owner above ordinary/reorderable rows while a control's spring is active.
 * The surrounding lazy-list viewport still clips and scrolls normally.
 */
@Composable
fun GlassControlOverflowColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val parent = LocalGlassGroupElevation.current
    val elevation = remember(parent) { GlassGroupElevation(parent) }
    CompositionLocalProvider(LocalGlassGroupElevation provides elevation) {
        Column(modifier.zIndex(if (elevation.raised) 3f else 0f), content = content)
    }
}
