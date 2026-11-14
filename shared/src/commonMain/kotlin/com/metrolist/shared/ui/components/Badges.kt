package com.metrolist.shared.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.downloaded
import com.metrolist.shared.generated.resources.explicit
import com.metrolist.shared.generated.resources.liked
import com.metrolist.shared.generated.resources.live
import com.metrolist.shared.generated.resources.new_badge
import com.metrolist.shared.generated.resources.pinned
import com.metrolist.shared.generated.resources.saved_to_library
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.icons.SongStatusIcons
import org.jetbrains.compose.resources.stringResource

val LocalDownloadedSongIds = compositionLocalOf<Set<String>> { emptySet() }

@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = SongStatusIcons.Explicit,
        contentDescription = stringResource(Res.string.explicit),
        tint = Color.Unspecified,
        modifier = modifier.offset(y = 1.dp).size(13.dp),
    )
}

@Composable
fun ItemStatusBadges(
    item: YTItem,
    isDownloaded: Boolean = false,
    showExplicit: Boolean = true,
) {
    val songId = (item as? SongItem)?.id
    val downloaded = isDownloaded || (songId != null && songId in LocalDownloadedSongIds.current)
    when (item) {
        is SongItem -> {
            SongStatusBadges(item, downloaded, showExplicit)
        }

        is AlbumItem -> {
            if (item.isNew || item.isExplicit) {
                Row(
                    modifier = Modifier.padding(end = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.isNew) NewBadge()
                    if (item.isExplicit) ExplicitBadge()
                }
            }
        }

        else -> {}
    }
}

@Composable
fun LiveBadge(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    color: Color = MaterialTheme.colorScheme.error,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "LiveBadgePulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "LiveDotAlpha",
    )

    val dotSize = if (compact) 4.5.dp else 6.dp
    val spacing = if (compact) 3.dp else 4.dp
    val fontSize = if (compact) 9.sp else 11.sp

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Box(
            modifier =
                Modifier
                    .size(dotSize)
                    .background(color.copy(alpha = dotAlpha), CircleShape),
        )
        Text(
            text = stringResource(Res.string.live),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    letterSpacing = if (compact) 0.3.sp else 0.5.sp,
                ),
        )
    }
}

@Composable
fun NewBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(2.dp),
        modifier = modifier.height(14.dp),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(Res.string.new_badge),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
            )
        }
    }
}

@Composable
fun SongStatusBadges(
    song: SongItem,
    isDownloaded: Boolean = false,
    showExplicit: Boolean = true,
) {
    val isLiked = SongLikeState.isLiked(song.id)
    if (!isLiked && song.libraryRemoveToken == null && !isDownloaded && !(showExplicit && song.isExplicit) && !song.isNew &&
        !song.isLive
    ) {
        return
    }

    Row(
        modifier = Modifier.padding(end = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (song.isLive) LiveBadge(compact = true)
        if (song.isNew) NewBadge()
        if (isLiked) {
            Icon(
                imageVector = SongStatusIcons.Liked,
                contentDescription = stringResource(Res.string.liked),
                tint = Color.Unspecified,
                modifier = Modifier.offset(y = 1.dp).size(13.dp),
            )
        }
        if (song.libraryRemoveToken != null) {
            Icon(
                imageVector = SongStatusIcons.Saved,
                contentDescription = stringResource(Res.string.saved_to_library),
                tint = Color.Unspecified,
                modifier = Modifier.offset(y = 1.dp).size(10.dp),
            )
        }
        if (isDownloaded) {
            Icon(
                imageVector = SongStatusIcons.Downloaded,
                contentDescription = stringResource(Res.string.downloaded),
                tint = Color.Unspecified,
                modifier = Modifier.offset(y = 1.dp).size(11.dp),
            )
        }
        if (showExplicit && song.isExplicit) {
            ExplicitBadge()
        }
    }
}

@Composable
fun SpeedDialPinBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(24.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.primary,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                MetrolistIcons.Filled.PushPin,
                contentDescription = stringResource(Res.string.pinned),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
