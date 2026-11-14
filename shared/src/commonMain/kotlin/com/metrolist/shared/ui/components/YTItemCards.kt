/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselItemDrawInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.constants.CarouselItemCornerRadius
import com.metrolist.shared.constants.GridCaptionSafeSpace
import com.metrolist.shared.constants.ThumbnailCornerRadius
import com.metrolist.shared.constants.currentGridThumbnailHeight
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album_type
import com.metrolist.shared.generated.resources.artist
import com.metrolist.shared.generated.resources.artist_type
import com.metrolist.shared.generated.resources.browse
import com.metrolist.shared.generated.resources.episode
import com.metrolist.shared.generated.resources.monthly_audience_short
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.music_video_type
import com.metrolist.shared.generated.resources.n_track
import com.metrolist.shared.generated.resources.playlist
import com.metrolist.shared.generated.resources.podcast
import com.metrolist.shared.generated.resources.song_type
import com.metrolist.shared.generated.resources.video_type
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.model.isOfficialMusicVideo
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.model.previewAspectRatio
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.model.thumbnailAspectRatio
import com.metrolist.shared.settings.ArtistNameAliases
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.settings.displayTitle
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.artworkMenuActions
import com.metrolist.shared.ui.menu.onSecondaryClick
import com.metrolist.shared.ui.navigation.AlbumArtworkSource
import com.metrolist.shared.ui.navigation.navigationPageSource
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.navigation.rememberAlbumArtworkSource
import com.metrolist.shared.ui.theme.gridCellInset
import com.metrolist.shared.ui.theme.gridCellShape
import com.metrolist.shared.ui.theme.uiEnhanced
import com.metrolist.shared.ui.utils.artistArtworkShape
import com.metrolist.shared.ui.utils.carouselCaption
import com.metrolist.shared.utils.formatDuration
import com.metrolist.shared.utils.isDesktop
import com.metrolist.shared.utils.monthlyAudienceText
import com.metrolist.shared.utils.withoutPlaybackCountLabel
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun MetadataLink(
    text: String,
    id: String?,
    onClick: ((String) -> Unit)?,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    linkColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (!enabled || id == null || onClick == null) {
        Text(text, modifier, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val currentClick = rememberUpdatedState(onClick)
    val linkStyles =
        TextLinkStyles(
            focusedStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            hoveredStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
        )
    val annotatedText =
        remember(text, id, enabled, linkStyles) {
            buildAnnotatedString {
                withLink(LinkAnnotation.Clickable(id, linkStyles) { currentClick.value(id) }) { append(text) }
            }
        }
    MetadataText(annotatedText, modifier.navigationPageSource(), style, color)
}

@Composable
fun ArtistSubtitle(
    artists: List<ArtistTiny>,
    onArtistClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    clickable: Boolean = true,
    linkColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (!clickable || onArtistClick == null) {
        val text = remember(artists, ArtistNameAliases.aliases) { artists.joinToString { it.displayName } }
        Text(text, modifier, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val currentClick = rememberUpdatedState(onArtistClick)
    val linkStyles =
        TextLinkStyles(
            style = SpanStyle(color = color),
            focusedStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            hoveredStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
        )
    val text =
        remember(artists, linkStyles, ArtistNameAliases.aliases) {
            buildAnnotatedString {
                artists.forEachIndexed { index, artist ->
                    val artistId = artist.id
                    if (artistId != null) {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = artistId,
                                styles = linkStyles,
                                linkInteractionListener = {
                                    CollectionPreviews.record(artist)
                                    currentClick.value(artistId)
                                },
                            ),
                        ) {
                            append(artist.displayName)
                        }
                    } else {
                        append(artist.displayName)
                    }
                    if (index < artists.lastIndex) append(", ")
                }
            }
        }
    MetadataText(text, modifier, style, color)
}

@Composable
private fun MetadataText(
    text: AnnotatedString,
    modifier: Modifier,
    style: TextStyle,
    color: Color,
) {
    val configuration = LocalViewConfiguration.current
    val linkConfiguration =
        remember(configuration) {
            object : ViewConfiguration by configuration {
                override val minimumTouchTargetSize = DpSize.Zero
            }
        }
    CompositionLocalProvider(LocalViewConfiguration provides linkConfiguration) {
        Text(
            text = text,
            modifier = modifier,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongListItem(
    song: SongItem,
    onClick: () -> Unit,
    onShowMenu: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    index: Int? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    isDownloaded: Boolean = false,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    clickableMetadata: Boolean = true,
    showThumbnail: Boolean = false,
    showAlbum: Boolean = false,
    longClickEnabled: Boolean = true,
    enabled: Boolean = !song.isUnavailable,
) {
    val effectiveSong = SongMetadataState.apply(song)
    val hasArtwork = song.thumbnail != null && (isCurrent || showThumbnail || leadingContent != null || index == null)
    val colors = animateCurrentSongColors(isCurrent)
    val contentColor = colors.content
    val subtitleColor = colors.subtitle
    val linkColor = colors.link
    val artworkShape = animatedCurrentSongArtworkShape(isCurrent, RoundedCornerShape(4.dp))
    val artworkRotation by rememberCurrentSongArtworkRotation(isCurrent, isPlaying)
    val numberedArtwork = index != null && !showThumbnail && leadingContent == null
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .alpha(if (enabled) 1f else 0.38f)
                .clip(if (isCurrent) CircleShape else RoundedCornerShape(8.dp))
                .background(colors.container)
                .focusOutline(if (isCurrent) CircleShape else RoundedCornerShape(8.dp))
                .currentSongSemantics(isCurrent, isPlaying)
                .combinedClickable(
                    onClick = { if (enabled) onClick() },
                    onLongClick = if (longClickEnabled) onLongClick ?: onShowMenu else null,
                ).onSecondaryClick(onShowMenu)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingContent?.let {
            Box(
                modifier = Modifier.width(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                it()
            }
            Spacer(Modifier.width(8.dp))
        }

        if (leadingContent == null && (index != null || isCurrent) && !(isCurrent && hasArtwork)) {
            Box(
                modifier = Modifier.width(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isCurrent && !hasArtwork) {
                    Icon(
                        imageVector = if (isPlaying) MetrolistIcons.Rounded.PlayArrow else MetrolistIcons.Default.Pause,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(
                        text = "${index!! + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        if (hasArtwork) {
            Box(
                modifier =
                    Modifier
                        .squareArtworkSlot(if (numberedArtwork) 36.dp else 48.dp, if (isCurrent) 1f else song.previewAspectRatio)
                        .graphicsLayer { rotationZ = artworkRotation }
                        .clip(artworkShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = -artworkRotation },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        url = song.previewThumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Spacer(Modifier.width(if (numberedArtwork) 8.dp else 12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            ListItemTitle(
                text = effectiveSong.title,
                isExplicit = isDesktop && effectiveSong.isExplicit,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                ItemStatusBadges(effectiveSong, isDownloaded, showExplicit = !isDesktop)

                ArtistSubtitle(
                    artists = effectiveSong.artists,
                    onArtistClick = onArtistClick,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f, fill = false),
                    clickable = clickableMetadata,
                    color = subtitleColor,
                    linkColor = linkColor,
                )

                if (showAlbum && effectiveSong.album != null) {
                    Text(text = " • ", style = MaterialTheme.typography.bodySmall, color = subtitleColor)
                    MetadataLink(
                        text = effectiveSong.album.name,
                        id = effectiveSong.album.id,
                        onClick = onAlbumClick,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f, fill = false),
                        enabled = clickableMetadata,
                        color = subtitleColor,
                        linkColor = linkColor,
                    )
                }

                effectiveSong.duration?.let {
                    val mins = it / 60
                    val secs = it % 60
                    Text(
                        text = " • $mins:${secs.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (song.isPodcast) EpisodePlayedIndicator(song.id, song.duration)
        }

        if (trailingContent != null) {
            trailingContent()
        } else {
            IconButton(modifier = Modifier.navigationPopupSource(), onClick = onShowMenu, shapes = IconButtonDefaults.shapes()) {
                Icon(
                    MetrolistIcons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.more_options),
                    tint = if (isCurrent) contentColor else LocalContentColor.current,
                )
            }
        }
    }
}

@Composable
fun ListItemTitle(
    text: String,
    isExplicit: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    fontWeight: FontWeight = FontWeight.Medium,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f, fill = false),
            style = style,
            fontWeight = fontWeight,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isExplicit) ExplicitBadge()
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.squareArtworkSlot(
    size: Dp,
    aspectRatio: Float,
): Modifier = this.size(size).padding(vertical = maxOf(0.dp, size * (1f - 1f / aspectRatio) / 2))

@Composable
fun YTGridItem(
    item: YTItem,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onShowMenu: ((YTItem) -> Unit)? = null,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    imageSize: Dp = currentGridThumbnailHeight(),
    imageShape: Shape? = null,
    artworkModifier: Modifier = Modifier,
    artworkAspectRatio: Float? = null,
    carouselItemDrawInfo: CarouselItemDrawInfo? = null,
    contentPadding: Dp = 0.dp,
    subtitleOverride: String? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    fillWidth: Boolean = false,
    squareArtwork: Boolean = false,
    largeText: Boolean = false,
    clickableMetadata: Boolean = true,
    enabled: Boolean = !item.isUnavailable,
    onAlbumPlay: (() -> Unit)? = null,
    menuActionLabel: String = stringResource(Res.string.more_options),
    showAlbumYear: Boolean = false,
    artwork: AlbumArtworkSource? = null,
    showDurationBadge: Boolean = false,
) {
    val itemEnabled = enabled && !item.isUnavailable
    val pageArtwork = artwork ?: rememberAlbumArtworkSource(item, onClick)
    val onMenuClick = onShowMenu?.let { show -> { show(item) } } ?: onLongClick
    val subtitleStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = if (largeText) 12.sp else 10.sp,
            lineHeight = if (largeText) 16.sp else 12.sp,
            fontWeight = FontWeight.Medium,
        )
    val artworkCornerRadius = if (carouselItemDrawInfo == null) uiEnhanced(ThumbnailCornerRadius, 12.dp) else CarouselItemCornerRadius
    val inset = if (carouselItemDrawInfo == null) gridCellInset() else 0.dp
    val artworkSize = (imageSize - inset * 2).coerceAtLeast(0.dp)
    val shape =
        imageShape
            ?: when (item) {
                is ArtistItem -> artistArtworkShape(item.id)
                is PodcastItem -> CircleShape
                else -> RoundedCornerShape(uiEnhanced(ThumbnailCornerRadius, 12.dp))
            }

    Column(
        modifier =
            modifier
                .alpha(if (itemEnabled) 1f else 0.38f)
                .clip(gridCellShape())
                .focusOutline(gridCellShape())
                .artworkMenuActions(onMenuClick, menuActionLabel)
                .then(if (pageArtwork != null) Modifier.navigationPageSource(pageArtwork.token) else Modifier)
                .combinedClickable(
                    enabled = itemEnabled || onMenuClick != null,
                    onClick = {
                        if (itemEnabled && onClick != null) {
                            CollectionPreviews.record(item)
                            onClick()
                        }
                    },
                    onLongClick = onLongClick ?: onMenuClick,
                    onLongClickLabel = menuActionLabel,
                ).padding(inset)
                .padding(contentPadding),
    ) {
        Box(
            modifier =
                (if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(artworkSize))
                    .aspectRatio(artworkAspectRatio ?: 1f)
                    .then(artworkModifier),
            contentAlignment = Alignment.Center,
        ) {
            val aspectRatio = if (squareArtwork) 1f else artworkAspectRatio ?: (item as? SongItem)?.previewAspectRatio ?: 1f
            val imageModifier =
                if (fillWidth) {
                    Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                } else {
                    Modifier.width(artworkSize).aspectRatio(aspectRatio)
                }

            Box(
                modifier =
                    (pageArtwork?.modifier(artworkCornerRadius, carouselItemDrawInfo) ?: Modifier)
                        .then(imageModifier)
                        .clipToBounds()
                        .then(
                            if (pageArtwork == null && (item !is ArtistItem || carouselItemDrawInfo == null)) {
                                Modifier.clip(shape)
                            } else {
                                Modifier
                            },
                        ).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (item is PlaylistItem) {
                    PlaylistCoverImage(item, Modifier.fillMaxSize())
                } else if (item.thumbnail != null) {
                    AsyncImage(
                        url = if (squareArtwork) item.thumbnail else item.displayThumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector =
                            when (item) {
                                is ArtistItem -> {
                                    MetrolistIcons.Outlined.AccountCircle
                                }

                                is PlaylistItem -> {
                                    MetrolistIcons.Outlined.LibraryMusic
                                }

                                is BrowseItem -> {
                                    when {
                                        item.id.startsWith("FEmusic_new_releases") -> MetrolistIcons.Outlined.NewReleases
                                        item.id.startsWith("FEmusic_charts") -> MetrolistIcons.Outlined.BarChart4Bars
                                        item.id.startsWith("FEmusic_moods_and_genres") -> MetrolistIcons.Outlined.Palette
                                        else -> MetrolistIcons.Outlined.MusicNote
                                    }
                                }

                                else -> {
                                    MetrolistIcons.Outlined.MusicNote
                                }
                            },
                        contentDescription = null,
                        modifier = Modifier.size(imageSize / 2),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (item is SongItem || item is EpisodeItem) {
                    SongThumbnailPlayOverlay(
                        isActive = isCurrent,
                        isPlaying = isPlaying,
                        shape = shape,
                    )
                }
                val duration =
                    when (item) {
                        is SongItem -> item.duration
                        is EpisodeItem -> item.duration
                        else -> null
                    }
                if (showDurationBadge && duration != null && duration > 0) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.inverseSurface,
                    ) {
                        Text(
                            text = formatDuration(duration * 1000),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
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

        Column(Modifier.carouselCaption(carouselItemDrawInfo)) {
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = (item as? SongItem)?.let(SongMetadataState::apply)?.title ?: item.displayTitle,
                color = MaterialTheme.colorScheme.onSurface,
                style = if (largeText) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )

            val subtitle =
                subtitleOverride ?: when (item) {
                    is SongItem -> {
                        SongMetadataState.apply(item).artists.joinToString { it.displayName }
                    }

                    is AlbumItem -> {
                        item.artists.joinToString { it.displayName }.ifEmpty { item.year?.takeIf { showAlbumYear }.orEmpty() }
                    }

                    is ArtistItem -> {
                        ""
                    }

                    is PlaylistItem -> {
                        listOfNotNull(item.author?.let { ArtistNameAliases.resolve(null, it) }, item.songCount)
                            .joinToString(" • ")
                            .ifEmpty { stringResource(Res.string.playlist) }
                    }

                    is PodcastItem -> {
                        stringResource(Res.string.podcast)
                    }

                    is EpisodeItem -> {
                        stringResource(Res.string.episode)
                    }

                    is BrowseItem -> {
                        item.subtitle ?: stringResource(Res.string.browse)
                    }
                }

            if (subtitle.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = if (item is ArtistItem) Arrangement.Center else Arrangement.Start,
                ) {
                    ItemStatusBadges((item as? SongItem)?.let(SongMetadataState::apply) ?: item)
                    if (subtitleOverride != null) {
                        Text(
                            text = subtitleOverride,
                            style = subtitleStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if ((item is SongItem || item is AlbumItem) && !(item is AlbumItem && item.artists.isEmpty())) {
                        val subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant

                        if (item is SongItem) {
                            SongSubtitle(
                                SongMetadataState.apply(item),
                                onArtistClick,
                                clickableMetadata,
                                subtitleStyle,
                                Modifier.weight(1f),
                                showDuration = !showDurationBadge,
                            )
                        } else if (item is AlbumItem) {
                            val type = item.type ?: stringResource(Res.string.album_type)
                            val trackCount = item.songCount?.let { pluralStringResource(Res.plurals.n_track, it, it) }
                            val year = item.year?.takeIf { showAlbumYear }
                            val detail = if (year != null) listOfNotNull(year, trackCount).joinToString(" • ") else trackCount ?: item.year
                            SuffixedSubtitle(
                                fullSuffix = listOfNotNull(type, detail).joinToString(" • "),
                                compactSuffix = year ?: type,
                                style = subtitleStyle,
                                color = subtitleColor,
                                modifier = Modifier.weight(1f),
                            ) {
                                ArtistSubtitle(
                                    artists = item.artists,
                                    onArtistClick = onArtistClick,
                                    style = subtitleStyle,
                                    color = subtitleColor,
                                    clickable = clickableMetadata,
                                )
                            }
                        }
                    } else {
                        Text(
                            text = subtitle,
                            style = subtitleStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(GridCaptionSafeSpace))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YTListItem(
    item: YTItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onShowMenu: (() -> Unit)? = null,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    trailingContent: @Composable (() -> Unit)? = null,
    clickableMetadata: Boolean = true,
    enabled: Boolean = !item.isUnavailable,
    compactSongSubtitle: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    onAlbumPlay: (() -> Unit)? = null,
) {
    val pageArtwork = rememberAlbumArtworkSource(item, onClick)
    val itemEnabled = enabled && !item.isUnavailable
    val currentSong = isCurrent && (item is SongItem || item is EpisodeItem)
    val colors = animateCurrentSongColors(currentSong)
    val contentColor = colors.content
    val subtitleColor = colors.subtitle
    val linkColor = colors.link
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .alpha(if (itemEnabled) 1f else 0.38f)
                .clip(if (currentSong) CircleShape else RoundedCornerShape(4.dp))
                .background(colors.container)
                .focusOutline(if (currentSong) CircleShape else RoundedCornerShape(4.dp))
                .currentSongSemantics(currentSong, isPlaying)
                .then(if (pageArtwork != null) Modifier.navigationPageSource(pageArtwork.token) else Modifier)
                .combinedClickable(
                    enabled = itemEnabled || onLongClick != null || onShowMenu != null,
                    onClick = {
                        if (itemEnabled) {
                            CollectionPreviews.record(item)
                            onClick()
                        }
                    },
                    onLongClick = onLongClick ?: onShowMenu,
                ).onSecondaryClick(onLongClick ?: onShowMenu)
                .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape =
            animatedCurrentSongArtworkShape(
                currentSong,
                if (item is ArtistItem) artistArtworkShape(item.id) else RoundedCornerShape(4.dp),
            )
        val artworkRotation by rememberCurrentSongArtworkRotation(currentSong, isPlaying)

        Box(
            modifier =
                Modifier
                    .then(pageArtwork?.modifier(4.dp) ?: Modifier)
                    .squareArtworkSlot(48.dp, if (currentSong) 1f else item.thumbnailAspectRatio)
                    .graphicsLayer { rotationZ = artworkRotation }
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = -artworkRotation },
                contentAlignment = Alignment.Center,
            ) {
                if (item is PlaylistItem) {
                    PlaylistCoverImage(item, Modifier.fillMaxSize())
                } else if (item.thumbnail != null) {
                    AsyncImage(
                        url = item.displayThumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (item is AlbumItem && onAlbumPlay != null) {
                    AlbumPlayButton(
                        visible = !isCurrent,
                        onClick = onAlbumPlay,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            ListItemTitle(
                text = (item as? SongItem)?.let(SongMetadataState::apply)?.title ?: item.displayTitle,
                isExplicit = isDesktop && (item as? SongItem)?.isExplicit == true,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = contentColor,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ItemStatusBadges((item as? SongItem)?.let(SongMetadataState::apply) ?: item, showExplicit = !isDesktop)

                if (item is SongItem || item is AlbumItem) {
                    val subtitleStyle = MaterialTheme.typography.labelSmall
                    if (item is SongItem) {
                        SongSubtitle(
                            SongMetadataState.apply(item),
                            onArtistClick,
                            clickableMetadata,
                            subtitleStyle,
                            Modifier.weight(1f),
                            !compactSongSubtitle,
                            color = subtitleColor,
                            linkColor = linkColor,
                        )
                    } else if (item is AlbumItem) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            val type = item.type ?: stringResource(Res.string.album_type)
                            Text(
                                text = "$type • ",
                                style = subtitleStyle,
                                color = subtitleColor,
                                maxLines = 1,
                                softWrap = false,
                            )
                            ArtistSubtitle(
                                artists = item.artists,
                                onArtistClick = onArtistClick,
                                style = subtitleStyle,
                                color = subtitleColor,
                                modifier = Modifier.weight(1f, fill = false),
                                clickable = clickableMetadata,
                            )
                            val count = item.songCount?.let { pluralStringResource(Res.plurals.n_track, it, it) }
                            val detail = count ?: item.year
                            if (detail != null) {
                                Text(
                                    text = " • $detail",
                                    style = subtitleStyle,
                                    color = subtitleColor,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                } else {
                    val subtitle =
                        when (item) {
                            is ArtistItem -> {
                                monthlyAudienceText(item.monthlyListeners, stringResource(Res.string.monthly_audience_short))
                                    ?: item.subscribers
                                    ?: stringResource(Res.string.artist_type)
                            }

                            is PlaylistItem -> {
                                listOfNotNull(item.author?.let { ArtistNameAliases.resolve(null, it) }, item.songCount).joinToString(" • ")
                            }

                            is PodcastItem -> {
                                listOfNotNull(
                                    item.author?.let { ArtistNameAliases.resolve(null, it) },
                                    item.episodeCount,
                                ).joinToString(" • ")
                            }

                            is EpisodeItem -> {
                                listOfNotNull(
                                    item.author?.displayName,
                                    item.publishDateText,
                                    item.duration?.let { formatDuration(it * 1000) },
                                ).joinToString(" • ")
                            }

                            is BrowseItem -> {
                                item.subtitle ?: ""
                            }
                        }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = subtitleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (item is EpisodeItem) EpisodePlayedIndicator(item.id, item.duration, serverProgressPercent = item.playbackProgressPercent)
        }

        if (trailingContent != null) {
            trailingContent()
        } else if (onShowMenu != null) {
            IconButton(modifier = Modifier.navigationPopupSource(), onClick = onShowMenu, shapes = IconButtonDefaults.shapes()) {
                Icon(
                    MetrolistIcons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.more_options),
                    tint = if (currentSong) contentColor else LocalContentColor.current,
                )
            }
        }
    }
}

@Composable
private fun SongSubtitle(
    song: SongItem,
    onArtistClick: ((String) -> Unit)?,
    clickable: Boolean,
    style: TextStyle,
    modifier: Modifier,
    showCount: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    showDuration: Boolean = true,
) {
    val fullSuffix =
        song.duration?.takeIf { showDuration }?.let { formatDuration(it * 1000) } ?: song.playCountText.takeIf { showCount }.orEmpty()
    SuffixedSubtitle(fullSuffix, fullSuffix.withoutPlaybackCountLabel(), style, color, modifier) {
        if (song.artists.isNotEmpty()) {
            ArtistSubtitle(song.artists, onArtistClick, style = style, clickable = clickable, color = color, linkColor = linkColor)
        } else {
            Text(
                stringResource(
                    when {
                        song.isOfficialMusicVideo -> Res.string.music_video_type
                        song.isVideoSong -> Res.string.video_type
                        else -> Res.string.song_type
                    },
                ),
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SuffixedSubtitle(
    fullSuffix: String,
    compactSuffix: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier,
    artistLine: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            artistLine()
            for (suffix in listOf(fullSuffix, compactSuffix)) {
                Text(
                    if (suffix.isEmpty()) "" else " • $suffix",
                    style = style,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val full = measurables[1].measure(loose.copy(maxWidth = Constraints.Infinity))
        // Lookahead and the final measure pass can select different suffixes, so both must be measured.
        val compact = measurables[2].measure(loose)
        val artistWidth = measurables[0].maxIntrinsicWidth(constraints.maxHeight)
        val suffix = if (artistWidth.toLong() + full.width <= constraints.maxWidth) full else compact
        val artist = measurables[0].measure(loose.copy(maxWidth = (constraints.maxWidth - suffix.width).coerceAtLeast(0)))
        val width = constraints.constrainWidth(artist.width + suffix.width)
        val height = constraints.constrainHeight(maxOf(artist.height, suffix.height))
        layout(width, height) {
            artist.placeRelative(0, (height - artist.height) / 2)
            suffix.placeRelative(artist.width, (height - suffix.height) / 2)
        }
    }
}

@Composable
fun GridCard(
    title: String,
    subtitle: String,
    thumbnail: String?,
    icon: ImageVector?,
    artistId: String? = null,
    artworkModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    subtitleModifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    cover: DrawableResource? = null,
    localArtwork: List<String>? = null,
    artworkOverlay: (@Composable BoxScope.() -> Unit)? = null,
    artworkAspectRatio: Float = 1f,
    isPlaylistArtwork: Boolean = false,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    val isArtist = artistId != null
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Artwork(
                thumbnail,
                icon,
                modifier = Modifier.fillMaxWidth().aspectRatio(artworkAspectRatio).then(artworkModifier),
                cover = cover,
                localArtwork = localArtwork,
                iconSize = 56.dp,
                cornerRadius = uiEnhanced(ThumbnailCornerRadius, 12.dp),
                shape = artistId?.let { artistArtworkShape(it) } ?: RoundedCornerShape(uiEnhanced(ThumbnailCornerRadius, 12.dp)),
                iconModifier = iconModifier,
                overlay = artworkOverlay,
                isPlaylistArtwork = isPlaylistArtwork,
                isCurrent = isCurrent,
                isPlaying = isPlaying,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            modifier = titleModifier,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
                modifier = subtitleModifier,
            )
        }
        Spacer(Modifier.height(GridCaptionSafeSpace))
    }
}

@Composable
fun ListCard(
    title: String,
    subtitle: String,
    thumbnail: String?,
    icon: ImageVector?,
    artistId: String? = null,
    artworkModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    subtitleModifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    cover: DrawableResource? = null,
    localArtwork: List<String>? = null,
    artworkOverlay: (@Composable BoxScope.() -> Unit)? = null,
    artworkAspectRatio: Float = 1f,
    isPlaylistArtwork: Boolean = false,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            thumbnail,
            icon,
            modifier = Modifier.squareArtworkSlot(48.dp, artworkAspectRatio).then(artworkModifier),
            iconSize = 24.dp,
            cover = cover,
            localArtwork = localArtwork,
            cornerRadius = ThumbnailCornerRadius,
            shape = artistId?.let { artistArtworkShape(it) } ?: RoundedCornerShape(ThumbnailCornerRadius),
            iconModifier = iconModifier,
            overlay = artworkOverlay,
            isPlaylistArtwork = isPlaylistArtwork,
            isCurrent = isCurrent,
            isPlaying = isPlaying,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 17.sp),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = subtitleModifier,
                )
            }
        }
    }
}

@Composable
fun Artwork(
    thumbnail: String?,
    icon: ImageVector?,
    modifier: Modifier,
    iconSize: Dp,
    cornerRadius: Dp,
    shape: Shape = RoundedCornerShape(cornerRadius),
    iconModifier: Modifier = Modifier,
    cover: DrawableResource? = null,
    localArtwork: List<String>? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    isPlaylistArtwork: Boolean = false,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    val artworkShape = animatedCurrentSongArtworkShape(isCurrent, shape)
    val artworkRotation by rememberCurrentSongArtworkRotation(isCurrent, isPlaying)
    Box(modifier) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = artworkRotation }
                    .clip(artworkShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = -artworkRotation },
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    Image(
                        painterResource(cover),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else if (localArtwork != null) {
                    LocalPlaylistArtwork(localArtwork, Modifier.fillMaxSize(), fallbackUrl = thumbnail)
                } else if (isPlaylistArtwork) {
                    PlaylistCoverImage(thumbnail, Modifier.fillMaxSize())
                } else if (thumbnail != null) {
                    AsyncImage(thumbnail, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize).then(iconModifier),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        overlay?.invoke(this)
    }
}

@Composable
fun carouselArtworkShape(item: YTItem): Shape =
    if (item is ArtistItem) artistArtworkShape(item.id) else RoundedCornerShape(CarouselItemCornerRadius)
