@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.edit_playlist
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.playback.retainPlaybackKeyFocus
import com.metrolist.shared.utils.highResolutionArtworkUrl
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun PlaylistHeader(
    playlist: PlaylistItem?,
    onEdit: (() -> Unit)? = null,
    cover: DrawableResource? = null,
    previewThumbnail: String? = null,
    isWide: Boolean,
    modifier: Modifier = Modifier,
    songCountLabel: String,
    onAuthorClick: ((String) -> Unit)? = null,
    fallbackIcon: ImageVector? = null,
    isMedium: Boolean = false,
    mediumArtworkSize: Dp = 176.dp,
    titleModifier: Modifier = Modifier,
    actions: @Composable (() -> Unit)? = null,
) {
    CollectionHeader(
        isWide = isWide,
        isMedium = isMedium,
        modifier = modifier,
        mediumArtworkSize = mediumArtworkSize,
        artwork = { artworkModifier, cornerRadius ->
            PlaylistHeaderArtwork(
                playlist = playlist,
                cover = cover ?: playlist?.let(::libraryCover),
                previewThumbnail = previewThumbnail,
                fallbackIcon = fallbackIcon,
                modifier = artworkModifier.clip(RoundedCornerShape(cornerRadius)),
            )
        },
        actions = actions,
    ) {
        PlaylistTitleAndDescription(
            playlist,
            MaterialTheme.typography.headlineMediumEmphasized,
            onEdit,
            songCountLabel,
            onAuthorClick,
            titleModifier,
        )
    }
}

@Composable
private fun PlaylistHeaderArtwork(
    playlist: PlaylistItem?,
    cover: DrawableResource?,
    previewThumbnail: String?,
    fallbackIcon: ImageVector?,
    modifier: Modifier,
) {
    when {
        cover != null -> {
            Image(painterResource(cover), null, modifier)
        }

        !playlist?.customThumbnail.isNullOrBlank() -> {
            PlaylistCoverImage(url = playlist.customThumbnail, modifier = modifier)
        }

        playlist?.isLocal == true -> {
            LocalPlaylistArtwork(playlist.artworkThumbnails, modifier, fallbackUrl = playlist.thumbnail)
        }

        playlist?.thumbnail.isNullOrBlank() && fallbackIcon != null -> {
            Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(fallbackIcon, null, Modifier.fillMaxWidth(0.4f).aspectRatio(1f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        else -> {
            PlaylistCoverImage(
                url = playlist?.thumbnail?.let(::highResolutionArtworkUrl),
                previewUrl = previewThumbnail,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun PlaylistTitleAndDescription(
    playlist: PlaylistItem?,
    style: TextStyle,
    onEdit: (() -> Unit)?,
    metadataLabel: String,
    onAuthorClick: ((String) -> Unit)?,
    titleModifier: Modifier = Modifier,
) {
    val metadataVisible = remember(playlist?.id) { MutableTransitionState(false) }
    metadataVisible.targetState = metadataLabel.isNotBlank()
    val description = playlist?.description
    val descriptionVisible = remember(playlist?.id) { MutableTransitionState(false) }
    descriptionVisible.targetState = !description.isNullOrBlank()
    Column(
        modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExpandablePlaylistTitle(
                title = playlist?.title.orEmpty(),
                style = style,
                playlistId = playlist?.id,
                modifier = Modifier.weight(1f).then(titleModifier),
            )
            if (onEdit != null) {
                IconButton(onClick = onEdit, shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Default.Edit, contentDescription = stringResource(Res.string.edit_playlist))
                }
            }
        }
        playlist?.author?.takeIf(String::isNotBlank)?.let { author ->
            val authorId = playlist.authorId
            val clickable =
                if (authorId != null && onAuthorClick != null) {
                    Modifier.clickable(role = Role.Button) { onAuthorClick(authorId) }
                } else {
                    Modifier
                }
            Row(
                modifier = clickable,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                playlist.authorThumbnail?.let { thumbnail ->
                    AsyncImage(url = thumbnail, modifier = Modifier.size(24.dp).clip(CircleShape))
                }
                Text(
                    text = author,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(
            visibleState = metadataVisible,
            enter =
                expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit =
                shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            Text(
                text = metadataLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visibleState = descriptionVisible,
            enter =
                expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit =
                shrinkVertically(
                    MaterialTheme.motionScheme.defaultSpatialSpec(),
                ) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                SelectionContainer(modifier = Modifier.retainPlaybackKeyFocus()) {
                    Text(
                        description.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandablePlaylistTitle(
    title: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    playlistId: String? = null,
    collapsedMaxLines: Int = 2,
) {
    var isExpanded by remember(playlistId, title) { mutableStateOf(false) }
    var canExpand by remember(playlistId, title) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    Text(
        text = title,
        style = style,
        maxLines = if (isExpanded) Int.MAX_VALUE else collapsedMaxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layoutResult ->
            canExpand = layoutResult.hasVisualOverflow || layoutResult.lineCount > collapsedMaxLines
        },
        modifier =
            modifier
                .animateContentSize()
                .focusOutline(RoundedCornerShape(8.dp))
                .then(
                    if (canExpand || isExpanded) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                        ) {
                            isExpanded = !isExpanded
                        }
                    } else {
                        Modifier
                    },
                ),
    )
}
