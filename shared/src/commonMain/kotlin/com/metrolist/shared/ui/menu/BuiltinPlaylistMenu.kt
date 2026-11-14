@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.ui.components.ModalBottomSheet
import com.metrolist.shared.ui.components.localizedBuiltinPlaylist
import com.metrolist.shared.ui.components.rememberModalSheetState
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource

@Composable
fun SpeedDialPinMenuItem(
    playlist: PlaylistItem,
    database: MusicDatabase,
    onDismiss: () -> Unit,
) {
    val pin = rememberSpeedDialToggle(playlist, database)
    val action = pinAction(pin.isPinned, onDismiss, pin.toggle) ?: return
    DropdownMenuItem(
        text = { Text(action.title) },
        leadingIcon = { Icon(action.icon, null) },
        onClick = action.onClick,
    )
}

@Composable
fun BuiltinPlaylistMenuButton(
    builtin: BuiltinPlaylist,
    database: MusicDatabase,
) {
    var expanded by remember { mutableStateOf(false) }
    val playlist = builtin.playlist(stringResource(builtin.title))
    Box {
        IconButton(
            modifier = Modifier.tvFocusHighlight(CircleShape).navigationPopupSource(),
            onClick = { expanded = true },
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.more_options))
        }
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                SpeedDialPinMenuItem(playlist, database) { expanded = false }
            }
        }
    }
}

@Composable
fun BuiltinPlaylistMenu(
    playlist: PlaylistItem,
    database: MusicDatabase,
    onDismiss: () -> Unit,
) {
    val playlist = localizedBuiltinPlaylist(playlist)
    val pin = rememberSpeedDialToggle(playlist, database)
    val model =
        playlistMediaMenuModel(
            playlist = playlist,
            onDismiss = onDismiss,
            isPinned = pin.isPinned,
            onTogglePin = pin.toggle,
        )
    ModalBottomSheet(sheetState = rememberModalSheetState(), onDismissRequest = onDismiss) {
        MediaMenuSheetContent(model)
    }
}
