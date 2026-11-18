package com.metrolist.shared.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.playback_error_track_unavailable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.getString

object UnavailableSongTaps {
    internal val taps = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun tapped() {
        taps.tryEmit(Unit)
    }
}

@Composable
fun UnavailableSongSnackbar(snackbarHostState: SnackbarHostState) {
    LaunchedEffect(snackbarHostState) {
        UnavailableSongTaps.taps.collectLatest {
            snackbarHostState.showSnackbar(getString(Res.string.playback_error_track_unavailable))
        }
    }
}
