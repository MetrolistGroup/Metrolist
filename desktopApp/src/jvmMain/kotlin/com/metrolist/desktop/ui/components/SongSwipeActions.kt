package com.metrolist.desktop.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.launch
import com.metrolist.shared.ui.components.SongSwipeActions as SharedSongSwipeActions

@Composable
fun SongSwipeActions(
    song: SongItem,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    SharedSongSwipeActions(
        song = song,
        onFeedback = { message ->
            AppState.snackbarHostState.currentSnackbarData?.dismiss()
            AppState.scope.launch {
                AppState.snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            }
        },
        enabled = enabled && !PlayerState.isListenTogetherGuest(),
        content = content,
    )
}
