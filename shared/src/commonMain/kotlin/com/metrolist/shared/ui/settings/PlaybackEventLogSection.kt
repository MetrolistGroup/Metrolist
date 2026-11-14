@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.settings

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.clear
import com.metrolist.shared.generated.resources.copy
import com.metrolist.shared.generated.resources.devtools_playback_log_count
import com.metrolist.shared.generated.resources.devtools_playback_log_title
import com.metrolist.shared.generated.resources.playback_event_log_raw_description
import com.metrolist.shared.generated.resources.playback_event_log_raw_empty
import com.metrolist.shared.generated.resources.share
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.previewText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaybackEventLogSection(
    formatTimestamp: (Long) -> String,
    onExport: (() -> Unit)? = null,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    statusMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    val events by PlaybackEventLog.events.updates.collectAsState(emptyList())
    val previews by produceState(emptyList<Pair<Long, String>>(), events, formatTimestamp) {
        value =
            withContext(Dispatchers.Default) {
                events.takeLast(300).map { it.id to it.previewText(formatTimestamp) }
            }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.devtools_playback_log_title),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 32.dp, bottom = 4.dp),
        )
        Text(
            text = stringResource(Res.string.playback_event_log_raw_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 24.dp, bottom = 4.dp),
        )
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.Black,
        ) {
            Text(
                text = stringResource(Res.string.devtools_playback_log_count, events.size),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onExport != null) {
                SplitButtonLayout(
                    leadingButton = {
                        SplitButtonDefaults.TonalLeadingButton(onClick = onExport, enabled = events.isNotEmpty()) {
                            Icon(MetrolistIcons.Default.Share, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.share))
                        }
                    },
                    trailingButton = {
                        SplitButtonDefaults.TrailingButton(
                            onClick = onCopy,
                            enabled = events.isNotEmpty(),
                            shapes = SplitButtonDefaults.trailingButtonShapesFor(SplitButtonDefaults.MediumContainerHeight),
                        ) {
                            Icon(MetrolistIcons.Default.ContentCopy, null, Modifier.size(20.dp))
                        }
                    },
                )
            } else {
                SplitButtonDefaults.TonalLeadingButton(onClick = onCopy, enabled = events.isNotEmpty()) {
                    Icon(MetrolistIcons.Default.ContentCopy, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.copy))
                }
            }
            FilledTonalIconButton(
                onClick = onClear,
                enabled = events.isNotEmpty(),
                colors =
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(MetrolistIcons.Default.Delete, contentDescription = stringResource(Res.string.clear))
            }
        }
        statusMessage?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp)) }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.Black,
        ) {
            DragScrollableColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (events.isEmpty()) {
                    item {
                        Text(
                            stringResource(Res.string.playback_event_log_raw_empty),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                } else {
                    items(previews, key = { it.first }) { (_, text) ->
                        Text(
                            text,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
    }
}
