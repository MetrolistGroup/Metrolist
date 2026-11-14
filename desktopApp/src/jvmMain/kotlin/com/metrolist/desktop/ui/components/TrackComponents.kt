/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.LibraryState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.playback.DiscordRpcState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.storage.DownloadState
import com.metrolist.shared.constants.ListItemHeight
import com.metrolist.shared.constants.ListThumbnailSize
import com.metrolist.shared.constants.ThumbnailCornerRadius
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.artist
import com.metrolist.shared.generated.resources.browse
import com.metrolist.shared.generated.resources.episode
import com.metrolist.shared.generated.resources.explicit
import com.metrolist.shared.generated.resources.filter_downloaded
import com.metrolist.shared.generated.resources.more_options_for
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.playlist
import com.metrolist.shared.generated.resources.podcast
import com.metrolist.shared.generated.resources.queued
import com.metrolist.shared.generated.resources.song_liked
import com.metrolist.shared.generated.resources.song_saved_to_library_description
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistSource
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.previewAspectRatio
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.settings.ArtistNameAliases
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.settings.displayTitle
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.components.ListItemTitle
import com.metrolist.shared.ui.components.animateCurrentSongColors
import com.metrolist.shared.ui.components.animatedCurrentSongArtworkShape
import com.metrolist.shared.ui.components.currentSongSemantics
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.rememberCurrentSongArtworkRotation
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.icons.SongStatusIcons
import com.metrolist.shared.ui.menu.cursorContextMenu
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.utils.artistArtworkShape
import com.metrolist.shared.utils.formatDuration
import org.jetbrains.compose.resources.stringResource

fun Modifier.songThumbSize(
    song: SongItem?,
    squareSize: Dp,
): Modifier {
    val ratio = song?.previewAspectRatio ?: 1f
    return if (ratio > 1f) size(squareSize * ratio, squareSize) else size(squareSize)
}

@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = SongStatusIcons.Explicit,
        contentDescription = stringResource(Res.string.explicit),
        tint = Color.Unspecified,
        modifier = modifier.size(10.dp),
    )
}

@Composable
fun SongMetadataRow(
    song: SongItem,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
    contentColor: Color = colorScheme.onSurfaceVariant,
) {
    val effectiveSong = SongMetadataState.apply(song)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (PlayerState.isLiked(song.id)) {
            Icon(
                imageVector = SongStatusIcons.Liked,
                contentDescription = stringResource(Res.string.song_liked),
                tint = Color.Unspecified,
                modifier = Modifier.size(11.dp, 10.dp),
            )
        }
        if (song.libraryRemoveToken != null) {
            Icon(
                imageVector = SongStatusIcons.Saved,
                contentDescription = stringResource(Res.string.song_saved_to_library_description),
                tint = Color.Unspecified,
                modifier = Modifier.size(10.dp),
            )
        }
        Text(
            text =
                buildString {
                    append(effectiveSong.artists.joinToString { it.displayName })
                    effectiveSong.duration?.let {
                        if (isNotEmpty()) append(" ·\u00a0")
                        append(formatDuration(it * 1000))
                    }
                },
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.basicMarquee(),
        )

        SongDownloadBadge(song.id, colorScheme)
    }
}

