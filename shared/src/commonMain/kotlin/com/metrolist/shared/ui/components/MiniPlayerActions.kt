@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.add_to_favorites
import com.metrolist.shared.generated.resources.add_to_playlist
import com.metrolist.shared.generated.resources.mini_player_subscribe_artist
import com.metrolist.shared.generated.resources.mini_player_unsubscribe_artist
import com.metrolist.shared.generated.resources.remove_from_favorites
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource

@Composable
fun MiniPlayerActions(
    isLiked: Boolean,
    isArtistSubscribed: Boolean,
    onAddToPlaylist: (() -> Unit)?,
    onLike: (() -> Unit)?,
    onSubscribeToggle: (() -> Unit)?,
    borderColor: Color,
    contentColor: Color,
) {
    val settings = Settings.shared
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (settings.showMiniPlayerPlaylistButton && onAddToPlaylist != null) {
            MiniPlayerActionButton(
                icon = MetrolistIcons.AutoMirrored.Outlined.PlaylistAdd,
                label = stringResource(Res.string.add_to_playlist),
                onClick = onAddToPlaylist,
                borderColor = borderColor,
                contentColor = contentColor,
            )
        }
        if (settings.showMiniPlayerSubscribeButton && onSubscribeToggle != null) {
            val activeColor = MaterialTheme.colorScheme.primary
            MiniPlayerActionButton(
                icon = if (isArtistSubscribed) MetrolistIcons.Filled.PersonRemove else MetrolistIcons.Filled.PersonAdd,
                label =
                    stringResource(
                        if (isArtistSubscribed) Res.string.mini_player_unsubscribe_artist else Res.string.mini_player_subscribe_artist,
                    ),
                onClick = onSubscribeToggle,
                borderColor = if (isArtistSubscribed) activeColor.copy(alpha = 0.5f) else borderColor,
                contentColor = if (isArtistSubscribed) activeColor else contentColor,
            )
        }
        if (settings.showMiniPlayerLikeButton && onLike != null) {
            MiniPlayerActionButton(
                icon = if (isLiked) MetrolistIcons.Filled.Favorite else MetrolistIcons.Outlined.FavoriteBorder,
                label = stringResource(if (isLiked) Res.string.remove_from_favorites else Res.string.add_to_favorites),
                onClick = onLike,
                borderColor = borderColor,
                contentColor = if (isLiked) MaterialTheme.colorScheme.error else contentColor,
            )
        }
    }
}

@Composable
private fun MiniPlayerActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    borderColor: Color,
    contentColor: Color,
) {
    OutlinedIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).tvFocusHighlight(CircleShape, scale = 1.08f, borderWidth = 4.dp),
        shapes = IconButtonDefaults.shapes(shape = CircleShape),
        border = BorderStroke(1.dp, borderColor),
        colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = contentColor),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
    }
}
