package com.metrolist.shared.ui.mobile

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.components.ModalBottomSheet
import com.metrolist.shared.ui.menu.SongDetailsContent
import com.metrolist.shared.ui.playback.PlaybackUiState
import com.metrolist.shared.ui.theme.ComponentShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileSongDetailsSheet(
    state: PlaybackUiState,
    database: MusicDatabase,
    onDismiss: () -> Unit,
    song: SongItem? = state.currentSong,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = ComponentShapes.BottomSheetShape) {
        SongDetailsContent(
            song = song,
            database = database,
            playbackResource = state.currentPlaybackResource,
            durationMs = state.durationMs.takeIf { it > 0 && state.currentSong?.id == song?.id },
        )
    }
}
