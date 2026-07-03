/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.menu.AddToPlaylistDialogOnline
import com.metrolist.music.ui.menu.LoadingScreen
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.reportException
import com.metrolist.music.viewmodels.BackupRestoreViewModel
import com.metrolist.music.viewmodels.CsvImportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spotify library import — without using the Spotify API.
 *
 * The Spotify Web API now requires the app owner to have Premium and limits
 * Development Mode to a few allowlisted users, so a standalone integration
 * cannot serve everyone. Instead we rely on Exportify (which runs in Spotify's
 * Extended Quota Mode and is therefore exempt): the user exports their library
 * to CSV there and then imports it here. The CSV matcher that already exists in
 * Metrolist does the rest.
 *
 * Since the Exportify CSV has a fixed header, we auto-detect the title/artist
 * columns and skip the manual mapping dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifySettings(
    navController: NavController,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val importedSongs = remember { mutableStateListOf<Song>() }
    var showAddDialog by remember { mutableStateOf(false) }
    var isProgressStarted by remember { mutableStateOf(false) }
    var progressPercentage by remember { mutableStateOf(0) }
    var currentImportSong by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf<String?>(null) }

    // Find the title/artist column indices in the Exportify header.
    fun detectColumns(header: List<String>): CsvImportState? {
        fun idxOf(vararg names: String): Int =
            header.indexOfFirst { cell -> names.any { it.equals(cell.trim(), ignoreCase = true) } }

        val titleIdx = idxOf("Track Name", "Title")
        val artistIdx = idxOf("Artist Name(s)", "Artist Name", "Artist", "Artists")
        if (titleIdx < 0 || artistIdx < 0) return null

        return CsvImportState(
            titleColumnIndex = titleIdx,
            artistColumnIndex = artistIdx,
            hasHeader = true,
        )
    }

    val pickCsv =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch(Dispatchers.IO) {
                statusText = null
                // Read only the header to auto-map columns.
                val header = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readLine()
                    }
                }.getOrNull()

                val mapping = header?.let { detectColumns(parseCsvHeader(it)) }

                if (mapping == null) {
                    withContext(Dispatchers.Main) {
                        statusText = context.getString(R.string.spotify_csv_unrecognized)
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { isProgressStarted = true }
                val result = runCatching {
                    viewModel.importPlaylistFromCsv(
                        context = context,
                        uri = uri,
                        columnMapping = mapping,
                        onProgress = { progressPercentage = it },
                        onLogUpdate = { logs -> logs.firstOrNull()?.let { currentImportSong = it.title } },
                    )
                }
                withContext(Dispatchers.Main) { isProgressStarted = false }

                result
                    .onSuccess { songs ->
                        withContext(Dispatchers.Main) {
                            importedSongs.clear()
                            importedSongs.addAll(songs)
                            if (importedSongs.isNotEmpty()) {
                                showAddDialog = true
                            } else {
                                statusText = context.getString(R.string.spotify_csv_empty)
                            }
                        }
                    }
                    .onFailure {
                        reportException(it)
                        withContext(Dispatchers.Main) {
                            statusText = context.getString(R.string.spotify_csv_empty)
                        }
                    }
            }
        }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(stringResource(R.string.spotify_import_intro))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.spotify_import_steps))
        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://exportify.net")),
                )
            }.onFailure { reportException(it) }
        }) {
            Text(stringResource(R.string.spotify_open_exportify))
        }

        Spacer(Modifier.height(8.dp))

        Button(onClick = {
            pickCsv.launch(
                arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain"),
            )
        }) {
            Text(stringResource(R.string.spotify_select_csv))
        }

        statusText?.let {
            Spacer(Modifier.height(12.dp))
            Text(it)
        }
    }

    AddToPlaylistDialogOnline(
        isVisible = showAddDialog,
        allowSyncing = false,
        initialTextFieldValue = stringResource(R.string.spotify_integration),
        songs = importedSongs,
        onDismiss = { showAddDialog = false },
        onProgressStart = { isProgressStarted = it },
        onPercentageChange = { progressPercentage = it },
        onSongChange = { currentImportSong = it },
    )

    LoadingScreen(
        isVisible = isProgressStarted,
        value = progressPercentage,
        songTitle = currentImportSong,
    )

    TopAppBar(
        title = { Text(stringResource(R.string.spotify_integration)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

/** Minimal CSV header parser that respects quoted cells. */
private fun parseCsvHeader(line: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    for (c in line) {
        when {
            c == '"' -> inQuotes = !inQuotes
            c == ',' && !inQuotes -> {
                result.add(current.toString()); current.clear()
            }
            else -> current.append(c)
        }
    }
    result.add(current.toString())
    return result.map { it.trim().trim('"') }
}