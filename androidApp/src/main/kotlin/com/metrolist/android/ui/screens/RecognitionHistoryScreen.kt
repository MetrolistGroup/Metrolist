@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.ui.screens.settings.SettingsSubScreen
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.clear
import com.metrolist.shared.generated.resources.clear_recognition_history_description
import com.metrolist.shared.generated.resources.clear_recognition_history_question
import com.metrolist.shared.generated.resources.recognition_history
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
fun RecognitionHistoryScreen(onNavigateBack: () -> Unit) {
    val database: MusicDatabase = koinInject()
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var confirmClearHistory by remember { mutableStateOf(false) }
    val history by remember(query) {
        if (query.isBlank()) {
            database.dao().recognitionHistory()
        } else {
            database.dao().searchRecognitionHistory(query.trim())
        }
    }.collectAsState(initial = emptyList())

    SettingsSubScreen(
        title = stringResource(Res.string.recognition_history),
        onNavigateBack = onNavigateBack,
        actions = {
            IconButton(onClick = { confirmClearHistory = true }, enabled = history.isNotEmpty(), shapes = IconButtonDefaults.shapes()) {
                Icon(
                    imageVector = MetrolistIcons.Outlined.Delete,
                    contentDescription = stringResource(Res.string.clear),
                )
            }
        },
        contentScrollable = false,
    ) {
        RecognitionHistoryContent(
            history = history,
            query = query,
            onQueryChange = { query = it },
            onPlay = { entry ->
                entry.toSongItem()?.let { playerConnection?.playSong(it, "") }
            },
            onDelete = { entry ->
                scope.launch(Dispatchers.IO) {
                    database.dao().deleteRecognitionHistoryById(entry.id)
                }
            },
        )
    }

    PopupVisibility(visible = confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text(stringResource(Res.string.clear_recognition_history_question)) },
            text = { Text(stringResource(Res.string.clear_recognition_history_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearHistory = false
                        scope.launch(Dispatchers.IO) { database.dao().clearRecognitionHistory() }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}
