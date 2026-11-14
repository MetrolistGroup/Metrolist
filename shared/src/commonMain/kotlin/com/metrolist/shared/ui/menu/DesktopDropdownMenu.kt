package com.metrolist.shared.ui.menu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.DropdownMenuPopupPositionProvider
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import com.metrolist.shared.ui.navigation.LocalNavigationContainerState
import com.metrolist.shared.ui.navigation.blockInput
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import kotlin.math.max

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DesktopDropdownMenu(
    expanded: Boolean,
    offset: DpOffset,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sidePlacementWidth: Dp? = null,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val navigation = LocalNavigationContainerState.current
    val anchor = remember { DesktopMenuAnchor() }
    anchor.update(expanded, navigation?.pendingPopup?.windowBounds?.roundToIntRect())
    val cursor = with(density) { if (offset == DpOffset.Zero) null else IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
    val gap = with(density) { 8.dp.roundToPx() }
    val sideWidth = with(density) { sidePlacementWidth?.roundToPx() }
    val position = remember(cursor, anchor.bounds, gap, sideWidth) { DesktopMenuPositionProvider(cursor, anchor.bounds, gap, sideWidth) }
    val availableHeight = with(density) { position.availableHeight(windowSize).toDp() }
    val maxHeight = if (sideWidth == null) availableHeight.coerceAtMost(720.dp) else availableHeight

    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        popupPositionProvider = position,
    ) {
        Surface(
            shape = ComponentShapes.MenuExteriorShape,
            color = if (tonalElevation > 0.dp) MaterialTheme.colorScheme.surface else MenuDefaults.containerColor,
            tonalElevation = tonalElevation,
            shadowElevation = MenuDefaults.ShadowElevation,
        ) {
            Column(
                modifier =
                    modifier
                        .blockInput(!expanded)
                        .widthIn(min = 200.dp, max = 400.dp)
                        .heightIn(max = maxHeight)
                        .verticalScrollWithDrag(rememberScrollState())
                        .padding(vertical = 8.dp),
                content = content,
            )
        }
    }
}

internal class DesktopMenuAnchor {
    private var wasExpanded = false
    var bounds: IntRect? = null
        private set

    fun update(
        expanded: Boolean,
        source: IntRect?,
    ) {
        if (expanded && !wasExpanded) bounds = source
        wasExpanded = expanded
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal class DesktopMenuPositionProvider(
    private val cursor: IntOffset?,
    private val buttonBounds: IntRect?,
    private val gap: Int,
    private val sidePlacementWidth: Int? = null,
) : DropdownMenuPopupPositionProvider {
    override var transformOrigin by mutableStateOf(TransformOrigin.Center)
        private set

    fun availableHeight(windowSize: IntSize): Int =
        if (cursor == null && buttonBounds != null && !fitsBesideButton(windowSize, sidePlacementWidth)) {
            max(buttonBounds.top - gap, windowSize.height - buttonBounds.bottom - gap).coerceIn(0, windowSize.height)
        } else {
            windowSize.height
        }

    private fun fitsBesideButton(
        windowSize: IntSize,
        width: Int?,
    ): Boolean =
        cursor == null && buttonBounds != null && width != null &&
            (buttonBounds.right + gap + width <= windowSize.width || buttonBounds.left - gap - width >= 0)

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchor = buttonBounds ?: anchorBounds
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val besideButton = sidePlacementWidth != null && fitsBesideButton(windowSize, popupContentSize.width)
        val x =
            (
                if (cursor != null) {
                    if (cursor.x + popupContentSize.width <= windowSize.width) cursor.x else cursor.x - popupContentSize.width
                } else if (besideButton) {
                    val right = anchor.right + gap
                    val left = anchor.left - gap - popupContentSize.width
                    if (layoutDirection == LayoutDirection.Ltr) {
                        if (right + popupContentSize.width <= windowSize.width) right else left
                    } else {
                        if (left >= 0) left else right
                    }
                } else if (layoutDirection == LayoutDirection.Ltr) {
                    anchor.right - popupContentSize.width
                } else {
                    anchor.left
                }
            ).coerceIn(0, maxX)
        val y =
            (
                if (cursor != null) {
                    if (cursor.y + popupContentSize.height <= windowSize.height) cursor.y else cursor.y - popupContentSize.height
                } else if (besideButton) {
                    anchor.top
                } else if (anchor.bottom + gap + popupContentSize.height <= windowSize.height) {
                    anchor.bottom + gap
                } else {
                    anchor.top - gap - popupContentSize.height
                }
            ).coerceIn(0, maxY)
        val pivot = cursor ?: anchor.center
        transformOrigin =
            TransformOrigin(
                if (popupContentSize.width == 0) 0f else ((pivot.x - x).toFloat() / popupContentSize.width).coerceIn(0f, 1f),
                if (popupContentSize.height == 0) 0f else ((pivot.y - y).toFloat() / popupContentSize.height).coerceIn(0f, 1f),
            )
        return IntOffset(x, y)
    }
}
