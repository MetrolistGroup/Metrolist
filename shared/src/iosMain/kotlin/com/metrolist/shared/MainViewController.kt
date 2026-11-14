@file:Suppress("FunctionName")

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.metrolist.shared.backup.BackupArchiveLimits
import com.metrolist.shared.backup.IosBackupRestoreManager
import com.metrolist.shared.backup.defaultBackupFilename
import com.metrolist.shared.crash.IosCrashRecovery
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.discord.IosDiscordPresenceEffect
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.backup_filename_prefix
import com.metrolist.shared.generated.resources.import_playlist
import com.metrolist.shared.listentogether.IosListenTogetherPlayback
import com.metrolist.shared.listentogether.ListenTogetherLogLevel
import com.metrolist.shared.listentogether.ListenTogetherLogger
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.listentogether.ListenTogetherPresentationText
import com.metrolist.shared.listentogether.ListenTogetherTrackResolver
import com.metrolist.shared.listentogether.toSongItem
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.playback.IosPlaybackController
import com.metrolist.shared.playback.IosPlayerPlatformServices
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.playlist.IosPlaylistCoverMapper
import com.metrolist.shared.playlist.migrateIosPlaylistCovers
import com.metrolist.shared.playlist.removeStoredPlaylistCover
import com.metrolist.shared.playlist.setStoredPlaylistCover
import com.metrolist.shared.playlist.storeIosPlaylistCover
import com.metrolist.shared.playlist.uploadStoredPlaylistCover
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.state.contentCountryCodes
import com.metrolist.shared.storage.IosAudioDownloadManager
import com.metrolist.shared.storage.ResizableImageDiskCache
import com.metrolist.shared.ui.crash.CrashLogsScreen
import com.metrolist.shared.ui.listentogether.listenTogetherPresentationMessages
import com.metrolist.shared.ui.mobile.IosLoginScreen
import com.metrolist.shared.ui.mobile.MobileApp
import com.metrolist.shared.ui.mobile.iosLyricsShareAction
import com.metrolist.shared.ui.mobile.pickIosPlaylistCover
import com.metrolist.shared.ui.mobile.rememberVoiceOverEnabled
import com.metrolist.shared.ui.onboarding.IosOnboarding
import com.metrolist.shared.ui.settings.BackupRestorePage
import com.metrolist.shared.ui.settings.BackupRestoreStatus
import com.metrolist.shared.ui.settings.IosNativeFiles
import com.metrolist.shared.ui.settings.IosPlaylistImportSettings
import com.metrolist.shared.ui.settings.IosStorageSettingsPage
import com.metrolist.shared.ui.settings.LocalSettingsPageBottomPadding
import com.metrolist.shared.ui.settings.PlaylistImportFile
import com.metrolist.shared.ui.settings.SettingsChoice
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.theme.MetrolistTheme
import com.metrolist.shared.ui.theme.parseThemeSeedColor
import com.metrolist.shared.update.IosUpdateNotice
import com.metrolist.shared.utils.ArtworkSizingInterceptor
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.formatHistoryMonth
import com.metrolist.shared.youtube.playback.IosPageBoundTokenHost
import com.metrolist.shared.youtube.playback.IosPlaybackNetworkMonitor
import com.metrolist.shared.youtube.playback.StreamResolver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.NSLocaleIdentifier
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localeIdentifier
import platform.Foundation.preferredLanguages
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.UIKit.UIViewController
import platform.darwin.NSObjectProtocol
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.TimeSource

private const val MAX_PLAYLIST_IMPORT_BYTES = 16L * 1024L * 1024L

private val oauthCallbackUrlChannel = Channel<String>(Channel.BUFFERED)
private val oauthCallbackUrls = oauthCallbackUrlChannel.receiveAsFlow()

fun handleOpenUrl(url: String) {
    oauthCallbackUrlChannel.trySend(url)
}

