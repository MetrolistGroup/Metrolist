/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.metrolist.desktop.state.content.SearchState
import com.metrolist.desktop.update.DesktopUpdateManager
import com.metrolist.desktop.update.GitHubDesktopRelease
import com.metrolist.desktop.update.desktopUpdateCheckIsDue
import com.metrolist.desktop.update.isNewerDesktopVersion
import com.metrolist.desktop.utils.AppLogger
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.api.artwork.AnimatedArtworkService
import com.metrolist.shared.api.artwork.DirectCanvasProvider
import com.metrolist.shared.discord.DiscordRpcManager
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.copied
import com.metrolist.shared.generated.resources.could_not_download_update_manager
import com.metrolist.shared.generated.resources.update_failed
import com.metrolist.shared.generated.resources.updates_available_title
import com.metrolist.shared.generated.resources.updates_title
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.utils.OsUtil
import com.metrolist.shared.utils.tryOpenUrl
import dev.nucleusframework.share.ShareException
import dev.nucleusframework.share.ShareSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

object AppState {
    val directCanvasProvider by lazy { DirectCanvasProvider(GlobalYouTube.client) }
    val animatedArtworkService by lazy { AnimatedArtworkService(GlobalYouTube.client) }
    internal val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    val snackbarHostState = SnackbarHostState()

    private var updateDownloadJob: Job? = null

    private val updateInitialization: Job =
        scope.launch {
            applyReleases(DesktopUpdateManager.cachedReleases())
            if (Settings.shared.checkForUpdates &&
                desktopUpdateCheckIsDue(Settings.shared.lastUpdateCheckAttemptTimestamp, System.currentTimeMillis())
            ) {
                checkForUpdates(notify = true)
            }
        }

    private suspend fun applyReleases(releases: List<GitHubDesktopRelease>) {
        UiState.updateReleases = releases
        val update = releases.firstOrNull { isNewerDesktopVersion(it.version, BuildConfig.APP_VERSION) }
        if (UiState.availableUpdate?.version != update?.version) UiState.updateDownloadError = null
        UiState.availableUpdate = update
        UiState.canInstallUpdate = UiState.availableUpdate?.let { DesktopUpdateManager.canInstall(it) } == true
    }

    fun checkForUpdates(notify: Boolean = false) {
        if (UiState.isCheckingForUpdates || UiState.isDownloadingUpdate || UiState.updateManagerStarted) return
        UiState.isCheckingForUpdates = true
        UiState.updateCheckFailed = false
        scope.launch {
            try {
                updateInitialization.join()
                DesktopUpdateManager.check().fold(
                    onSuccess = {
                        applyReleases(it)
                        UiState.hasCheckedForUpdates = true
                        if (notify) notifyUpdateAvailable()
                    },
                    onFailure = {
                        UiState.updateCheckFailed = true
                        AppLogger.warning("Updates", "Update check failed", it)
                    },
                )
            } finally {
                UiState.isCheckingForUpdates = false
            }
        }
    }

    private fun notifyUpdateAvailable() {
        val release = UiState.availableUpdate ?: return
        if (!Settings.shared.updateNotifications || Settings.shared.lastNotifiedUpdateVersion == release.version) return
        scope.launch {
            Settings.shared.lastNotifiedUpdateVersion = release.version
            val result =
                snackbarHostState.showSnackbar(
                    message = getString(Res.string.updates_available_title, release.version),
                    actionLabel = getString(Res.string.updates_title),
                )
            if (result == SnackbarResult.ActionPerformed) {
                UiState.selectedSettingsPage = SettingsPageId.UPDATES
                UiState.showSettings = true
            }
        }
    }

    fun copyToClipboard(text: String) {
        try {
            val sel = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            scope.launch { snackbarHostState.showSnackbar(getString(Res.string.copied)) }
        } catch (_: Exception) {
        }
    }

    fun shareLink(
        link: String,
        title: String,
    ) {
        copyToClipboard(link)
        if (OsUtil.isLinux) {
            return
        }
        scope.launch {
            try {
                ShareSheet.share {
                    this.title = title
                    url(link)
                }
            } catch (e: ShareException) {
                AppLogger.warning("Main", "${e.error}: ${e.message}")
            }
        }
    }

    fun installUpdate() {
        val release = UiState.availableUpdate ?: return
        if (UiState.isDownloadingUpdate || UiState.isCheckingForUpdates || UiState.updateManagerStarted) return
        UiState.isDownloadingUpdate = true
        UiState.updateDownloadError = null
        updateDownloadJob =
            scope.launch {
                try {
                    if (!DesktopUpdateManager.canInstall(release)) {
                        openUpdateRelease(release)
                        return@launch
                    }
                    DesktopUpdateManager
                        .downloadAndLaunch(release.version) {
                            withContext(Dispatchers.Main) { UiState.updateManagerStarted = true }
                        }.fold(
                            onSuccess = {},
                            onFailure = {
                                AppLogger.warning("Updates", "Update manager download failed", it)
                                UiState.updateDownloadError = getString(Res.string.could_not_download_update_manager)
                            },
                        )
                } finally {
                    UiState.isDownloadingUpdate = false
                }
            }
    }

    fun openUpdateRelease(release: GitHubDesktopRelease) {
        tryOpenUrl(release.url) { opened ->
            if (!opened) {
                scope.launch {
                    val message = getString(Res.string.update_failed)
                    UiState.updateDownloadError = message
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    fun cancelUpdateDownload() {
        updateDownloadJob?.cancel()
        DesktopUpdateManager.cancelDownload()
    }

    suspend fun release() {
        cancelUpdateDownload()
        SearchState.close()
        scope.coroutineContext[Job]?.cancelAndJoin()
        DiscordRpcManager
            .destroy()
        runCatching { GlobalYouTube.close() }
    }
}
