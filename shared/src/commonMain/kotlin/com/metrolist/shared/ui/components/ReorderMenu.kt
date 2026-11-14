@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.move_down
import com.metrolist.shared.generated.resources.move_to_bottom
import com.metrolist.shared.generated.resources.move_to_top
import com.metrolist.shared.generated.resources.move_up
import com.metrolist.shared.generated.resources.playlist_reorder
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ReorderMenu(
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    onMoveToTop: (() -> Unit)? = null,
    onMoveToBottom: (() -> Unit)? = null,
    onArtwork: Boolean = false,
    expandedState: MutableState<Boolean> = remember { mutableStateOf(false) },
) {
    var expanded by expandedState
    Box(modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.tvFocusHighlight(CircleShape).then(dragHandleModifier),
            shapes = IconButtonDefaults.shapes(),
            colors =
                if (onArtwork) {
                    IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    IconButtonDefaults.iconButtonColors()
                },
        ) {
            Icon(
                MetrolistIcons.Outlined.DragIndicator,
                stringResource(Res.string.playlist_reorder),
            )
        }
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.move_to_top)) },
                    modifier = Modifier.tvFocusHighlight(),
                    enabled = onMoveToTop != null,
                    onClick = {
                        expanded = false
                        onMoveToTop?.invoke()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.move_up)) },
                    modifier = Modifier.tvFocusHighlight(),
                    enabled = onMoveUp != null,
                    onClick = {
                        expanded = false
                        onMoveUp?.invoke()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.move_down)) },
                    modifier = Modifier.tvFocusHighlight(),
                    enabled = onMoveDown != null,
                    onClick = {
                        expanded = false
                        onMoveDown?.invoke()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.move_to_bottom)) },
                    modifier = Modifier.tvFocusHighlight(),
                    enabled = onMoveToBottom != null,
                    onClick = {
                        expanded = false
                        onMoveToBottom?.invoke()
                    },
                )
            }
        }
    }
}
