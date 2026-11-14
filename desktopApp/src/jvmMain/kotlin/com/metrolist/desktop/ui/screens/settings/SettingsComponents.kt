/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.settings.settingsRowContainerColor
import com.metrolist.shared.ui.utils.segmentedGroupShape

@Composable
fun Material3SettingsGroup(
    title: String? = null,
    colorScheme: ColorScheme,
    searchKeywords: List<String> = emptyList(),
    items: List<Material3SettingsItem>,
    horizontalPadding: Dp = 16.dp,
) {
    val searchQuery = LocalSettingsSearchQuery.current.lowercase()

    if (searchQuery.isNotEmpty() && searchKeywords.isNotEmpty()) {
        val matches =
            searchKeywords.any { keyword ->
                keyword.lowercase().contains(searchQuery) || searchQuery.contains(keyword.lowercase())
            }
        if (!matches) return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp, start = 16.dp, end = 32.dp),
            )
        }

        if (items.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items.forEachIndexed { index, item ->
                    Material3SettingsItemRow(
                        item = item,
                        isFirst = index == 0,
                        isLast = index == items.lastIndex,
                        horizontalPadding = horizontalPadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun Material3SettingsItemRow(
    item: Material3SettingsItem,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    horizontalPadding: Dp = 16.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = segmentedGroupShape(isFirst, isLast, isPressed)
    val backgroundColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.settingsRowContainerColor(isPressed),
        label = "backgroundColor",
    )

    Row(
        modifier =
            item.modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .clip(shape)
                .background(color = backgroundColor)
                .border(
                    width = if (isFocused) 3.dp else 0.dp,
                    color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = shape,
                ).then(
                    if (item.checked != null) {
                        Modifier.toggleable(
                            value = item.checked,
                            enabled = item.enabled && item.onClick != null,
                            role = Role.Switch,
                            interactionSource = interactionSource,
                            indication = ripple(),
                            onValueChange = { item.onClick?.invoke() },
                        )
                    } else {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = ripple(),
                            enabled = item.enabled && item.onClick != null,
                            onClick = { item.onClick?.invoke() },
                        )
                    },
                ).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.leadingContent != null) {
            item.leadingContent.invoke()
            Spacer(modifier = Modifier.width(16.dp))
        } else if (item.icon != null) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            item.iconContainerColor
                                ?: MaterialTheme.colorScheme.primary.copy(alpha = if (item.isHighlighted) 0.15f else 0.1f),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint =
                        item.iconTint
                            ?: if (!item.enabled) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            } else if (item.isHighlighted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                            },
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
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
    val isHighlighted: Boolean = false,
    val enabled: Boolean = true,
    val iconContainerColor: Color? = null,
    val iconTint: Color? = null,
    val modifier: Modifier = Modifier,
    val checked: Boolean? = null,
    val onClick: (() -> Unit)? = null,
)

@Composable
fun SettingsSwitch(
    checked: Boolean,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        enabled = enabled,
        onCheckedChange = null,
        thumbContent = {
            Icon(
                imageVector = if (checked) MetrolistIcons.Filled.Check else MetrolistIcons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}
