@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.menu

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.export_as_format
import com.metrolist.shared.generated.resources.export_collection
import com.metrolist.shared.generated.resources.export_failed
import com.metrolist.shared.generated.resources.export_saved
import com.metrolist.shared.generated.resources.exporting_collection
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.BasicAlertDialog
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.utils.CollectionExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun CollectionExportDialog(
    title: String,
    onDismiss: () -> Unit,
    loadSongs: suspend () -> List<SongItem>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    fun save(
        format: CollectionExportFormat,
        uri: Uri,
    ) {
        scope.launch {
            exporting = true
            val success =
                runCatching {
                    val songs = loadSongs()
                    val text = withContext(Dispatchers.Default) { format.render(songs) }
                    withContext(Dispatchers.IO) {
                        context.contentResolver
                            .openOutputStream(uri)
                            ?.bufferedWriter()
                            ?.use { it.write(text) }
                            ?: error("Could not open export file")
                    }
                }.isSuccess
            exporting = false
            Toast
                .makeText(
                    context,
                    getString(if (success) Res.string.export_saved else Res.string.export_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            onDismiss()
        }
    }

    // One launcher per format: the document MIME type is fixed when the launcher is created.
    val saveLaunchers =
        CollectionExportFormat.entries.associateWith { format ->
            rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri ->
                if (uri != null) save(format, uri)
            }
        }

    if (exporting) {
        BasicAlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Surface(
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularWavyProgressIndicator()
                    Text(stringResource(Res.string.exporting_collection, title), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(MetrolistIcons.Outlined.Share, contentDescription = null) },
        title = { Text(stringResource(Res.string.export_collection, title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                CollectionExportFormat.entries.forEach { format ->
                    Text(
                        text = stringResource(Res.string.export_as_format, format.extension),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { saveLaunchers.getValue(format).launch("${title.toSafeFilename()}.${format.extension}") }
                                .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

private fun String.toSafeFilename(): String = replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "playlist" }
