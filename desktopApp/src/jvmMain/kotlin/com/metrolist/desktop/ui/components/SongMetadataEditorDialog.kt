package com.metrolist.desktop.ui.components

import androidx.compose.runtime.Composable
import com.metrolist.desktop.state.app.AppState
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.editSongMetadata
import com.metrolist.shared.model.resetSongMetadata
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.ui.menu.EditSongDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.koin.core.context.GlobalContext

@Composable
internal fun SongMetadataEditorDialog(
    song: SongItem,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val effectiveSong = SongMetadataState.apply(song)
    EditSongDialog(
        title = effectiveSong.title,
        artist =
            effectiveSong.artists
                .firstOrNull()
                ?.name
                .orEmpty(),
        onSave = { title, artist ->
            AppState.scope
                .async(Dispatchers.IO) {
                    editSongMetadata(GlobalContext.get().get<MusicDatabase>(), song, title, artist)
                }.await()
        },
        onSaved = onSaved,
        onDismiss = onDismiss,
        onReset = {
            AppState.scope
                .async(Dispatchers.IO) {
                    resetSongMetadata(GlobalContext.get().get<MusicDatabase>(), song.id)
                }.await()
        },
    )
}
