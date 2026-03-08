@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.explicit
import com.metrolist.shared.generated.resources.show_less
import com.metrolist.shared.generated.resources.show_more
import com.metrolist.shared.ui.components.AutoPlaylistThumbnail
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.ExpandableText
import com.metrolist.shared.ui.components.PlayerMenuActionGroup
import com.metrolist.shared.ui.components.PlayerMenuPrimaryActions
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.isAutoPlaylistIcon
import com.metrolist.shared.ui.components.squareArtworkSlot
import com.metrolist.shared.ui.icons.SongStatusIcons
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.artistArtworkShape
import org.jetbrains.compose.resources.stringResource

@Composable
fun MediaMenuSheetContent(model: MediaMenuModel) {
    ExpressiveMediaMenuHeader(
        title = model.title,
        subtitle = model.subtitle,
        subtitleLinks = model.subtitleLinks,
        thumbnail = model.thumbnail,
        thumbnailAspectRatio = model.thumbnailAspectRatio,
        fallbackIcon = model.fallbackIcon,
        isExplicit = model.isExplicit,
        artistId = model.artistId,
        modifier = Modifier.padding(top = 12.dp),
        actions =
            model.headerActions.map { action ->
                MediaMenuHeaderAction(
                    icon = action.icon,
                    contentDescription = action.title,
                    onClick = action.onClick,
                    isPrimary = action.prominent,
                    isToggle = action.selected != null,
                    isChecked = action.selected == true,
                    isLoading = action.isLoading,
                )
            },
    )
    model.description?.takeIf(String::isNotBlank)?.let { description ->
        ExpandableText(
            text = description,
            showMoreText = stringResource(Res.string.show_more),
            showLessText = stringResource(Res.string.show_less),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
    if (model.primaryActions.isEmpty()) {
        Spacer(Modifier.height(12.dp))
    }
    PlayerMenuPrimaryActions(
        actions = model.primaryActions,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    model.actionGroups.filter { it.isNotEmpty() }.forEach { actions ->
        PlayerMenuActionGroup(
            actions = actions,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

@Composable
fun ExpressiveMediaMenuHeader(
    title: String,
    subtitle: String?,
    thumbnail: String?,
    modifier: Modifier = Modifier,
    thumbnailAspectRatio: Float = 1f,
    fallbackIcon: ImageVector? = null,
    isExplicit: Boolean = false,
    artistId: String? = null,
    actions: List<MediaMenuHeaderAction> = emptyList(),
    subtitleLinks: AnnotatedString? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (thumbnail.isAutoPlaylistIcon()) {
            AutoPlaylistThumbnail(thumbnail, Modifier.size(64.dp), RoundedCornerShape(8.dp))
        } else if (thumbnail != null) {
            AsyncImage(
                url = thumbnail,
                contentDescription = null,
                modifier =
                    Modifier
                        .squareArtworkSlot(if (artistId != null) 80.dp else 64.dp, thumbnailAspectRatio)
                        .clip(if (artistId != null) artistArtworkShape(artistId) else RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else if (fallbackIcon != null) {
            Box(
                modifier =
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = fallbackIcon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = if (artistId != null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp),
                )
                if (isExplicit && subtitle.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    MediaMenuExplicitBadge()
                }
            }
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subtitleLinks ?: AnnotatedString(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .weight(1f, fill = false)
                                .then(
                                    if (subtitleLinks == null) {
                                        Modifier.basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp)
                                    } else {
                                        Modifier
                                    },
                                ),
                    )
                    if (isExplicit) {
                        Spacer(Modifier.width(6.dp))
                        MediaMenuExplicitBadge()
                    }
                }
            }
        }

        if (actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                actions.forEach { action ->
                    when {
                        action.isToggle -> {
                            FilledTonalIconToggleButton(
                                checked = action.isChecked,
                                onCheckedChange = { action.onClick() },
                                modifier = Modifier.size(56.dp).tvFocusHighlight(CircleShape),
                                shapes = IconButtonDefaults.toggleableShapes(),
                                colors =
                                    IconButtonDefaults.filledTonalIconToggleButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        checkedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ),
                            ) {
                                if (action.isLoading) {
                                    ContainedLoadingIndicator(Modifier.size(28.dp), label = action.contentDescription)
                                } else {
                                    Icon(
                                        imageVector = action.icon,
                                        contentDescription = action.contentDescription,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }
                        }

                        action.isPrimary -> {
                            FilledIconButton(
                                onClick = action.onClick,
                                modifier = Modifier.size(56.dp).tvFocusHighlight(RoundedCornerShape(16.dp)),
                                shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(16.dp)),
                            ) {
                                if (action.isLoading) {
                                    ContainedLoadingIndicator(Modifier.size(32.dp), label = action.contentDescription)
                                } else {
                                    Icon(
                                        imageVector = action.icon,
                                        contentDescription = action.contentDescription,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            }
                        }

                        else -> {
                            FilledTonalIconButton(
                                onClick = action.onClick,
                                modifier = Modifier.size(56.dp).tvFocusHighlight(RoundedCornerShape(16.dp)),
                                shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(16.dp)),
                            ) {
                                if (action.isLoading) {
                                    ContainedLoadingIndicator(Modifier.size(28.dp), label = action.contentDescription)
                                } else {
                                    Icon(
                                        imageVector = action.icon,
                                        contentDescription = action.contentDescription,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MediaMenuExplicitBadge() {
    Icon(
        imageVector = SongStatusIcons.Explicit,
        contentDescription = stringResource(Res.string.explicit),
        tint = Color.Unspecified,
        modifier = Modifier.size(16.dp),
    )
}

data class MediaMenuHeaderAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    val tint: Color? = null,
    val isPrimary: Boolean = false,
    val isToggle: Boolean = false,
    val isChecked: Boolean = false,
    val isLoading: Boolean = false,
)