@OptIn(ExperimentalForeignApi::class)
@Composable
fun MetrolistIOSApp() {
    val scope = rememberCoroutineScope()
    val presenter = LocalUIViewController.current
    DisposableEffect(Unit) {
        val networkMonitor = IosPlaybackNetworkMonitor()
        networkMonitor.start()
        onDispose { networkMonitor.stop() }
    }
    val koin = remember { KoinPlatform.getKoin() }
    var databaseGeneration by remember { mutableIntStateOf(0) }
    var appGeneration by remember { mutableIntStateOf(0) }
    var showMobileApp by remember { mutableStateOf(true) }
    var showOnboarding by remember { mutableStateOf(!Settings.shared.iosOnboardingComplete) }
    var initialSettingsPage by remember { mutableStateOf<SettingsPageId?>(null) }
    var pendingMobileDispose by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    var sessionClosed by remember { mutableStateOf(false) }
    val latestPendingDispose by rememberUpdatedState(pendingMobileDispose)
    val database = remember(koin, databaseGeneration) { koin.get<MusicDatabase>() }
    LaunchedEffect(database) {
        try {
            migrateIosPlaylistCovers(database)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w("PlaylistCover", "Managed cover migration failed: ${error::class.simpleName}")
        }
    }
    val player = remember(koin, databaseGeneration) { koin.get<MusicPlayer>() }
    val appJob = remember(databaseGeneration) { SupervisorJob(scope.coroutineContext[Job]) }
    val appScope = remember(appJob) { CoroutineScope(scope.coroutineContext + appJob) }
    LaunchedEffect(database, appScope) {
        SongMetadataState.bind(database, appScope)
    }
    val downloadManager = remember(database, appScope) { IosAudioDownloadManager(database, appScope) }
    val playbackController =
        remember(player, appScope, database, downloadManager) {
            IosPlaybackController(
                player,
                koin.get<StreamResolver>(),
                database,
                appScope,
                audioStorage = downloadManager,
            )
        }
    IosDiscordPresenceEffect(playbackController.state, player.playbackSpeed)
    val listenTogetherClient = remember(appScope) { createHttpClient() }
    val listenTogetherMessages by rememberUpdatedState(listenTogetherPresentationMessages())
    val listenTogetherManager =
        remember(playbackController, appScope, listenTogetherClient) {
            val monotonicOrigin = TimeSource.Monotonic.markNow()
            val timestampFormatter =
                NSDateFormatter().apply {
                    timeStyle = NSDateFormatterMediumStyle
                    dateStyle = NSDateFormatterNoStyle
                }
            ListenTogetherManager(
                callerScope = appScope,
                httpClient = listenTogetherClient,
                playback = IosListenTogetherPlayback(playbackController, player, appScope),
                resolver =
                    ListenTogetherTrackResolver { track ->
                        try {
                            GlobalYouTubeRepository.instance.getSongMetadata(track.id) ?: track.toSongItem()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            track.toSongItem()
                        }
                    },
                monotonicClockMs = { monotonicOrigin.elapsedNow().inWholeMilliseconds },
                wallClockMs = { Clock.System.now().toEpochMilliseconds() },
                timestampFormatter = { timestampFormatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(it / 1000.0)) },
                presentationText = ListenTogetherPresentationText { listenTogetherMessages.getValue(it) },
                logger =
                    ListenTogetherLogger { level, message ->
                        when (level) {
                            ListenTogetherLogLevel.DEBUG -> Logger.d("ListenTogether", message)
                            ListenTogetherLogLevel.INFO -> Logger.i("ListenTogether", message)
                            ListenTogetherLogLevel.WARNING -> Logger.w("ListenTogether", message)
                            ListenTogetherLogLevel.ERROR -> Logger.e("ListenTogether", message)
                        }
                    },
            ).also(playbackController::bindListenTogether)
        }
    val backupManager = remember { IosBackupRestoreManager() }
    var backupStatus by remember { mutableStateOf<BackupRestoreStatus?>(null) }
    var backupFailure by remember { mutableStateOf<Throwable?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    var showPlaylistImporter by rememberSaveable { mutableStateOf(false) }
    val storageUsage by downloadManager.storageUsageUpdates.collectAsState()
    val cleanupSession by rememberUpdatedState {
        if (!sessionClosed) {
            listenTogetherManager.close()
            listenTogetherClient.close()
            downloadManager.close()
            appJob.cancel()
            player.release()
        }
    }
    DisposableEffect(Unit) {
        onDispose { cleanupSession() }
    }
    LaunchedEffect(downloadManager, databaseGeneration) {
        if (databaseGeneration > 0) downloadManager.resumeAll()
    }
    val localeChoices by produceState(emptyList<SettingsChoice>() to emptyList<SettingsChoice>()) {
        value = withContext(Dispatchers.Default) { iosContentLocaleChoices() }
    }
    val systemLocale =
        remember {
            val current = NSLocale.currentLocale
            val preferred =
                (NSLocale.preferredLanguages.firstOrNull() as? String)
                    ?.takeIf(String::isNotBlank)
                    ?: current.localeIdentifier
            preferred.replace('_', '-') to current.countryCode.orEmpty()
        }

    suspend fun closeDatabaseForRestore() {
        val disposed = CompletableDeferred<Unit>()
        pendingMobileDispose = disposed
        showMobileApp = false
        disposed.await()

        var shutdownFailure: Throwable? = null
        runCatching { listenTogetherManager.leaveRoomAndWait() }.onFailure { shutdownFailure = it }
        listenTogetherManager.close()
        listenTogetherClient.close()
        runCatching { downloadManager.pauseAll() }.onFailure { shutdownFailure = it }
        downloadManager.close()
        player.stop()
        appJob.cancelAndJoin()
        player.release()
        database.close()
        sessionClosed = true
        shutdownFailure?.let { throw it }
    }

    suspend fun reopenDatabaseAfterRestore() {
        MetrolistPlatform.reopenDatabaseAfterRestore()
        databaseGeneration++
        sessionClosed = false
        pendingMobileDispose = null
        showMobileApp = true
    }

    fun exportBackup() {
        if (backupBusy) return
        backupBusy = true
        backupFailure = null
        backupStatus = BackupRestoreStatus.BUSY
        scope.launch {
            val directory = NSTemporaryDirectory() + NSUUID().UUIDString
            val destination = "$directory/${defaultBackupFilename(getString(Res.string.backup_filename_prefix))}.zip"
            val result =
                if (NSFileManager.defaultManager.createDirectoryAtPath(
                        directory,
                        withIntermediateDirectories = false,
                        attributes = null,
                        error = null,
                    )
                ) {
                    backupManager.backup(destination)
                } else {
                    Result.failure(IllegalStateException("Could not create backup directory"))
                }
            if (result.isSuccess) {
                IosNativeFiles.shareFile(presenter, destination) { shared ->
                    backupBusy = false
                    backupStatus = if (shared) BackupRestoreStatus.BACKUP_CREATED else null
                    NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
                }
            } else {
                NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
                backupBusy = false
                backupStatus = BackupRestoreStatus.BACKUP_FAILED
            }
        }
    }

    fun importBackup() {
        if (backupBusy) return
        backupBusy = true
        backupFailure = null
        backupStatus = BackupRestoreStatus.BUSY
        IosNativeFiles.pickDocument(presenter, listOf("public.data")) { url ->
            if (url == null) {
                backupBusy = false
                backupStatus = null
                return@pickDocument
            }
            scope.launch {
                var copiedPath: String? = null
                try {
                    val sourcePath =
                        withContext(Dispatchers.IO) {
                            IosNativeFiles.copyPickedFile(
                                url,
                                BackupArchiveLimits.TOTAL_UNCOMPRESSED_BYTES + 2L * 1024L * 1024L,
                            )
                        }
                    copiedPath = sourcePath
                    val generationBeforeRestore = databaseGeneration
                    val result =
                        backupManager.restore(
                            sourcePath = sourcePath,
                            closeDatabase = ::closeDatabaseForRestore,
                            reopenDatabase = { reopenDatabaseAfterRestore() },
                        )
                    if (sessionClosed) reopenDatabaseAfterRestore()
                    if (databaseGeneration == generationBeforeRestore && result.isSuccess) {
                        MetrolistPlatform.refreshRestoredSettings()
                        appGeneration++
                    }
                    backupFailure = result.exceptionOrNull()
                    backupStatus = if (result.isSuccess) null else BackupRestoreStatus.RESTORE_FAILED
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    backupFailure = error
                    if (sessionClosed) runCatching { reopenDatabaseAfterRestore() }
                    backupStatus = BackupRestoreStatus.RESTORE_FAILED
                } finally {
                    copiedPath?.let { path ->
                        withContext(Dispatchers.IO) {
                            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
                        }
                    }
                    backupBusy = false
                }
            }
        }
    }

    suspend fun pickPlaylistFile(): PlaylistImportFile? {
        val url =
            suspendCancellableCoroutine<NSURL?> { continuation ->
                IosNativeFiles.pickDocument(presenter, listOf("public.data")) { picked ->
                    if (continuation.isActive) continuation.resume(picked)
                }
                continuation.invokeOnCancellation { IosNativeFiles.cancelDocumentPicker() }
            } ?: return null
        val name = requireNotNull(url.lastPathComponent)
        val bytes = withContext(Dispatchers.IO) { IosNativeFiles.readPickedFile(url, MAX_PLAYLIST_IMPORT_BYTES) }
        return PlaylistImportFile(name, bytes)
    }

    Box(Modifier.fillMaxSize()) {
        if (showOnboarding) {
            Settings.shared.systemInDarkTheme = isSystemInDarkTheme()
            MetrolistTheme(
                seedColor = parseThemeSeedColor(Settings.shared.selectedThemeColor),
                darkTheme = Settings.shared.getCurrentThemeIsDark(),
                pureBlack = Settings.shared.isPureBlackTheme(),
            ) {
                IosOnboarding { consent, login, restore ->
                    Settings.shared.sentryEnabled = consent
                    Settings.shared.iosOnboardingComplete = true
                    showOnboarding = false
                    if (login) handleOpenUrl("metrolist://login")
                    if (restore) initialSettingsPage = SettingsPageId.BACKUP_RESTORE
                }
            }
        } else if (showMobileApp) {
            LifecycleResumeEffect(player) {
                player.setUiObserving(true)
                onPauseOrDispose { player.setUiObserving(false) }
            }
            DisposableEffect(Unit) {
                onDispose { latestPendingDispose?.complete(Unit) }
            }
            key(appGeneration) {
                MobileApp(
                    onExportStatistics = { IosNativeFiles.shareImage(presenter, it) },
                    onPickPlaylistCover = { pickIosPlaylistCover(presenter) },
                    onSavePlaylistCover = { playlist, bytes ->
                        try {
                            val repository = GlobalYouTubeRepository.instance
                            if (playlist.isLocal) {
                                val entity = checkNotNull(database.dao().findPlaylistEntity(playlist.id))
                                storeIosPlaylistCover(bytes, entity.customThumbnailUrl) { path ->
                                    if (path == null) {
                                        removeStoredPlaylistCover(database, repository, playlist.id).getOrThrow()
                                    } else {
                                        setStoredPlaylistCover(database, repository, playlist.id, path).getOrThrow()
                                    }
                                }
                            } else if (bytes == null) {
                                removeStoredPlaylistCover(database, repository, playlist.id, playlist).getOrThrow()
                            } else {
                                uploadStoredPlaylistCover(database, repository, playlist.id, bytes, playlist).getOrThrow()
                            }
                            Result.success(Unit)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                    },
                    database = database,
                    initialSettingsPage = initialSettingsPage,
                    playbackController = playbackController,
                    listenTogetherManager = listenTogetherManager,
                    playerServices = remember(presenter) { IosPlayerPlatformServices(presenter) },
                    lyricsShareAction = { iosLyricsShareAction(it, presenter) },
                    touchExplorationEnabled = rememberVoiceOverEnabled(),
                    resolveAndPlayVideo = playbackController::resolveAndPlayVideo,
                    systemLanguage = systemLocale.first,
                    systemCountry = systemLocale.second,
                    contentLanguageChoices = localeChoices.first,
                    contentCountryChoices = localeChoices.second,
                    appLanguageChoices = localeChoices.first,
                    storageTotalBytes = storageUsage?.totalBytes,
                    storageFreeBytes = storageUsage?.freeBytes,
                    onRemoveDownloads = { ids -> appScope.async { ids.count { downloadManager.removeDownload(it) } }.await() },
                    onDownloadSongs = { songs -> appScope.async { downloadManager.downloadAll(songs) }.await() },
                    onDownloadPlaylist = { playlistId -> appScope.async { downloadManager.downloadPlaylist(playlistId) }.await() },
                    onRemovePlaylistDownloads = { playlistId, ids ->
                        appScope
                            .async {
                                downloadManager.stopTrackingPlaylist(playlistId)
                                ids.count { downloadManager.removeDownload(it) }
                            }.await()
                    },
                    iosSettingsPageContent = { page, onDownloads ->
                        when (page) {
                            SettingsPageId.STORAGE -> {
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(
                                            rememberScrollState(),
                                        ).padding(bottom = LocalSettingsPageBottomPadding.current),
                                ) {
                                    IosStorageSettingsPage(downloadManager, onDownloads)
                                }
                            }

                            SettingsPageId.BACKUP_RESTORE -> {
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(
                                            rememberScrollState(),
                                        ).padding(bottom = LocalSettingsPageBottomPadding.current),
                                ) {
                                    if (showPlaylistImporter) {
                                        TextButton(onClick = { showPlaylistImporter = false }, shapes = ButtonDefaults.shapes()) {
                                            Text(stringResource(Res.string.back))
                                        }
                                        IosPlaylistImportSettings(
                                            database = database,
                                            onPickPlaylistFile = ::pickPlaylistFile,
                                            scrollable = false,
                                        )
                                    } else {
                                        BackupRestorePage(
                                            onExport = ::exportBackup,
                                            onImport = ::importBackup,
                                            status = backupStatus,
                                            failure = backupFailure,
                                        )
                                        Button(
                                            onClick = { showPlaylistImporter = true },
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                            enabled = !backupBusy,
                                            shapes = ButtonDefaults.shapes(),
                                        ) {
                                            Text(stringResource(Res.string.import_playlist))
                                        }
                                    }
                                }
                            }

                            else -> {
                            }
                        }
                    },
                    formatHistoryMonth = { formatHistoryMonth(it) },
                    oauthCallbackUrls = oauthCallbackUrls,
                    loginContent = { switching, onBack, onComplete, onTokenLogin ->
                        IosLoginScreen(switching, onBack, onComplete, onTokenLogin)
                    },
                )
            }
            IosUpdateNotice()
        }
        IosPageBoundTokenHost()
    }
}

