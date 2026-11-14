package com.metrolist.shared.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.painterResource

const val AUTO_PLAYLIST_ICON_PREFIX = "auto_playlist_icon_"
const val AUTO_PLAYLIST_ICON_LIKED = "auto_playlist_icon_liked"
const val AUTO_PLAYLIST_ICON_DOWNLOADED = "auto_playlist_icon_downloaded"
const val AUTO_PLAYLIST_ICON_TOP100 = "auto_playlist_icon_top100"
const val AUTO_PLAYLIST_ICON_UPLOADED = "auto_playlist_icon_uploaded"
const val AUTO_PLAYLIST_ICON_CACHED = "auto_playlist_icon_cached"

fun String?.isAutoPlaylistIcon(): Boolean = this?.startsWith(AUTO_PLAYLIST_ICON_PREFIX) == true

@Composable
fun AutoPlaylistThumbnail(
    iconIdentifier: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
) {
    libraryCover(iconIdentifier)?.let { cover ->
        Image(painterResource(cover), null, modifier.clip(shape), contentScale = ContentScale.Crop)
        return
    }
    val (icon, iconTint, iconBackground) = autoPlaylistIconStyle(iconIdentifier)
    BoxWithConstraints(
        modifier =
            modifier
                .clip(shape)
                .background(iconBackground),
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = minOf(maxWidth, maxHeight) * 0.4f
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun autoPlaylistIconStyle(iconIdentifier: String?): Triple<ImageVector, Color, Color> {
    val colorScheme = MaterialTheme.colorScheme
    return when (iconIdentifier) {
        AUTO_PLAYLIST_ICON_LIKED -> {
            Triple(
                MetrolistIcons.Outlined.FavoriteBorder,
                colorScheme.onSecondaryContainer,
                colorScheme.secondaryContainer,
            )
        }

        "auto_playlist_icon_history" -> {
            Triple(MetrolistIcons.Outlined.History, colorScheme.onSurfaceVariant, colorScheme.surfaceVariant)
        }

        "auto_playlist_icon_podcasts" -> {
            Triple(MetrolistIcons.Outlined.Podcasts, colorScheme.onTertiaryContainer, colorScheme.tertiaryContainer)
        }

        AUTO_PLAYLIST_ICON_DOWNLOADED -> {
            Triple(
                MetrolistIcons.Outlined.Download,
                colorScheme.onSecondaryContainer,
                colorScheme.secondaryContainer,
            )
        }

        AUTO_PLAYLIST_ICON_TOP100 -> {
            Triple(
                MetrolistIcons.AutoMirrored.Outlined.TrendingUp,
                colorScheme.onPrimaryContainer,
                colorScheme.primaryContainer,
            )
        }

        AUTO_PLAYLIST_ICON_UPLOADED -> {
            Triple(
                MetrolistIcons.Outlined.Backup,
                colorScheme.onTertiaryContainer,
                colorScheme.tertiaryContainer,
            )
        }

        AUTO_PLAYLIST_ICON_CACHED -> {
            Triple(
                MetrolistIcons.Outlined.Storage,
                colorScheme.onSurfaceVariant,
                colorScheme.surfaceVariant,
            )
        }

        else -> {
            Triple(
                MetrolistIcons.AutoMirrored.Outlined.QueueMusic,
                colorScheme.onSurfaceVariant,
                colorScheme.surfaceVariant,
            )
        }
    }
}
