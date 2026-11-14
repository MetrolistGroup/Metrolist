@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import com.metrolist.shared.ui.navigation.LocalNavigationContainerState
import com.metrolist.shared.ui.theme.ComponentShapes
import androidx.compose.material3.DropdownMenuPopup as MaterialDropdownMenuPopup

internal class PopupMenuAnchorState {
    private var wasExpanded = false
    private var anchored = false

    fun update(
        expanded: Boolean,
        hasSource: Boolean,
    ): Boolean {
        if (expanded && !wasExpanded) anchored = hasSource
        wasExpanded = expanded
        return anchored
    }
}

@Composable
private fun useAnchoredPopup(expanded: Boolean): Boolean {
    val state = remember { PopupMenuAnchorState() }
    return state.update(expanded, LocalNavigationContainerState.current?.pendingPopup != null)
}

@Composable
fun FabMenuPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (useAnchoredPopup(expanded)) {
        PopupVisibility(expanded) {
            val positionProvider =
                remember {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset {
                            val x =
                                if (layoutDirection == LayoutDirection.Ltr) {
                                    anchorBounds.left
                                } else {
                                    anchorBounds.right - popupContentSize.width
                                }
                            val y =
                                if (anchorBounds.bottom + popupContentSize.height <= windowSize.height) {
                                    anchorBounds.bottom
                                } else {
                                    anchorBounds.top - popupContentSize.height
                                }
                            return IntOffset(
                                x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                                y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
                            )
                        }
                    }
                }
            Popup(positionProvider, onDismissRequest) {
                Surface(
                    modifier = Modifier.popupMotion(),
                    shape = ComponentShapes.MenuExteriorShape,
                    color = MenuDefaults.containerColor,
                    tonalElevation = MenuDefaults.TonalElevation,
                    shadowElevation = MenuDefaults.ShadowElevation,
                ) {
                    Column(
                        modifier =
                            Modifier
                                .widthIn(
                                    min = 200.dp,
                                    max = 400.dp,
                                ).width(IntrinsicSize.Max)
                                .heightIn(max = 720.dp)
                                .padding(8.dp),
                        content = content,
                    )
                }
            }
        }
    } else {
        MaterialDropdownMenuPopup(expanded, onDismissRequest, content = content)
    }
}