private fun iosContentLocaleChoices(): Pair<List<SettingsChoice>, List<SettingsChoice>> {
    val locale = NSLocale.currentLocale
    val languages =
        BuildConfig.BUNDLED_LANGUAGE_TAGS
            .split(',')
            .filter(String::isNotBlank)
            .map { code -> SettingsChoice(code, NSLocale(code).displayNameForKey(NSLocaleIdentifier, code) ?: code) }
            .sortedBy(SettingsChoice::label)
    val countries =
        contentCountryCodes
            .asSequence()
            .map { code -> SettingsChoice(code, locale.displayNameForKey(NSLocaleCountryCode, code) ?: code) }
            .sortedBy(SettingsChoice::label)
            .toList()
    return languages to countries
}

fun MainViewController(): UIViewController {
    if (IosCrashRecovery.hasPendingReport) {
        return ComposeUIViewController {
            val presenter = LocalUIViewController.current
            var showingReport by remember { mutableStateOf(true) }
            if (showingReport) {
                MaterialTheme {
                    CrashLogsScreen(IosCrashRecovery, onClose = {
                        IosCrashRecovery.acknowledge()
                        MetrolistPlatform.initialize()
                        configureIosImageLoader()
                        showingReport = false
                    }, onShare = { IosNativeFiles.shareText(presenter, it) })
                }
            } else {
                MetrolistIOSApp()
            }
        }
    }
    configureIosImageLoader()
    return ComposeUIViewController { MetrolistIOSApp() }
}

private var imageMemoryWarningObserver: NSObjectProtocol? = null

@OptIn(ExperimentalCoilApi::class)
internal fun configureIosImageLoader() {
    if (imageMemoryWarningObserver == null) {
        imageMemoryWarningObserver =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = UIApplicationDidReceiveMemoryWarningNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) {
                SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.clear()
            }
    }
    SingletonImageLoader.setSafe { context ->
        lateinit var imageLoader: ImageLoader
        imageLoader =
            ImageLoader
                .Builder(context)
                .components {
                    add(IosPlaylistCoverMapper())
                    add(ArtworkSizingInterceptor { imageLoader })
                    add(KtorNetworkFetcherFactory(concurrentRequestStrategy = { DeDupeConcurrentRequestStrategy() }))
                }.diskCache {
                    ResizableImageDiskCache(
                        requireNotNull(
                            NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String,
                        ).toPath() /
                            "coil3_disk_cache",
                        Settings.shared.maxImageCacheSize * 1024L * 1024L,
                    )
                }.build()
        imageLoader
    }
}
