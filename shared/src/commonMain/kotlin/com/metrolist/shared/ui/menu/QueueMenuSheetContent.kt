/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.menu

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_like
import com.metrolist.shared.generated.resources.unlike
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.uiEnhanced
import org.jetbrains.compose.resources.stringResource

@Composable
fun QueueMenuSheetContent(
    model: MediaMenuModel,
    duration: String?,
    isLiked: Boolean,
    onLike: (() -> Unit)?,
    actionGroup: @Composable (List<PlayerMenuAction>) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            QueueMenuHeader(model.title, model.subtitle.orEmpty(), duration, model.thumbnail, isLiked, onLike)
        }
        if (model.primaryActions.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    model.primaryActions.forEach { action ->
                        QueueMenuActionButton(
                            modifier = Modifier.weight(1f),
                            icon = action.icon,
                            title = action.title,
                            onClick = action.onClick,
                            onLongClick = action.onLongClick,
                        )
                    }
                }
            }
        }
        model.actionGroups.forEachIndexed { index, actions ->
            if (index == 0 || actions.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    actionGroup(actions)
                }
            }
        }
    }
}

@Composable
private fun QueueMenuHeader(
    songTitle: String,
    artistName: String,
    duration: String?,
    thumbnailUrl: String?,
    isLiked: Boolean,
    onLike: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        thumbnailUrl?.let {
            AsyncImage(
                url = it,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(uiEnhanced(4.dp, 8.dp))),
                contentScale = ContentScale.Crop,
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = songTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val subtitle =
                buildString {
                    append(artistName)
                    if (duration != null) {
                        append(" \u2022 ")
                        append(duration)
                    }
                }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        onLike?.let { action ->
            IconButton(onClick = action, shapes = IconButtonDefaults.shapes()) {
                Icon(
                    imageVector = if (isLiked) MetrolistIcons.Filled.Favorite else MetrolistIcons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(if (isLiked) Res.string.unlike else Res.string.action_like),
                    tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueMenuActionButton(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier =
            modifier
                .semantics { role = Role.Button }
                .clip(shape)
                .then(
                    if (onLongClick != null) {
                        Modifier.combinedClickable(
                            onClick = onClick,
                            onLongClick = {
                                onLongClick()
                            },
                        )
                    } else {
                        Modifier.clickable(onClick = onClick)
                    },
                ),
    ) {
        Column(
            modifier = Modifier.padding(top = 14.dp, start = 12.dp, bottom = 14.dp, end = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                fontSize = 12.sp,
                text = title,
            )
        }
    }
}
