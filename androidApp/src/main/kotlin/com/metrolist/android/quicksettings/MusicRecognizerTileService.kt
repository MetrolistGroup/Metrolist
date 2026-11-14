package com.metrolist.android.quicksettings

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import com.metrolist.android.R
import com.metrolist.android.recognition.MusicRecognitionService
import com.metrolist.android.recognition.RecognitionLaunchActivity
import com.metrolist.shared.recognition.RecognitionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MusicRecognizerTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        statusJob?.cancel()
        statusJob =
            scope.launch {
                MusicRecognitionService.status.collect { status ->
                    updateState(status)
                }
            }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateState(MusicRecognitionService.status.value)
    }

    private fun updateState(status: RecognitionStatus) {
        qsTile?.apply {
            val busy = status is RecognitionStatus.Listening || status is RecognitionStatus.Processing
            label =
                getString(
                    when (status) {
                        RecognitionStatus.Listening -> R.string.listening
                        RecognitionStatus.Processing -> R.string.processing
                        else -> R.string.recognize_music
                    },
                )
            icon = Icon.createWithResource(this@MusicRecognizerTileService, R.drawable.ic_widget_mic)
            state = if (busy) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onStopListening() {
        statusJob?.cancel()
        statusJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val intent =
            Intent(this, RecognitionLaunchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
        TileServiceCompat.startActivityAndCollapse(
            this,
            PendingIntentActivityWrapper(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                false,
            ),
        )
    }
}
