/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.tv.tvFocusHighlight
import timber.log.Timber

@Composable
fun Material3CardsGroup(
    title: String? = null,
    headerContent: @Composable (() -> Unit)? = null,
    items: List<Material3CardItem>,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp, top = 8.dp),
            )
        }

        headerContent?.let { header ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                header()
            }
            if (items.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEachIndexed { index, item ->
                val shape =
                    when {
                        items.size == 1 -> {
                            RoundedCornerShape(24.dp)
                        }

                        index == 0 -> {
                            RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
                        }

                        index == items.size - 1 -> {
                            RoundedCornerShape(
                                topStart = 6.dp,
                                topEnd = 6.dp,
                                bottomStart = 24.dp,
                                bottomEnd = 24.dp,
                            )
                        }

                        else -> {
                            RoundedCornerShape(6.dp)
                        }
                    }

                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                    shape = shape,
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Material3CardItemRow(item = item)
                }
            }
        }
    }
}

@Composable
private fun Material3CardItemRow(item: Material3CardItem) {
    val hapticManager = rememberMetrolistHaptics()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .tvFocusHighlight(RoundedCornerShape(16.dp))
                .clickable(
                    enabled = item.enabled && item.onClick != null,
                    onClick = {
                        Timber.tag("Haptics").d("Trigger Material3CardsGroup item click")
                        hapticManager.perform(HapticEvent.PRESS)
                        item.onClick?.invoke()
                    },
                ).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.icon?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint =
                    if (!item.enabled) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(24.dp),
            )

            Spacer(modifier = Modifier.width(16.dp))
        } ?: item.iconPainter?.let { icon ->
            Icon(
                painter = icon,
                contentDescription = null,
                tint =
                    if (!item.enabled) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(24.dp),
            )

            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
            ProvideTextStyle(
                MaterialTheme.typography.titleMedium.copy(
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

data class Material3CardItem(
    val icon: ImageVector? = null,
    val iconPainter: Painter? = null,
    val title: @Composable () -> Unit,
    val description: (@Composable () -> Unit)? = null,
    val trailingContent: (@Composable () -> Unit)? = null,
    val enabled: Boolean = true,
    val onClick: (() -> Unit)? = null,
)
