@file:OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.metrolist.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.android.ui.utils.playSong
import com.metrolist.shared.constants.GridCaptionSafeSpace
import com.metrolist.shared.constants.ThumbnailCornerRadius
import com.metrolist.shared.constants.currentGridThumbnailHeight
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.artist
import com.metrolist.shared.generated.resources.browse
import com.metrolist.shared.generated.resources.episode
import com.metrolist.shared.generated.resources.explicit
import com.metrolist.shared.generated.resources.filter_downloaded
import com.metrolist.shared.generated.resources.playlist
import com.metrolist.shared.generated.resources.podcast
import com.metrolist.shared.generated.resources.saved_to_library
import com.metrolist.shared.generated.resources.song_liked
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.model.thumbnailAspectRatio
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.settings.ArtistNameAliases
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.settings.displayTitle
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.AlbumPlayButton
import com.metrolist.shared.ui.components.AutoPlaylistThumbnail
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.components.EpisodePlayedIndicator
import com.metrolist.shared.ui.components.PlaylistCoverImage
import com.metrolist.shared.ui.components.SongThumbnailPlayOverlay
import com.metrolist.shared.ui.components.focusedItemBorder
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.isAutoPlaylistIcon
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.icons.SongStatusIcons
import com.metrolist.shared.ui.menu.artworkMenuActions
import com.metrolist.shared.ui.theme.gridCellInset
import com.metrolist.shared.ui.theme.gridCellShape
import com.metrolist.shared.ui.theme.uiEnhanced
import com.metrolist.shared.ui.utils.artistArtworkShape
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.stringResource

fun handleItemClick(
    item: YTItem,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToPlaylist: (String, String?) -> Unit,
    onNavigateToPodcast: (String) -> Unit,
    playerConnection: PlayerConnection?,
    scope: CoroutineScope,
    onNavigateToBrowse: (String, String?, String) -> Unit = { _, _, _ -> },
    songs: List<SongItem> = emptyList(),
    startVideo: Boolean = false,
) {
    when (item) {
        is SongItem -> {
            playSong(item, playerConnection, scope, songs, startVideo = startVideo)
        }

        is AlbumItem -> {
            onNavigateToAlbum(item.id)
        }

        is ArtistItem -> {
            CollectionPreviews.record(item)
            onNavigateToArtist(item.id)
        }

        is PlaylistItem -> {
            onNavigateToPlaylist(item.id, item.thumbnail)
        }

        is PodcastItem -> {
            onNavigateToPodcast(item.id)
        }

        is EpisodeItem -> {
            playSong(item.toSongItem(), playerConnection, scope, songs)
        }

        is BrowseItem -> {
            onNavigateToBrowse(item.id, item.params, item.title)
        }
    }
}

@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = SongStatusIcons.Explicit,
        contentDescription = stringResource(Res.string.explicit),
        tint = Color.Unspecified,
        modifier = modifier.offset(y = 1.dp).size(10.dp),
    )
}

@Composable
fun SongMetadataRow(
    song: SongItem,
    modifier: Modifier = Modifier,
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall,
    isDownloaded: Boolean = false,
) {
    MetadataRow(
        text =
            buildString {
                append(song.artists.joinToString { it.displayName })
                song.duration?.let { duration ->
                    if (isNotEmpty()) append(" • ")
                    append("${duration / 60}:${(duration % 60).toString().padStart(2, '0')}")
                }
            },
        modifier = modifier,
        textStyle = textStyle,
        statusBadges = { SongStatusBadges(song, isDownloaded) },
    )
}

@Composable
private fun ItemMetadataRow(
    item: YTItem,
    text: String,
    modifier: Modifier = Modifier,
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall,
) {
    if (item is SongItem) {
        SongMetadataRow(item, modifier = modifier, textStyle = textStyle)
    } else {
        MetadataRow(
            text = text,
            modifier = modifier,
            textStyle = textStyle,
            statusBadges = { ItemStatusBadges(item) },
        )
    }
}

@Composable
private fun MetadataRow(
    text: String,
    modifier: Modifier = Modifier,
    textStyle: androidx.compose.ui.text.TextStyle,
    statusBadges: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        statusBadges()
        Text(
            text = text,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ItemStatusBadges(item: YTItem) {
    when (item) {
        is SongItem -> SongStatusBadges(item)
        is AlbumItem -> if (item.isExplicit) ExplicitBadge()
        else -> Unit
    }
}

@Composable
private fun SongStatusBadges(
    song: SongItem,
    isDownloaded: Boolean = false,
) {
    val isLiked = SongLikeState.isLiked(song.id)
    if (!isLiked && song.libraryRemoveToken == null && !isDownloaded && !song.isExplicit) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLiked) {
            Icon(
                imageVector = SongStatusIcons.Liked,
                contentDescription = stringResource(Res.string.song_liked),
                tint = Color.Unspecified,
                modifier = Modifier.offset(y = 1.dp).size(11.dp, 10.dp),
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
                contentDescription = stringResource(Res.string.filter_downloaded),
                tint = Color.Unspecified,
                modifier = Modifier.offset(y = 1.dp).size(11.dp),
            )
        }
        if (song.isExplicit) {
            ExplicitBadge()
        }
    }
}

