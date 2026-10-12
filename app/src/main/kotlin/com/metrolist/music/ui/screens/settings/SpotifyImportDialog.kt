package com.metrolist.music.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.metrolist.music.R
import com.metrolist.music.viewmodels.SpotifyImportViewModel

@Composable
fun SpotifyImportDialog(onDismiss: () -> Unit, viewModel: SpotifyImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var clientId by rememberSaveable { mutableStateOf(viewModel.clientId) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.startFile(uri)
    }
    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        title = { Text(stringResource(R.string.spotify_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.spotify_import_description))
                Text(stringResource(R.string.spotify_free_description))
                Button(enabled = !state.running, onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.spotify_import_file))
                }
                TextButton(onClick = { uriHandler.openUri("https://www.spotify.com/account/privacy/") }) {
                    Text(stringResource(R.string.spotify_request_export))
                }
                if (showSetup) {
                    Text(stringResource(R.string.spotify_setup))
                    OutlinedTextField(value = clientId, onValueChange = { clientId = it }, enabled = !state.running,
                        label = { Text(stringResource(R.string.spotify_client_id)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { uriHandler.openUri("https://developer.spotify.com/dashboard") }) {
                        Text(stringResource(R.string.spotify_dashboard))
                    }
                }
                if (state.message.isNotEmpty()) Text(state.message)
                if (state.running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (state.current.isNotEmpty()) Text(state.current)
                    Text(stringResource(R.string.spotify_keep_open))
                }
                if (state.total > 0 || state.processed > 0) {
                    Text(stringResource(R.string.spotify_progress, state.processed, state.total, state.added, state.existing, state.skipped))
                }
                if (state.unmatched.isNotEmpty()) {
                    Text(stringResource(R.string.spotify_unmatched), style = MaterialTheme.typography.titleSmall)
                    Text(state.unmatched.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
                if (!state.running && !showSetup) {
                    TextButton(onClick = { showSetup = true }) { Text(stringResource(R.string.spotify_change_connection)) }
                }
                if (!state.running && viewModel.clientId.isNotBlank()) {
                    TextButton(onClick = { viewModel.disconnect(); clientId = ""; showSetup = true }) {
                        Text(stringResource(R.string.spotify_disconnect))
                    }
                }
            }
        },
        confirmButton = {
            if (clientId.isNotBlank() || showSetup) {
            TextButton(enabled = !state.running && clientId.isNotBlank(), onClick = {
                viewModel.start(clientId)
                if (Regex("[a-fA-F0-9]{32}").matches(clientId.trim())) showSetup = false
            }) { Text(stringResource(R.string.spotify_import_action)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (state.running) viewModel.cancel() else onDismiss() }) {
                Text(stringResource(if (state.running) R.string.cancel else R.string.close))
            }
        },
    )
}
