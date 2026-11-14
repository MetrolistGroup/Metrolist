package com.metrolist.shared.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.tv.tvFocusHighlight

data class PlayerMenuAction(
    val icon: ImageVector,
    val title: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val selected: Boolean? = null,
    val prominent: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val isLoading: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun PlayerMenuActionGroup(
    actions: List<PlayerMenuAction>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        actions.forEachIndexed { index, action ->
            val shape =
                when {
                    actions.size == 1 -> RoundedCornerShape(28.dp)
                    index == 0 -> RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
                    index == actions.lastIndex -> RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 28.dp, bottomEnd = 28.dp)
                    else -> RoundedCornerShape(6.dp)
                }
            Card(
                onClick = action.onClick,
                enabled = action.enabled,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .alpha(if (action.enabled) 1f else 0.6f)
                        .tvFocusHighlight(shape)
                        .navigationPopupSource(shape),
                shape = shape,
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerMenuActionIcon(action, Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = action.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        action.description?.let { description ->
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerMenuPrimaryActions(
    actions: List<PlayerMenuAction>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        actions.forEachIndexed { index, action ->
            val shape =
                when {
                    actions.size == 1 -> RoundedCornerShape(28.dp)
                    index == 0 -> RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, topEnd = 6.dp, bottomEnd = 6.dp)
                    index == actions.lastIndex -> RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 28.dp, bottomEnd = 28.dp)
                    else -> RoundedCornerShape(6.dp)
                }
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(86.dp)
                        .semantics { role = Role.Button }
                        .tvFocusHighlight(shape)
                        .navigationPopupSource(shape)
                        .clip(shape)
                        .then(
                            if (action.enabled) {
                                if (action.onLongClick != null) {
                                    Modifier.combinedClickable(
                                        onClick = action.onClick,
                                        onLongClick = {
                                            action.onLongClick.invoke()
                                        },
                                    )
                                } else {
                                    Modifier.clickable(onClick = action.onClick)
                                }
                            } else {
                                Modifier
                            },
                        ),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PlayerMenuActionIcon(action, Modifier.size(28.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = action.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerMenuActionIcon(
    action: PlayerMenuAction,
    modifier: Modifier = Modifier,
) {
    if (action.isLoading) {
        ContainedLoadingIndicator(modifier)
    } else {
        Icon(action.icon, contentDescription = null, modifier = modifier)
    }
}