@Composable
fun YtGridItem(
    item: YTItem,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    imageSize: Dp = currentGridThumbnailHeight(),
    imageShape: Shape? = null,
    contentPadding: Dp = 0.dp,
    subtitleOverride: String? = null,
    fillWidth: Boolean = false,
    useInternalClip: Boolean = true,
    onShowMenu: ((SongItem) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    onAlbumPlay: (() -> Unit)? = null,
) {
    val onMenuClick = (item as? SongItem)?.let { song -> onShowMenu?.let { show -> { show(song) } } } ?: onLongClick
    val inset = gridCellInset()
    val artworkSize = (imageSize - inset * 2).coerceAtLeast(0.dp)
    val shape =
        imageShape
            ?: when (item) {
                is ArtistItem -> artistArtworkShape(item.id)
                is PodcastItem -> CircleShape
                else -> RoundedCornerShape(uiEnhanced(ThumbnailCornerRadius, 4.dp))
            }

    Column(
        modifier =
            modifier
                .clip(gridCellShape())
                .focusedItemBorder(gridCellShape())
                .artworkMenuActions(onMenuClick)
                .then(
                    if (onClick != null || onMenuClick != null) {
                        Modifier.combinedClickable(
                            onClick = { if (!item.isUnavailable) onClick?.invoke() },
                            onLongClick =
                                onLongClick ?: onMenuClick,
                        )
                    } else {
                        Modifier
                    },
                ).padding(inset)
                .padding(contentPadding),
    ) {
        Box(
            modifier = if (fillWidth) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.size(artworkSize),
            contentAlignment = Alignment.Center,
        ) {
            Box {
                if (item is PlaylistItem) {
                    val base = if (fillWidth) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.size(artworkSize)
                    val imageModifier = if (useInternalClip) base.clipToBounds().clip(shape) else base.clipToBounds()
                    PlaylistCoverImage(
                        playlist = item,
                        modifier = imageModifier.shadow(8.dp, shape, clip = false),
                    )
                } else if (item.thumbnail.isAutoPlaylistIcon()) {
                    val base = if (fillWidth) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.size(artworkSize)
                    AutoPlaylistThumbnail(
                        iconIdentifier = item.thumbnail,
                        modifier = base.shadow(8.dp, shape),
                        shape = shape,
                    )
                } else if (item.thumbnail != null) {
                    val aspectRatio = item.thumbnailAspectRatio
                    val base =
                        if (fillWidth) {
                            Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                        } else {
                            Modifier.width(artworkSize).aspectRatio(aspectRatio)
                        }
                    val imageModifier = if (useInternalClip) base.clipToBounds().clip(shape) else base.clipToBounds()

                    AsyncImage(
                        url = item.displayThumbnailUrl,
                        contentDescription = null,
                        modifier = imageModifier.shadow(8.dp, shape, clip = false),
                        contentScale = ContentScale.Crop,
                    )
                }

                if (item is SongItem && !Settings.shared.uiEnhancements) {
                    SongThumbnailPlayOverlay(
                        isActive = isCurrent,
                        isPlaying = isPlaying,
                        shape = shape,
                    )
                }
                if (item is AlbumItem && onAlbumPlay != null) {
                    AlbumPlayButton(
                        visible = !isCurrent,
                        onClick = onAlbumPlay,
                    )
                }
            }
            if (item is EpisodeItem) {
                EpisodePlayedIndicator(
                    item.id,
                    item.duration,
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f))
                        .padding(4.dp),
                    serverProgressPercent = item.playbackProgressPercent,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        val subtitle =
            subtitleOverride
                ?: when (item) {
                    is SongItem -> item.artists.joinToString { it.displayName }
                    is AlbumItem -> item.artists.joinToString { it.displayName }
                    is ArtistItem -> stringResource(Res.string.artist)
                    is PlaylistItem -> item.author?.let { ArtistNameAliases.resolve(null, it) } ?: stringResource(Res.string.playlist)
                    is PodcastItem -> stringResource(Res.string.podcast)
                    is EpisodeItem -> stringResource(Res.string.episode)
                    is BrowseItem -> item.subtitle ?: stringResource(Res.string.browse)
                }

        if (subtitle.isNotEmpty()) {
            ItemMetadataRow(
                item = item,
                text = subtitle,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(GridCaptionSafeSpace))
    }
}
