/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AndroidAutoSearchLocalLimitKey
import com.metrolist.music.constants.AndroidAutoTargetPlaylistKey
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidAutoSettings(
    navController: NavController,
) {
    val database = LocalDatabase.current

    val userPlaylists by remember {
        database.playlistsByCreateDateAsc().map { list -> list.map { it.playlist } }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val (targetPlaylist, onTargetPlaylistChange) = rememberPreference(
        key = AndroidAutoTargetPlaylistKey,
        defaultValue = MediaSessionConstants.TARGET_PLAYLIST_AUTO
    )

    val (androidAutoSearchLocalLimit, onAndroidAutoSearchLocalLimitChange) = rememberPreference(
        AndroidAutoSearchLocalLimitKey,
        defaultValue = 75
    )

    val playlistOptions = listOf(MediaSessionConstants.TARGET_PLAYLIST_AUTO) +
            userPlaylists.map { it.id }

    val playlistLabels: @Composable (String) -> String = { id ->
        if (id == MediaSessionConstants.TARGET_PLAYLIST_AUTO) {
            stringResource(R.string.android_auto_target_playlist_auto)
        } else {
            userPlaylists.find { it.id == id }?.name ?: id
        }
    }

    Column(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // Quick-add destination playlist
        var showTargetPlaylistDialog by remember { mutableStateOf(false) }

        if (showTargetPlaylistDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showTargetPlaylistDialog = false },
                title = { Text(stringResource(R.string.android_auto_target_playlist)) },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(playlistOptions) { value ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showTargetPlaylistDialog = false
                                        onTargetPlaylistChange(value)
                                    }
                                    .padding(vertical = 12.dp),
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = value == targetPlaylist,
                                    onClick = null,
                                )
                                Text(
                                    text = playlistLabels(value),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showTargetPlaylistDialog = false }
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            )
        }
        
        Material3SettingsGroup(
            title = stringResource(R.string.android_auto_target_playlist),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.playlist_add),
                    title = { Text(stringResource(R.string.android_auto_target_playlist)) },
                    description = { Text(playlistLabels(targetPlaylist)) },
                    onClick = { showTargetPlaylistDialog = true }
                )
            )
        )

        Spacer(Modifier.height(27.dp))

        // Search options
        Material3SettingsGroup(
            title = stringResource(R.string.android_auto_search_options),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.manage_search),
                    title = { Text(stringResource(R.string.android_auto_search_local_songs_limit)) },
                    description = {
                        val limitValues =
                            remember { listOf(10, 25, 50, 75, 100, 150, 200, -1) }
                        Column {
                            Text(stringResource(R.string.android_auto_search_local_songs_limit_desc))
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text =
                                    when (androidAutoSearchLocalLimit) {
                                        -1 -> stringResource(R.string.unlimited)
                                        else -> "$androidAutoSearchLocalLimit ${stringResource(R.string.songs)}"
                                    },
                            )
                            Slider(
                                value = limitValues.indexOf(androidAutoSearchLocalLimit).toFloat(),
                                enabled = true,
                                onValueChange = {
                                    val newValue = limitValues[it.roundToInt()]
                                    onAndroidAutoSearchLocalLimitChange(newValue)
                                },
                                steps = limitValues.size - 2,
                                valueRange = 0f..(limitValues.size - 1).toFloat(),
                            )
                        }
                    }
                )
            )
        )

        Spacer(Modifier.height(27.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.android_auto)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}
