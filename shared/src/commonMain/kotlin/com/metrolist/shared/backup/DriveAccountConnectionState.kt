package com.metrolist.shared.backup

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow

object DriveAccountConnectionState {
    val connected = MutableStateFlow(Settings.shared.driveBrokerSession.isNotBlank())
    val reauthorizationRequired = MutableStateFlow(false)
    val signInFailed = MutableStateFlow(false)
    val signInInProgress = MutableStateFlow(false)

    fun refresh() {
        connected.value = Settings.shared.driveBrokerSession.isNotBlank()
    }

    fun update(sessionToken: String) {
        connected.value = sessionToken.isNotBlank()
        if (sessionToken.isNotBlank()) {
            reauthorizationRequired.value = false
            signInFailed.value = false
            signInInProgress.value = false
        }
    }

    fun clearSignInFailure() {
        signInFailed.value = false
    }

    fun reportSignInFailure() {
        signInFailed.value = true
    }

    fun clearIfCurrent(sessionToken: String?): Boolean {
        if (sessionToken != null && (sessionToken.isBlank() || Settings.shared.driveBrokerSession != sessionToken)) return false
        clearConnectedAccount()
        return true
    }

    fun requireReauthorization(sessionToken: String): Boolean {
        if (!clearIfCurrent(sessionToken)) return false
        reauthorizationRequired.value = true
        return true
    }

    fun recordCleanupFailure() {
        val settings = Settings.shared
        settings.driveCleanupPending = true
        runCatching { settings.flush() }
            .onFailure { error -> Logger.w("DriveBackup", "Could not persist cleanup warning", error) }
    }

    fun clearCleanupFailure() {
        val settings = Settings.shared
        settings.driveCleanupPending = false
        runCatching { settings.flush() }
            .onFailure { error -> Logger.w("DriveBackup", "Could not clear cleanup warning", error) }
    }

    private fun clearConnectedAccount() {
        val settings = Settings.shared
        settings.driveBrokerSession = ""
        connected.value = false
        signInFailed.value = false
        signInInProgress.value = false
        if (AutoBackupDestination.fromSetting(settings.autoBackupDestination) == AutoBackupDestination.GOOGLE_DRIVE) {
            settings.autoBackupDestination = AutoBackupDestination.LOCAL.name
        }
        settings.driveCleanupPending = false
        settings.flush()
    }
}
