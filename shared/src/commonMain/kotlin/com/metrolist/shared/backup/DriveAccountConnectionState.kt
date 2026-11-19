package com.metrolist.shared.backup

import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow

object DriveAccountConnectionState {
    val connected = MutableStateFlow(Settings.shared.driveBrokerSession.isNotBlank())

    fun update(sessionToken: String) {
        connected.value = sessionToken.isNotBlank()
    }
}
