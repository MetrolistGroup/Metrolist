@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.menu

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album
import com.metrolist.shared.generated.resources.artist
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.continue_action
import com.metrolist.shared.generated.resources.csv_column_label
import com.metrolist.shared.generated.resources.csv_first_row_header
import com.metrolist.shared.generated.resources.csv_mapping_hint
import com.metrolist.shared.generated.resources.csv_mapping_title
import com.metrolist.shared.generated.resources.csv_named_column
import com.metrolist.shared.generated.resources.csv_url_column
import com.metrolist.shared.generated.resources.none
import com.metrolist.shared.generated.resources.preview
import com.metrolist.shared.generated.resources.song_title
import com.metrolist.shared.playlistimport.CsvColumnMapping
import com.metrolist.shared.playlistimport.CsvPlaylist
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ExpressiveFilterChip
import com.metrolist.shared.ui.utils.carouselScroll
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import org.jetbrains.compose.resources.stringResource

@Composable
fun CsvColumnMappingDialog(
    csv: CsvPlaylist,
    onDismiss: () -> Unit,
    onConfirm: (CsvColumnMapping) -> Unit,
    initialMapping: CsvColumnMapping = csv.guessMapping(),
) {
    var mapping by remember(csv) { mutableStateOf(initialMapping) }
    var submitting by remember(csv) { mutableStateOf(false) }
    val previewScroll = rememberScrollState()
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(Res.string.csv_mapping_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScrollWithDrag(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(Res.string.preview), style = MaterialTheme.typography.labelLarge)
                Column(
                    modifier = Modifier.carouselScroll(previewScroll).horizontalScroll(previewScroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(csv.columnCount) { index ->
                            Text(
                                stringResource(Res.string.csv_column_label, index + 1),
                                modifier = Modifier.width(120.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    csv.rows.take(5).forEachIndexed { index, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(csv.columnCount) { column ->
                                Text(
                                    text = row.getOrNull(column).orEmpty(),
                                    modifier = Modifier.width(120.dp),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (index == 0 && mapping.hasHeader) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier =
                        Modifier.fillMaxWidth().toggleable(
                            value = mapping.hasHeader,
                            role = Role.Checkbox,
                            onValueChange = { mapping = mapping.copy(hasHeader = it) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = mapping.hasHeader, onCheckedChange = null)
                    Text(stringResource(Res.string.csv_first_row_header))
                }
                Text(stringResource(Res.string.csv_mapping_hint), style = MaterialTheme.typography.bodySmall)
                CsvColumnSelector(stringResource(Res.string.song_title), mapping.title, csv, mapping.hasHeader) {
                    mapping = mapping.copy(title = it)
                }
                CsvColumnSelector(stringResource(Res.string.artist), mapping.artist, csv, mapping.hasHeader) {
                    mapping = mapping.copy(artist = it)
                }
                CsvColumnSelector(stringResource(Res.string.album), mapping.album, csv, mapping.hasHeader) {
                    mapping = mapping.copy(album = it)
                }
                CsvColumnSelector(stringResource(Res.string.csv_url_column), mapping.url, csv, mapping.hasHeader) {
                    mapping = mapping.copy(url = it)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && mapping.isValid(csv.columnCount) && csv.rows.size > if (mapping.hasHeader) 1 else 0,
                onClick = {
                    submitting = true
                    onConfirm(mapping)
                },
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.continue_action)) }
        },
        dismissButton = {
            TextButton(
                enabled = !submitting,
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

@Composable
private fun CsvColumnSelector(
    label: String,
    selectedIndex: Int,
    csv: CsvPlaylist,
    hasHeader: Boolean,
    onSelected: (Int) -> Unit,
) {
    val scrollState = rememberScrollState()
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.carouselScroll(scrollState).horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (index in -1 until csv.columnCount) {
                ExpressiveFilterChip(
                    selected = selectedIndex == index,
                    onClick = { onSelected(index) },
                    label = {
                        Text(
                            if (index == -1) {
                                stringResource(Res.string.none)
                            } else {
                                val header =
                                    csv.rows
                                        .firstOrNull()
                                        ?.getOrNull(index)
                                        ?.takeIf { hasHeader && it.isNotBlank() }
                                if (header == null) {
                                    stringResource(Res.string.csv_column_label, index + 1)
                                } else {
                                    stringResource(Res.string.csv_named_column, index + 1, header)
                                }
                            },
                            maxLines = 1,
                        )
                    },
                )
            }
        }
    }
}
