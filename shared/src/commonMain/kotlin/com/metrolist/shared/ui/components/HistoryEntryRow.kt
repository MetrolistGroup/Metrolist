@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.edit
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.onSecondaryClick
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.theme.ComponentShapes
import org.jetbrains.compose.resources.stringResource

enum class HistoryEntryGroupPosition {
    SINGLE,
    FIRST,
    MIDDLE,
    LAST,
}

@Composable
fun HistoryEntryRow(
    title: String,
    artists: String,
    album: String?,
    thumbnailUrl: String?,
    relativeTimeText: String?,
    isSelected: Boolean,
    isSelectMode: Boolean,
    onPlay: () -> Unit,
    onToggleSelection: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onSongMenu: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    groupPosition: HistoryEntryGroupPosition = HistoryEntryGroupPosition.SINGLE,
    showInlineMenu: Boolean = true,
    editText: String = stringResource(Res.string.edit),
    deleteText: String = stringResource(Res.string.delete),
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val shape =
        when (groupPosition) {
            HistoryEntryGroupPosition.SINGLE -> {
                ComponentShapes.MenuGroupShape
            }

            HistoryEntryGroupPosition.FIRST -> {
                RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = 5.dp,
                    bottomEnd = 5.dp,
                )
            }

            HistoryEntryGroupPosition.MIDDLE -> {
                RoundedCornerShape(5.dp)
            }

            HistoryEntryGroupPosition.LAST -> {
                RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
            }
        }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
                .clip(shape)
                .combinedClickable(
                    onClick = { if (isSelectMode) onToggleSelection() else onPlay() },
                    onLongClick = onLongClick ?: onSongMenu,
                ).onSecondaryClick(onSongMenu)
                .semantics {
                    if (isSelectMode) this.selected = isSelected
                },
        shape = shape,
        color =
            when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                isSelectMode -> MaterialTheme.colorScheme.surfaceContainerLow
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                url = thumbnailUrl ?: "",
                modifier = Modifier.size(56.dp).clip(ComponentShapes.SmallThumbnailShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${artists}${if (album != null) " ·\u00a0$album" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (relativeTimeText != null) {
                    Text(
                        relativeTimeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isSelectMode) {
                Spacer(Modifier.width(8.dp))
                Checkbox(checked = isSelected, onCheckedChange = null)
            }
            Spacer(Modifier.width(4.dp))
            if (showInlineMenu && !isSelectMode) {
                Box {
                    IconButton(
                        modifier = Modifier.navigationPopupSource(),
                        onClick = { menuExpanded = true },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            MetrolistIcons.Outlined.MoreVert,
                            contentDescription = stringResource(Res.string.more_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenuPopup(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                            if (onEdit != null) {
                                DropdownMenuItem(
                                    text = { Text(editText) },
                                    onClick = {
                                        menuExpanded = false
                                        onEdit()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            MetrolistIcons.Outlined.Edit,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(deleteText) },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(
                                        MetrolistIcons.Outlined.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        }
                    }
                }
            }
            onSongMenu?.takeUnless { isSelectMode }?.let { open ->
                IconButton(modifier = Modifier.navigationPopupSource(), onClick = open, shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.more_options))
                }
            }
        }
    }
}
