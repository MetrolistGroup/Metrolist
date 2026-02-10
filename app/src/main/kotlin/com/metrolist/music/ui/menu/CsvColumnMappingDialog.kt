/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metrolist.music.R
import com.metrolist.music.viewmodels.ConvertedSongLog
import com.metrolist.music.viewmodels.CsvImportState

@Composable
fun CsvColumnMappingDialog(
    isVisible: Boolean,
    csvState: CsvImportState,
    onDismiss: () -> Unit,
    onConfirm: (CsvImportState) -> Unit,
) {
    if (!isVisible) return

    var artistColumnIndex by remember { mutableIntStateOf(csvState.artistColumnIndex) }
    var titleColumnIndex by remember { mutableIntStateOf(csvState.titleColumnIndex) }
    var urlColumnIndex by remember { mutableIntStateOf(csvState.urlColumnIndex) }
    var hasHeader by remember { mutableStateOf(csvState.hasHeader) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.map_csv_columns),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Preview rows
            if (csvState.previewRows.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Preview",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        csvState.previewRows.take(5).forEachIndexed { rowIndex, row ->
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                row.forEachIndexed { colIndex, cell ->
                                    Box(
                                        modifier = Modifier
                                            .width(120.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                when {
                                                    rowIndex == 0 && hasHeader -> MaterialTheme.colorScheme.primaryContainer
                                                    colIndex == artistColumnIndex -> MaterialTheme.colorScheme.tertiaryContainer
                                                    colIndex == titleColumnIndex -> MaterialTheme.colorScheme.secondaryContainer
                                                    colIndex == urlColumnIndex && urlColumnIndex >= 0 -> MaterialTheme.colorScheme.tertiaryContainer
                                                    else -> MaterialTheme.colorScheme.background
                                                },
                                            )
                                            .padding(6.dp),
                                    ) {
                                        Text(
                                            text = cell.take(18),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Header checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = hasHeader,
                    onCheckedChange = { hasHeader = it },
                )
                Text(
                    text = stringResource(R.string.first_row_is_header),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Column selectors
            ColumnSelector(
                label = stringResource(R.string.artist_name_column),
                selectedIndex = artistColumnIndex,
                maxColumns = csvState.previewRows.firstOrNull()?.size ?: 0,
                onSelected = { artistColumnIndex = it },
            )

            ColumnSelector(
                label = stringResource(R.string.song_title_column),
                selectedIndex = titleColumnIndex,
                maxColumns = csvState.previewRows.firstOrNull()?.size ?: 0,
                onSelected = { titleColumnIndex = it },
            )

            ColumnSelector(
                label = stringResource(R.string.youtube_url_column),
                selectedIndex = urlColumnIndex,
                maxColumns = csvState.previewRows.firstOrNull()?.size ?: 0,
                allowNone = true,
                onSelected = { urlColumnIndex = it },
            )

            // Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        onConfirm(
                            CsvImportState(
                                previewRows = csvState.previewRows,
                                artistColumnIndex = artistColumnIndex,
                                titleColumnIndex = titleColumnIndex,
                                urlColumnIndex = urlColumnIndex,
                                hasHeader = hasHeader,
                            )
                        )
                    },
                ) {
                    Text(stringResource(R.string.continue_action))
                }
            }
        }
    }
}

@Composable
private fun ColumnSelector(
    label: String,
    selectedIndex: Int,
    maxColumns: Int,
    allowNone: Boolean = false,
    onSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (allowNone) {
                if (selectedIndex == -1) {
                    Button(
                        onClick = { onSelected(-1) },
                        modifier = Modifier.height(36.dp),
                    ) {
                        Text(stringResource(R.string.none), style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelected(-1) },
                        modifier = Modifier.height(36.dp),
                    ) {
                        Text(stringResource(R.string.none), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            repeat(maxColumns) { index ->
                if (selectedIndex == index) {
                    Button(
                        onClick = { onSelected(index) },
                        modifier = Modifier.height(36.dp),
                    ) {
                        Text(
                            stringResource(R.string.column_label, index + 1),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelected(index) },
                        modifier = Modifier.height(36.dp),
                    ) {
                        Text(
                            stringResource(R.string.column_label, index + 1),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CsvImportProgressDialog(
    isVisible: Boolean,
    progress: Int,
    recentLogs: List<ConvertedSongLog>,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.importing_csv),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )

            Text(
                text = stringResource(R.string.percentage_format, progress),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (recentLogs.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.recently_converted),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    recentLogs.forEach { log ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = log.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = log.artists,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
