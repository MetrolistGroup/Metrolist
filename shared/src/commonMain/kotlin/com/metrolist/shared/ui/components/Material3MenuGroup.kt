/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.theme.ComponentShapes

@Composable
fun Material3MenuGroup(
    items: List<Material3MenuItemData>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, item ->
            Material3MenuGroupItem(item, index, items.size)
        }
    }
}

@Composable
internal fun Material3MenuGroupItem(
    item: Material3MenuItemData,
    index: Int,
    count: Int,
) {
    val shape =
        when {
            count == 1 -> ComponentShapes.MenuGroupShape
            index == 0 -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
            index == count - 1 -> RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
            else -> RoundedCornerShape(5.dp)
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .animateContentSize(),
        shape = shape,
        colors =
            item.cardColors ?: CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Material3MenuItemRow(item = item, shape = shape)
    }
}

@Composable
private fun Material3MenuItemRow(
    item: Material3MenuItemData,
    shape: Shape,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .focusOutline(shape)
                .clickable(
                    enabled = item.onClick != null,
                    onClick = { item.onClick?.invoke() },
                ).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.icon?.let { icon ->
            icon()
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
            ProvideTextStyle(MaterialTheme.typography.titleMedium) {
                item.title()
            }

            item.description?.let { desc ->
                Spacer(modifier = Modifier.height(2.dp))
                ProvideTextStyle(
                    MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

data class Material3MenuItemData(
    val icon: (@Composable () -> Unit)? = null,
    val title: @Composable () -> Unit,
    val description: (@Composable () -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
    val cardColors: CardColors? = null,
    val trailingContent: (@Composable () -> Unit)? = null,
)