@Composable
private fun SongDownloadBadge(
    songId: String,
    colorScheme: ColorScheme,
) {
    val downloadState by remember(songId) { derivedStateOf { LibraryState.downloadStates[songId] } }
    val rawProgress by remember(songId) { derivedStateOf { LibraryState.downloadProgress[songId] ?: 0f } }
    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
    )

    when (downloadState) {
        DownloadState.DOWNLOADING -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colorScheme.primaryContainer.copy(alpha = 0.7f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                CircularWavyProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.size(24.dp),
                    color = colorScheme.primary,
                    trackColor = colorScheme.primary.copy(alpha = 0.2f),
                )
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    color = colorScheme.onPrimaryContainer,
                )
            }
        }

        DownloadState.QUEUED -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = colorScheme.secondaryContainer.copy(alpha = 0.5f),
            ) {
                Text(
                    text = stringResource(Res.string.queued),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    color = colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }

        DownloadState.DOWNLOADED -> {
            Icon(
                imageVector = SongStatusIcons.Downloaded,
                contentDescription = stringResource(Res.string.filter_downloaded),
                tint = Color.Unspecified,
                modifier = Modifier.size(11.dp),
            )
        }

        else -> {}
    }
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
fun YTListItem(
    item: YTItem,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (() -> Unit)? = null,
    onRenamePlaylist: ((PlaylistItem) -> Unit)? = null,
    onDeletePlaylist: ((PlaylistItem) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val enabled = !item.isUnavailable
    val contextMenu = rememberCursorContextMenuState()
    val hoverIS = remember { MutableInteractionSource() }
    val isHovered by hoverIS.collectIsHoveredAsState()
    val isFocused by hoverIS.collectIsFocusedAsState()
    val showActions = isHovered || isFocused
    val iconAlpha by animateFloatAsState(if (showActions) 1f else 0f, label = "iconAlpha")

    val finalOnClick = {
        if (enabled) {
            when (item) {
                is ArtistItem -> ArtistState.fetchArtistData(item)
                is PlaylistItem -> PlaylistAlbumState.fetchPlaylistData(item.id, item.thumbnail)
                is AlbumItem -> PlaylistAlbumState.fetchAlbumData(item.id)
                else -> onClick()
            }
        }
    }

    val isCurrentTrack = item is SongItem && PlayerState.currentTrack?.id == item.id
    val shape =
        animatedCurrentSongArtworkShape(
            isCurrentTrack,
            if (item is ArtistItem) artistArtworkShape(item.id) else RoundedCornerShape(ThumbnailCornerRadius),
        )
    val colors = animateCurrentSongColors(isCurrentTrack, colorScheme)
    val contentColor = colors.content
    val subtitleColor = colors.subtitle

    val thumbnail =
        if (item is ArtistItem) {
            val cacheKey = item.id
            val cached = DiscordRpcState.artistIconCache[cacheKey]
            cached ?: item.thumbnail
        } else {
            item.thumbnail
        }

    val song = item as? SongItem
    val previewThumbnail = song?.previewThumbnailUrl ?: thumbnail
    val listThumbnailModifier = Modifier.songThumbSize(song, ListThumbnailSize)
    val artworkInset = if (isCurrentTrack) ListThumbnailSize * ((song?.previewAspectRatio ?: 1f) - 1f).coerceAtLeast(0f) / 2 else 0.dp
    val artworkRotation by rememberCurrentSongArtworkRotation(isCurrentTrack, PlayerState.isPlaying)

    if (item is ArtistItem && ArtistState.artistIconSource != ArtistSource.YOUTUBE) {
        LaunchedEffect(item.id, ArtistState.artistIconSource) {
            if (DiscordRpcState.artistIconCache[item.id] == null) {
                ArtistState.fetchArtistIconOnDemand(item.id, item.title, ArtistState.artistIconSource)
            }
        }
    }

    Box(Modifier.cursorContextMenu(contextMenu)) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .heightIn(min = ListItemHeight)
                    .alpha(if (enabled) 1f else 0.38f)
                    .clip(if (isCurrentTrack) CircleShape else RoundedCornerShape(8.dp))
                    .background(colors.container)
                    .currentSongSemantics(isCurrentTrack, PlayerState.isPlaying)
                    .hoverable(hoverIS)
                    .clickable(
                        interactionSource = hoverIS,
                        indication = LocalIndication.current,
                        onClick = finalOnClick,
                    ).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Box(
                    modifier =
                        listThumbnailModifier
                            .graphicsLayer { rotationZ = artworkRotation }
                            .clip(shape),
                ) {
                    AsyncImage(
                        url = previewThumbnail ?: "",
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = artworkInset)
                                .graphicsLayer { rotationZ = -artworkRotation },
                    )
                }
                if (enabled && !isCurrentTrack && item is SongItem) {
                    Box(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .alpha(iconAlpha)
                                .clip(shape)
                                .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            onClick = {
                                PlayerState.playTrack(item)
                            },
                            shape = CircleShape,
                            color = colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp),
                            shadowElevation = 0.dp,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    MetrolistIcons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.play),
                                    tint = colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                ListItemTitle(
                    text = (item as? SongItem)?.let(SongMetadataState::apply)?.title ?: item.displayTitle,
                    isExplicit = (item as? SongItem)?.isExplicit == true,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                    modifier = Modifier,
                )

                if (item is SongItem) {
                    SongMetadataRow(item, colorScheme, contentColor = subtitleColor)
                } else {
                    val subtitle =
                        buildAnnotatedString {
                            when (item) {
                                is SongItem -> {
                                }

                                is AlbumItem -> {
                                    item.artists.forEachIndexed { index, artist ->
                                        val link =
                                            artist.id?.let { id ->
                                                LinkAnnotation.Clickable("ARTIST") {
                                                    CollectionPreviews.record(artist)
                                                    ArtistState.fetchArtistData(id)
                                                }
                                            }
                                        if (link != null) {
                                            withLink(link) {
                                                withStyle(
                                                    SpanStyle(
                                                        textDecoration = if (showActions) TextDecoration.Underline else TextDecoration.None,
                                                    ),
                                                ) {
                                                    append(
                                                        artist.displayName,
                                                    )
                                                }
                                            }
                                        } else {
                                            append(artist.displayName)
                                        }
                                        if (index < item.artists.lastIndex) append(", ")
                                    }
                                }

                                is ArtistItem -> {
                                    append(stringResource(Res.string.artist))
                                }

                                is PlaylistItem -> {
                                    append(item.author?.let { ArtistNameAliases.resolve(null, it) } ?: stringResource(Res.string.playlist))
                                }

                                is PodcastItem -> {
                                    append(item.author?.let { ArtistNameAliases.resolve(null, it) } ?: stringResource(Res.string.podcast))
                                }

                                is EpisodeItem -> {
                                    append(item.author?.displayName ?: stringResource(Res.string.episode))
                                }

                                is BrowseItem -> {
                                    append(item.subtitle ?: stringResource(Res.string.browse))
                                }
                            }
                        }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium.copy(color = colorScheme.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee(),
                    )
                }
            }

            IconButton(
                onClick = contextMenu::open,
                modifier = Modifier.alpha(iconAlpha).size(48.dp).navigationPopupSource(),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    MetrolistIcons.Default.MoreVert,
                    stringResource(Res.string.more_options_for, item.title),
                    tint = subtitleColor,
                )
            }
        }

        if (trailingContent != null) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 48.dp)) {
                trailingContent()
            }
        }

        when (item) {
            is SongItem -> {
                SongContextMenu(item, contextMenu.expanded, contextMenu.offset, onDismiss = contextMenu::dismiss)
            }

            is AlbumItem -> {
                AlbumContextMenu(item, expanded = contextMenu.expanded, offset = contextMenu.offset, onDismiss = contextMenu::dismiss)
            }

            is ArtistItem -> {
                ArtistContextMenu(item, contextMenu.expanded, contextMenu.offset, contextMenu::dismiss)
            }

            is PlaylistItem -> {
                PlaylistContextMenu(
                    item,
                    expanded = contextMenu.expanded,
                    offset = contextMenu.offset,
                    onDismiss = contextMenu::dismiss,
                    onRename = onRenamePlaylist?.let { action -> { action(item) } },
                    onDelete = onDeletePlaylist?.let { action -> { action(item) } },
                )
            }

            is PodcastItem -> {
                PodcastContextMenu(item, expanded = contextMenu.expanded, offset = contextMenu.offset, onDismiss = contextMenu::dismiss)
            }

            is EpisodeItem -> {
                EpisodeContextMenu(item, contextMenu.expanded, contextMenu.offset, contextMenu::dismiss)
            }

            else -> {}
        }
    }
}
