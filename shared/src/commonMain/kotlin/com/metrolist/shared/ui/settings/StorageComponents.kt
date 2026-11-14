@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.calculating
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.clear
import com.metrolist.shared.generated.resources.size_used
import com.metrolist.shared.generated.resources.storage_in_total
import com.metrolist.shared.storage.storageUsageFraction
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.playback.retainPlaybackKeyFocus
import com.metrolist.shared.ui.utils.rememberFileSizeFormatter
import org.jetbrains.compose.resources.stringResource

@Composable
fun StorageContent(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
fun StorageSummary(
    usedBytes: Long,
    totalBytes: Long? = null,
    loading: Boolean,
) {
    val formatFileSize = rememberFileSizeFormatter()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) {
            val calculating = stringResource(Res.string.calculating)
            Box(
                Modifier
                    .width(220.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .semantics { contentDescription = calculating },
            )
        } else {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val size = formatFileSize(usedBytes)
                val used = stringResource(Res.string.size_used, size)
                Text(
                    buildAnnotatedString {
                        append(used)
                        val start = used.indexOf(size)
                        if (start >= 0) {
                            addStyle(
                                SpanStyle(fontSize = MaterialTheme.typography.displayLarge.fontSize),
                                start,
                                start + size.substringBeforeLast(' ').length,
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = MaterialTheme.typography.displayLarge.lineHeight),
                    modifier = Modifier.align(Alignment.Bottom),
                )
                if (totalBytes != null && totalBytes > 0) {
                    Text(
                        stringResource(Res.string.storage_in_total, formatFileSize(totalBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Bottom).padding(bottom = 8.dp),
                    )
                }
            }
        }
        if (totalBytes != null && totalBytes > 0) {
            LinearProgressIndicator(
                progress = { storageUsageFraction(usedBytes, totalBytes) },
                modifier = Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(16.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        }
    }
}

@Composable
fun StorageSection(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
    }
}

@Composable
private fun StorageTile(
    isFirst: Boolean,
    isLast: Boolean,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    checked: Boolean? = null,
    onClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val haptics = rememberMetrolistHaptics()
    val shape =
        RoundedCornerShape(
            topStart = if (isFirst) 24.dp else 4.dp,
            topEnd = if (isFirst) 24.dp else 4.dp,
            bottomStart = if (isLast) 24.dp else 4.dp,
            bottomEnd = if (isLast) 24.dp else 4.dp,
        )
    Surface(
        modifier =
            Modifier.fillMaxWidth().clip(shape).then(
                when {
                    onClick == null -> {
                        Modifier
                    }

                    checked != null -> {
                        Modifier.toggleable(checked, enabled = enabled, role = Role.Switch) {
                            haptics.toggle(it)
                            onClick()
                        }
                    }

                    else -> {
                        Modifier.clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                    }
                },
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box(Modifier.heightIn(min = 56.dp).padding(16.dp)) { content() }
    }
}

@Composable
fun StorageUsageRow(
    icon: ImageVector,
    label: String,
    sizeText: String,
    progress: Float,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    StorageTile(isFirst, isLast, onClick, onClickLabel = onClickLabel) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(sizeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.secondaryContainer,
                )
            }
            if (onClick != null && onClickLabel != null) {
                IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        MetrolistIcons.Outlined.Delete,
                        contentDescription = onClickLabel,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun StorageSettingRow(
    title: String,
    subtitle: String? = null,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    checked: Boolean? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    StorageTile(isFirst, isLast, onClick, enabled, checked) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailingContent?.invoke()
        }
    }
}

@Composable
fun StorageSliderRow(
    title: String,
    subtitle: String,
    value: Float,
    onValueChangeFinished: (Float) -> Unit,
    steps: Int,
    revision: Int = 0,
    enabled: Boolean = true,
    isFirst: Boolean = true,
    isLast: Boolean = false,
) {
    StorageTile(isFirst, isLast) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            key(value, revision) {
                var draft by remember { mutableStateOf(value) }
                val sliderState = remember(steps) { SliderState(value = draft, trackRange = 0f..steps.toFloat(), steps = steps - 1) }
                sliderState.value = draft
                Slider(
                    state = sliderState,
                    onValueChange = { draft = it },
                    onValueChangeFinished = { onValueChangeFinished(draft) },
                    enabled = enabled,
                    modifier = Modifier.retainPlaybackKeyFocus().fillMaxWidth().semantics { contentDescription = title },
                )
            }
        }
    }
}

@Composable
fun StorageConfirmationDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = stringResource(Res.string.clear),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = onConfirm, shapes = ButtonDefaults.shapes()) { Text(confirmLabel) } },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.cancel)) }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
    )
}
