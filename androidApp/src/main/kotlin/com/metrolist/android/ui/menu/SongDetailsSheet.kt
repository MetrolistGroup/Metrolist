/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.menu

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.android.ui.component.AdaptiveModalBottomSheet
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.components.rememberModalSheetState
import com.metrolist.shared.ui.menu.SongDetailsContent
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.shared.youtube.playback.PlaybackResource
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailsSheet(
    song: SongItem?,
    playbackResource: PlaybackResource? = null,
    volume: Float? = null,
    onDismiss: () -> Unit,
) {
    val database: MusicDatabase = koinInject()
    val playerConnection: PlayerConnection = koinInject()
    val currentResource by playerConnection.currentPlaybackResource.collectAsState()
    val currentVolume by playerConnection.volume.collectAsState()
    val sheetState = rememberModalSheetState(SheetValue.Hidden)
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = ComponentShapes.BottomSheetShape) {
        SongDetailsContent(song, database, playbackResource ?: currentResource, volume ?: currentVolume)
    }
}
