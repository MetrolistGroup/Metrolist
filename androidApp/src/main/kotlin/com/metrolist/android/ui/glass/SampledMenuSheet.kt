package com.metrolist.android.ui.glass

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.theme.topUiRoundnessShape

/**
 * Shared-window host for menus and short forms whose List/Grid content owns its material.
 * [maxHeightFraction] caps the measured surface, including when dragged to its expanded stop.
 * [partiallyExpanded] must match whether [sheetState] allows a partially expanded stop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampledMenuSheet(
    showSheet: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    partiallyExpanded: Boolean = false,
    maxHeightFraction: Float? = null,
    shape: Shape = topUiRoundnessShape(),
    containerColor: Color = Color.Transparent,
    dragHandle: @Composable (() -> Unit)? = null,
    contentWindowInsets: @Composable () -> WindowInsets = { WindowInsets.ime },
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxSheetHeight = maxHeightFraction?.let {
        (LocalConfiguration.current.screenHeightDp * it.coerceIn(0f, 1f)).dp
    } ?: Dp.Unspecified
    val settings = rememberGlassSettings()
    val host = LocalGlassMenuHost.current
    val sameWindow = host?.dialogs?.windowRoot === LocalView.current.rootView
    if (containerColor == Color.Transparent && canHostGlassMenuSheet(
            settings, glassRenderer(settings), sameWindow && host?.backdrop != null,
        )) {
        SharedGlassMenuSheet(
            showSheet = showSheet,
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            partiallyExpanded = partiallyExpanded,
            maxSheetHeight = maxSheetHeight,
            modifier = modifier,
            shape = shape,
            dragHandle = dragHandle,
            contentWindowInsets = contentWindowInsets,
            content = content,
        )
    } else {
        CustomModalBottomSheet(
            showSheet = showSheet,
            onDismissRequest = onDismissRequest,
            modifier = if (maxSheetHeight.isSpecified) modifier.heightIn(max = maxSheetHeight) else modifier,
            sheetState = sheetState,
            shape = shape,
            containerColor = containerColor,
            dragHandle = dragHandle,
            contentWindowInsets = contentWindowInsets,
            content = content,
        )
    }
}
