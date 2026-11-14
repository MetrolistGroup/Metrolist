package com.metrolist.android

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.metrolist.android.deviceconnect.AndroidDeviceConnect
import com.metrolist.android.listentogether.AndroidListenTogetherManager
import com.metrolist.android.locale.AppLocale
import com.metrolist.android.onboarding.OnboardingDestination
import com.metrolist.android.onboarding.OnboardingScreen
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.android.playback.playbackAudioQuality
import com.metrolist.android.playback.prefetchIncomingAudio
import com.metrolist.android.playback.resolveIncomingPlaybackSong
import com.metrolist.android.playback.toAndroidContentHints
import com.metrolist.android.ui.IncomingDestination
import com.metrolist.android.ui.MetrolistApp
import com.metrolist.android.ui.screens.settings.DiscordLoginFlow
import com.metrolist.android.ui.theme.MetrolistTheme
import com.metrolist.android.ui.toIncomingDestination
import com.metrolist.android.utils.CrashHandler
import com.metrolist.android.utils.isAndroidTv
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.LibraryFilterType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.defaultOpenTabRoot
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.applyContentRegion
import com.metrolist.shared.ui.LocalIsInPipMode
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.theme.appearanceDensity
import com.metrolist.shared.youtube.playback.AndroidTokenProvider
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.metrolist.shared.youtube.playback.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import java.util.UUID

