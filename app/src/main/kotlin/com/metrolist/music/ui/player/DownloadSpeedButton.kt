package com.metrolist.music.ui.player

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.constants.DownloadSpeedKey
import com.metrolist.music.ui.menu.DownloadEffectsDialog
import com.metrolist.music.utils.rememberPreference

@Composable
fun DownloadSpeedButton() {
    val connection = LocalPlayerConnection.current ?: return
    val casting by connection.service.castConnectionHandler?.isCasting?.collectAsStateWithLifecycle()
        ?: androidx.compose.runtime.remember { mutableStateOf(false) }
    if (casting) return
    val speed by rememberPreference(DownloadSpeedKey, 1f)
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) {
        Text("${speed}×")
    }
    if (expanded) DownloadEffectsDialog(onDismiss = { expanded = false })
}
