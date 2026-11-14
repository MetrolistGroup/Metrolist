/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.update_available_title
import com.metrolist.shared.ui.components.focusOutline
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.settings.settingsRowContainerColor
import com.metrolist.shared.ui.utils.segmentedGroupShape
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber

@Composable
fun Material3SettingsGroup(
    title: String? = null,
    items: List<Material3SettingsItem>,
    horizontalPadding: Dp = 16.dp,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Start,
                modifier =
                    Modifier.padding(
                        bottom = 12.dp,
                        top = 8.dp,
                        start = 16.dp,
                        end = 32.dp,
                    ),
            )
        }

        AnimatedContent(
            targetState = items,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(300))
            },
            label = "itemsTransition",
        ) { currentItems ->
            if (currentItems.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    currentItems.forEachIndexed { index, item ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                            exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
                        ) {
                            Material3SettingsItemRow(
                                item = item,
                                isFirst = index == 0,
                                isLast = index == currentItems.lastIndex,
                                horizontalPadding = horizontalPadding,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun Material3SettingsItemRow(
    item: Material3SettingsItem,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    horizontalPadding: Dp = 16.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = segmentedGroupShape(isFirst, isLast, isPressed)
    val isFocused by interactionSource.collectIsFocusedAsState()
    val hapticManager = rememberMetrolistHaptics()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(isFocused) {
        if (isFocused) bringIntoViewRequester.bringIntoView()
    }

    val backgroundColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.settingsRowContainerColor(isPressed),
        label = "backgroundColor",
    )
    val badgeDescription = item.badgeDescription ?: stringResource(Res.string.update_available_title)

    Row(
        modifier =
            item.modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoViewRequester)
                .padding(horizontal = horizontalPadding)
                .clip(shape)
                .background(color = backgroundColor)
                .focusOutline(shape)
                .then(
                    if (item.checked != null) {
                        Modifier.toggleable(
                            value = item.checked,
                            enabled = item.enabled && item.onClick != null,
                            role = Role.Switch,
                            interactionSource = interactionSource,
                            indication = null,
                            onValueChange = {
                                hapticManager.toggle(it)
                                item.onClick?.invoke()
                            },
                        )
                    } else {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            enabled = item.enabled && item.onClick != null,
                            onClickLabel = item.onClickLabel,
                            onClick = {
                                Timber.tag("Haptics").d("Trigger Material3SettingsGroup item click")
                                hapticManager.perform(HapticEvent.PRESS)
                                item.onClick?.invoke()
                            },
                        )
                    },
                ).semantics {
                    if (item.showBadge) {
                        stateDescription = badgeDescription
                    }
                }.padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.leadingContent != null) {
            item.leadingContent.invoke()
            Spacer(modifier = Modifier.width(16.dp))
        } else if (item.icon != null) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint =
                        if (!item.enabled) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
            ProvideTextStyle(
                MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Normal,
                    color =
                        if (!item.enabled) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                ),
            ) {
                item.title()
            }

            item.description?.let { desc ->
                Spacer(modifier = Modifier.height(2.dp))
                ProvideTextStyle(
                    MaterialTheme.typography.bodyMedium.copy(
                        color =
                            if (!item.enabled) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    ),
                ) {
                    desc()
                }
            }
        }

        item.trailingContent?.let { trailing ->
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

data class Material3SettingsItem(
    val icon: ImageVector? = null,
    val leadingContent: (@Composable () -> Unit)? = null,
    val title: @Composable () -> Unit,
    val description: (@Composable () -> Unit)? = null,
    val trailingContent: (@Composable () -> Unit)? = null,
    val showBadge: Boolean = false,
    val badgeDescription: String? = null,
    val isHighlighted: Boolean = false,
    val enabled: Boolean = true,
    val iconTint: Color? = null,
    val searchTitle: String? = null,
    val searchDescription: String? = null,
    val modifier: Modifier = Modifier,
    val checked: Boolean? = null,
    val onClickLabel: String? = null,
    val onClick: (() -> Unit)? = null,
)