internal class NavigationRestorationViewModel(
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val saveKey: String =
        savedState.get<String>("navigationSaveKey") ?: UUID.randomUUID().toString().also {
            savedState["navigationSaveKey"] = it
        }

    fun shouldResetLibraryFilter(defaultOpenTab: String): Boolean {
        val freshStart = savedState.get<Boolean>("contentStarted") != true
        savedState["contentStarted"] = true
        return freshStart && defaultOpenTabRoot(defaultOpenTab) == "LIBRARY"
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_SEARCH = "com.metrolist.music.action.SEARCH"
        const val ACTION_PLAYLIST_IMPORT = "com.metrolist.music.action.PLAYLIST_IMPORT"
        const val EXTRA_IMPORT_RESULT_MESSAGE = "import_result_message"
        const val ACTION_DOWNLOADS = "com.metrolist.music.action.DOWNLOADS"
        const val ACTION_LISTEN_TOGETHER = "com.metrolist.music.action.LISTEN_TOGETHER"
        const val ACTION_LIBRARY = "com.metrolist.music.action.LIBRARY"
        const val ACTION_UPDATES = "com.metrolist.music.action.UPDATES"
        const val ACTION_LOGIN = "com.metrolist.music.action.LOGIN"
        const val ACTION_BACKUP_RESTORE = "com.metrolist.music.action.BACKUP_RESTORE"
        const val ACTION_RECOGNITION = "com.metrolist.music.action.RECOGNITION"
        const val ACTION_OPEN_WIDGET_TARGET = "com.metrolist.music.action.OPEN_WIDGET_TARGET"
        const val EXTRA_WIDGET_TARGET_TYPE = "widget_target_type"
        const val EXTRA_WIDGET_TARGET_ID = "widget_target_id"
    }

    override fun attachBaseContext(newBase: Context) {
        val recovery = (newBase.applicationContext as App).recovery
        var context = newBase
        recovery.run { context = AppLocale.wrap(newBase) }
        super.attachBaseContext(context)
    }

    private val incomingIntentState = mutableStateOf<Intent?>(null)
    private var incomingPlaybackSong: Deferred<SongItem>? = null
    private val isInPipModeState = mutableStateOf(false)
    private val navigationRestoration: NavigationRestorationViewModel by viewModels()
    private lateinit var startupPresentation: StartupPresentation
    private var startupTraceOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as App
        val recovery = app.recovery
        val startedAt = SystemClock.elapsedRealtime()
        startupPresentation = StartupPresentation(startedAt)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.beginAsyncSection("Metrolist.startup.firstFrame", hashCode())
            startupTraceOpen = true
        }
        var splashReleased = false
        try {
            installSplashScreen().setKeepOnScreenCondition {
                val now = SystemClock.elapsedRealtime()
                val keep = startupPresentation.keepSplash(now, app.startup.isReady, recovery.isRecovering, app.restoringBackup)
                if (!keep && !splashReleased) {
                    splashReleased = true
                    endStartupTrace()
                    Log.i(
                        "MetrolistStartup",
                        "splash released elapsedMs=${now - startedAt} home=${startupPresentation.homeRequired} " +
                            "cacheReady=${startupPresentation.homeCacheReady} avatarReady=${startupPresentation.avatarReady} " +
                            "recovery=${recovery.isRecovering} restore=${app.restoringBackup}",
                    )
                }
                keep
            }
        } catch (error: Throwable) {
            recovery.capture(error)
        }
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            recovery.report.collect { id -> if (id != null) showStartupRecovery() }
        }
        recovery.run { initializeActivity(savedInstanceState) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showStartupRecovery()
    }

    private fun showStartupRecovery() {
        val id = (application as App).recovery.claimReport(window.decorView.hasWindowFocus()) ?: return
        CrashHandler.launch(this, id, terminateAfterHandoff = true)
        finish()
    }

    private fun initializeActivity(savedInstanceState: Bundle?) {
        val app = application as App
        val waitingForRestore = app.restoringBackup

        @Suppress("DEPRECATION")
        val pendingIntent =
            if (savedInstanceState?.containsKey("incomingIntent") == true) {
                savedInstanceState.getParcelable<Intent>("incomingIntent")
            } else {
                intent
            }
        incomingIntentState.value = pendingIntent
        setIntent(pendingIntent ?: Intent(this, MainActivity::class.java))
        isInPipModeState.value = isInPictureInPictureMode
        WindowCompat.enableEdgeToEdge(window)
        val lightSystemBars = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
        if (waitingForRestore) {
            setContent {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                        ) {
                            @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                            CircularWavyProgressIndicator()
                            Text(stringResource(R.string.restore_in_progress))
                        }
                    }
                }
            }
        }
        // Main.immediate can deadlock Compose resource loading inside the startup completion event loop.
        lifecycleScope.launch(Dispatchers.Main) {
            if (!awaitStartup()) return@launch
            app.recovery.run {
                if (waitingForRestore) {
                    recreate()
                    return@run
                }
                consumeDiscordCallback(intent)
                app.restoreDeviceConnectIfEnabled()
                prepareIncomingPlayback()
                showContent()
            }
        }
    }

    private fun prepareIncomingPlayback() {
        if (!(application as App).onboardingManager.isComplete) return
        val destination = incomingIntentState.value?.toIncomingDestination() as? IncomingDestination.PlayVideo ?: return
        GlobalYouTube.applyContentRegion(
            contentCountry = Settings.shared.contentCountry,
            contentLanguage = Settings.shared.contentLanguage,
            systemLocale = AppLocale.systemLocale(this),
        )
        lifecycleScope.launch {
            (get<TokenProvider>() as? AndroidTokenProvider)?.prewarm()
        }
        get<PlayerConnection>().ensureConnected()
        incomingPlaybackSong =
            lifecycleScope.async(Dispatchers.IO) {
                val song =
                    resolveIncomingPlaybackSong(
                        videoId = destination.videoId,
                        fallbackArtist = getString(R.string.youtube_music),
                        loadMetadata = GlobalYouTubeRepository.instance::getSongMetadata,
                        loadPlayerInformation = GlobalYouTubeRepository.instance::getSongPlayerInformation,
                    )
                prefetchIncomingAudio(
                    song = song,
                    wantVideo = Settings.shared.videoViewerVisible,
                    canPlayLocally =
                        PlaybackNetworkStateStore.current.connected &&
                            !AndroidListenTogetherManager.isGuestPlaybackLocked && !AndroidDeviceConnect.manager.remote.isActive,
                ) { source ->
                    get<StreamResolver>().resolve(
                        source.id,
                        source.toAndroidContentHints(wantVideo = false),
                        playbackAudioQuality(),
                        sourceSong = source,
                    )
                }
                song
            }
    }

    private fun showContent() {
        if (navigationRestoration.shouldResetLibraryFilter(Settings.shared.defaultOpenTab)) {
            Settings.shared.libraryFilter = LibraryFilterType.DEFAULT
        }
        setContent {
            val systemDensity = LocalDensity.current
            val windowSize = LocalWindowInfo.current.containerDpSize
            val safeInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
            val direction = LocalLayoutDirection.current
            val contentWidth =
                windowSize.width.value -
                    (safeInsets.getLeft(systemDensity, direction) + safeInsets.getRight(systemDensity, direction)) / systemDensity.density
            val density =
                appearanceDensity(
                    systemDensity = systemDensity,
                    smallestDimensionDp = minOf(windowSize.width, windowSize.height).value,
                    enableLandscapeScaling = Settings.shared.enableLandscapeScaling,
                    userDensityScale = Settings.shared.densityScaleFactor,
                    availableWidthDp = contentWidth,
                )
            val systemViewConfiguration = LocalViewConfiguration.current
            val touchTargetScale = (systemDensity.density / density.density).coerceAtLeast(1f)
            val viewConfiguration =
                remember(systemViewConfiguration, touchTargetScale) {
                    object : ViewConfiguration by systemViewConfiguration {
                        override val minimumTouchTargetSize = systemViewConfiguration.minimumTouchTargetSize * touchTargetScale
                    }
                }
            CompositionLocalProvider(
                LocalStartupPresentation provides startupPresentation,
                LocalViewConfiguration provides viewConfiguration,
                LocalMinimumInteractiveComponentSize provides LocalMinimumInteractiveComponentSize.current * touchTargetScale,
                LocalIsInPipMode provides isInPipModeState.value,
                LocalDensity provides density,
            ) {
                val app = application as App
                var onboardingComplete by remember { mutableStateOf(app.onboardingManager.isComplete) }
                Box(Modifier.fillMaxSize().onGloballyPositioned { startupPresentation.contentLaidOut = true }) {
                    if (onboardingComplete) {
                        MetrolistApp(
                            navigationSaveKey = navigationRestoration.saveKey,
                            incomingIntent = incomingIntentState.value,
                            incomingPlaybackSong = incomingPlaybackSong,
                            onExitApp = ::finish,
                            onIncomingIntentConsumed = { consumed ->
                                if (incomingIntentState.value === consumed) {
                                    incomingIntentState.value = null
                                    setIntent(Intent(this@MainActivity, MainActivity::class.java))
                                }
                            },
                        )
                    } else {
                        val tvDevice = remember { isAndroidTv() }
                        CompositionLocalProvider(LocalIsTv provides tvDevice) {
                            MetrolistTheme {
                                OnboardingScreen(
                                    migration = app.legacyInstallMigration,
                                    hasLegacyInstallationOverride = app.hadLegacyInstallationAtStartup,
                                    onComplete = { sendCrashReports, destination ->
                                        app.completeOnboarding(sendCrashReports)
                                        incomingIntentState.value =
                                            when (destination) {
                                                OnboardingDestination.HOME -> incomingIntentState.value
                                                OnboardingDestination.LOGIN -> Intent(ACTION_LOGIN)
                                                OnboardingDestination.BACKUP_RESTORE -> Intent(ACTION_BACKUP_RESTORE)
                                            }
                                        prepareIncomingPlayback()
                                        onboardingComplete = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun endStartupTrace() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && startupTraceOpen) {
            Trace.endAsyncSection("Metrolist.startup.firstFrame", hashCode())
            startupTraceOpen = false
        }
    }

    override fun onDestroy() {
        endStartupTrace()
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState.value = isInPictureInPictureMode
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable("incomingIntent", incomingIntentState.value)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingPlaybackSong?.cancel()
        incomingPlaybackSong = null
        setIntent(intent)
        incomingIntentState.value = intent
        lifecycleScope.launch {
            if (!awaitStartup()) return@launch
            (application as App).recovery.run { consumeDiscordCallback(intent) }
        }
    }

    private suspend fun awaitStartup(): Boolean {
        val app = application as App
        if (app.recovery.isRecovering) return false
        return try {
            app.startup.await()
            !app.recovery.isRecovering
        } catch (error: Throwable) {
            if (error !is CancellationException) app.recovery.capture(error)
            false
        }
    }

    private fun consumeDiscordCallback(callback: Intent) {
        if (DiscordLoginFlow.handleCallback(callback) && intent === callback) {
            incomingIntentState.value = null
            setIntent(Intent(this, MainActivity::class.java))
        }
    }
}
