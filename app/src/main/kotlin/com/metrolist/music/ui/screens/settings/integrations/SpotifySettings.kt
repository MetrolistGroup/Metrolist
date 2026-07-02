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

/**
 * Importación de librería de Spotify — sin usar la API de Spotify.
 *
 * La Web API de Spotify ahora exige que el dueño de la app tenga Premium y limita
 * el Development Mode a unos pocos usuarios en allowlist, así que una integración
 * propia no puede servir a todos. En su lugar nos apoyamos en Exportify (que corre
 * en Extended Quota Mode de Spotify y por tanto está exento): el usuario exporta su
 * librería a CSV allí y luego lo importa aquí. El matcher de CSV que ya existe en
 * Metrolist hace el resto.
 *
 * Como el CSV de Exportify tiene cabecera fija, auto-detectamos las columnas de
 * título/artista y nos saltamos el diálogo de mapeo manual.
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

    // Encuentra los índices de columna título/artista en la cabecera de Exportify.
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
                // Lee solo la cabecera para auto-mapear columnas.
                val header = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readLine()
                    }
                }.getOrNull()

                val mapping = header?.let { detectColumns(parseCsvHeader(it)) }

                if (mapping == null) {
                    statusText = context.getString(R.string.spotify_csv_unrecognized)
                    return@launch
                }

                val result = runCatching {
                    viewModel.importPlaylistFromCsv(
                        context = context,
                        uri = uri,
                        columnMapping = mapping,
                        onProgress = { },
                    )
                }.onFailure { reportException(it) }.getOrNull().orEmpty()

                importedSongs.clear()
                importedSongs.addAll(result)
                if (importedSongs.isNotEmpty()) {
                    showAddDialog = true
                } else {
                    statusText = context.getString(R.string.spotify_csv_empty)
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
        initialTextFieldValue = "Spotify import",
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

/** Divisor mínimo de cabecera CSV que respeta las celdas entrecomilladas. */
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