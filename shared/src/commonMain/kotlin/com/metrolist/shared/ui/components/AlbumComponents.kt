@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.constants.currentGridThumbnailHeight
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.minutes_short
import com.metrolist.shared.generated.resources.n_song
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.albumTotalDuration
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.menu.artistLinksText
import com.metrolist.shared.ui.navigation.AlbumArtworkRole
import com.metrolist.shared.ui.navigation.AlbumArtworkSource
import com.metrolist.shared.ui.navigation.albumArtworkTransition
import com.metrolist.shared.ui.navigation.navigationPageSource
import org.jetbrains.compose.resources.pluralStringResource

@Composable
fun AlbumHeader(
    album: AlbumItem,
    songs: List<SongItem>,
    isWide: Boolean,
    modifier: Modifier = Modifier,
    onArtistClick: (String) -> Unit,
    description: String?,
    showMoreLabel: String,
    showLessLabel: String,
    isMedium: Boolean = false,
    mediumArtworkSize: Dp = 176.dp,
    actions: @Composable (() -> Unit)? = null,
    titleModifier: Modifier = Modifier,
) {
    CollectionHeader(
        isWide = isWide,
        isMedium = isMedium,
        modifier = modifier,
        mediumArtworkSize = mediumArtworkSize,
        artwork = { artworkModifier, cornerRadius -> AlbumHeaderArtwork(album, artworkModifier, cornerRadius) },
        actions = actions,
    ) {
        Text(
            text = album.title,
            modifier = titleModifier,
            style = MaterialTheme.typography.headlineMediumEmphasized,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = artistLinksText(album.artists, onArtistClick) ?: AnnotatedString(""),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = albumMetadataText(album, songs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AnimatedVisibility(
            visible = !description.isNullOrBlank(),
            enter =
                expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                    fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit =
                shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                    fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                ExpandableText(
                    text = description.orEmpty(),
                    collapsedMaxLines = 2,
                    showMoreText = showMoreLabel,
                    showLessText = showLessLabel,
                )
            }
        }
    }
}

@Composable
private fun AlbumHeaderArtwork(
    album: AlbumItem,
    modifier: Modifier,
    cornerRadius: Dp,
) {
    AsyncImage(
        url = album.thumbnail ?: "",
        previewUrl = CollectionPreviews.album(album.id)?.thumbnail ?: album.thumbnail,
        modifier = Modifier.albumArtworkTransition(album.id, AlbumArtworkRole.Header, cornerRadius).then(modifier),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun albumMetadataText(
    album: AlbumItem,
    songs: List<SongItem>,
): String {
    val songCount = album.songCount ?: songs.size.takeIf { it > 0 }
    val songsCountText = songCount?.let { pluralStringResource(Res.plurals.n_song, it, it) }
    val minutes = albumTotalDuration(album, songs)?.div(60)?.toInt()
    val durationText = minutes?.let { pluralStringResource(Res.plurals.minutes_short, it, it) }
    return listOfNotNull(
        album.type,
        songs.firstNotNullOfOrNull { it.genre?.takeIf(String::isNotBlank) },
        album.year,
        songsCountText,
        durationText,
    ).joinToString(" • ")
}

@Composable
fun OtherVersionItem(
    album: AlbumItem,
    sourceAlbumId: String,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbnailSize = currentGridThumbnailHeight()
    val token = "album:$sourceAlbumId:${album.id}"
    val artworkSource = AlbumArtworkSource(album, token) { onClick(token) }
    Column(
        modifier =
            modifier
                .width(thumbnailSize)
                .navigationPageSource()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    artworkSource.select()
                },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            url = album.thumbnail ?: "",
            modifier = artworkSource.modifier(8.dp).size(thumbnailSize),
            contentScale = ContentScale.Crop,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = album.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )

        Text(
            text = album.year ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
