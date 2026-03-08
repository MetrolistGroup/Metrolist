package com.metrolist.android.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
import coil3.imageLoader
import com.metrolist.android.BuildConfig
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.R
import com.metrolist.android.extensions.findActivity
import com.metrolist.android.locale.AppLocale
import com.metrolist.android.locale.rememberAppLanguageChoices
import com.metrolist.android.playback.AudioCacheManager
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.android.spotifyimport.SpotifyLoginScreen
import com.metrolist.android.spotifyimport.androidSpotifyImportManager
import com.metrolist.android.ui.component.rememberMetroEqProfilePicker
import com.metrolist.android.ui.download.rememberDownloadPermissionGate
import com.metrolist.android.ui.player.CanvasCache
import com.metrolist.android.ui.screens.settings.AndroidAboutScreen
import com.metrolist.android.ui.screens.settings.AndroidAutoScreen
import com.metrolist.android.ui.screens.settings.BackupRestoreScreen
import com.metrolist.android.ui.screens.settings.DevtoolsSettingsScreen
import com.metrolist.android.ui.screens.settings.PlaybackBenchmarkSettingsScreen
import com.metrolist.android.ui.screens.settings.StorageSettingsScreen
import com.metrolist.android.ui.screens.settings.UpdateDetailsScreen
import com.metrolist.android.ui.screens.settings.UpdatesScreen
import com.metrolist.android.update.AppUpdateManager
import com.metrolist.android.update.AppUpdateState
import com.metrolist.android.utils.AppFontManager
import com.metrolist.android.utils.AppIcon
import com.metrolist.android.utils.IconUtils
import com.metrolist.android.utils.QuickFontManager
import com.metrolist.android.utils.SentryManager
import com.metrolist.android.viewmodels.HomeViewModel
import com.metrolist.android.viewmodels.SearchViewModel
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_font_active
import com.metrolist.shared.generated.resources.app_font_applied
import com.metrolist.shared.generated.resources.app_font_checking
import com.metrolist.shared.generated.resources.app_font_choose
import com.metrolist.shared.generated.resources.app_font_download_and_use
import com.metrolist.shared.generated.resources.app_font_download_error
import com.metrolist.shared.generated.resources.app_font_downloading
import com.metrolist.shared.generated.resources.app_font_import_custom
import com.metrolist.shared.generated.resources.app_font_installed
import com.metrolist.shared.generated.resources.app_font_invalid
import com.metrolist.shared.generated.resources.app_font_reset
import com.metrolist.shared.generated.resources.app_font_reset_confirm
import com.metrolist.shared.generated.resources.app_font_reset_description
import com.metrolist.shared.generated.resources.app_language_system
import com.metrolist.shared.generated.resources.calculating
import com.metrolist.shared.generated.resources.device_language
import com.metrolist.shared.generated.resources.search_history_removal_failed
import com.metrolist.shared.generated.resources.settings_storage_summary
import com.metrolist.shared.generated.resources.settings_updates_available_description
import com.metrolist.shared.generated.resources.spotify_import_error_sign_in_failed
import com.metrolist.shared.generated.resources.updates_no_updates
import com.metrolist.shared.settings.FontOption
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.applyContentRegion
import com.metrolist.shared.state.contentCountryCodes
import com.metrolist.shared.ui.settings.PlayerLyricsSettingsPreview
import com.metrolist.shared.ui.settings.SettingsChangeEffect
import com.metrolist.shared.ui.settings.SettingsChoice
import com.metrolist.shared.ui.settings.SettingsHostAction
import com.metrolist.shared.ui.settings.SettingsHostChoice
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.settings.SettingsPlatform
import com.metrolist.shared.ui.settings.SharedSettingsHost
import com.metrolist.shared.ui.settings.SharedSettingsScreen
import com.metrolist.shared.ui.utils.rememberFileSizeFormatter
import com.metrolist.shared.viewmodels.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import java.io.File
import java.util.Locale
import com.metrolist.shared.settings.Settings as SharedPreferences

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
) {
    val pageViewModels = rememberViewModelStoreProvider()
    var currentPage by remember { mutableStateOf<SettingsPageId?>(null) }
    val formatFileSize = rememberFileSizeFormatter()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val playerConnection = LocalPlayerConnection.current
    val audioCacheManager: AudioCacheManager = koinInject()
    val homeViewModel: HomeViewModel = koinViewModel()
    val searchViewModel: SearchViewModel = koinViewModel()
    val searchState by searchViewModel.shared.uiState.collectAsState()
    val historyRemovalFailed = stringResource(Res.string.search_history_removal_failed)
    LaunchedEffect(searchState.historyMutationFailures) {
        if (searchState.historyMutationFailures > 0) Toast.makeText(context, historyRemovalFailed, Toast.LENGTH_LONG).show()
    }
    val scope = rememberCoroutineScope()
    val requireDownloadNotifications = rememberDownloadPermissionGate()

    val miniPlayerInset = LocalMiniPlayerBottomInset.current
    val appUpdateState by AppUpdateManager.state.collectAsState()
    val configuration = LocalConfiguration.current
    val systemLocale = remember(configuration) { AppLocale.systemLocale(context) }
    val systemDefaultLabel = stringResource(Res.string.app_language_system)
    val contentLanguageChoices = rememberAppLanguageChoices()
    val contentCountryChoices =
        remember(systemLocale, systemDefaultLabel) {
            listOf(SettingsChoice(GlobalYouTube.SYSTEM, systemDefaultLabel)) +
                contentCountryCodes
                    .asSequence()
                    .map { code ->
                        val label =
                            Locale
                                .Builder()
                                .setRegion(code)
                                .build()
                                .getDisplayCountry(systemLocale)
                                .ifBlank { code }
                        SettingsChoice(code, label)
                    }.sortedBy(SettingsChoice::label)
                    .toList()
        }
    val deviceLanguageLabel = stringResource(Res.string.device_language)
    val lyricsTargetLanguageChoices =
        remember(contentLanguageChoices, deviceLanguageLabel) {
            listOf(SettingsChoice("device", deviceLanguageLabel)) + contentLanguageChoices.drop(1)
        }
    val appIconChoices =
        AppIcon.entries.map { icon ->
            SettingsChoice(
                id = icon.name,
                label = stringResource(icon.labelRes),
                preview = {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) {
                        icon.previewBackgroundRes?.let { background ->
                            Image(painterResource(background), contentDescription = null, modifier = Modifier.matchParentSize())
                        }
                        Image(painterResource(icon.previewRes), contentDescription = null, modifier = Modifier.matchParentSize())
                    }
                },
            )
        }
    val customFontFile = SharedPreferences.shared.androidCustomFontFile
    val hasCustomFont = AppFontManager.hasImportedFont(context, customFontFile)
    var downloadingFontId by remember { mutableStateOf<String?>(null) }
    var installedFontIds by remember { mutableStateOf<Set<String>?>(null) }
    val importCustomFontLabel = stringResource(Res.string.app_font_import_custom)
    LaunchedEffect(downloadingFontId) {
        installedFontIds = QuickFontManager.downloadedFontIds(context)
    }
    val fontPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                withContext(Dispatchers.IO) { AppFontManager.importFont(context, uri) }
                    .onSuccess { font ->
                        SharedPreferences.shared.appFont = FontOption.SYSTEM
                        SharedPreferences.shared.androidCustomFontFile = font.fileName
                        SharedPreferences.shared.androidCustomFontName = font.displayName
                        SharedPreferences.shared.androidActiveFontId = QuickFontManager.CUSTOM_ID
                        Toast.makeText(context, getString(Res.string.app_font_applied, font.displayName), Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, getString(Res.string.app_font_invalid), Toast.LENGTH_LONG).show()
                    }
            }
        }
    val fontPrimaryActions =
        listOf(
            SettingsHostAction(
                id = "resetFont",
                title = stringResource(Res.string.app_font_reset),
                description = stringResource(Res.string.app_font_reset_description),
                confirmationMessage = if (hasCustomFont) stringResource(Res.string.app_font_reset_confirm) else null,
                enabled =
                    hasCustomFont || SharedPreferences.shared.androidActiveFontId != QuickFontManager.SYSTEM_ID ||
                        SharedPreferences.shared.appFont != FontOption.SYSTEM,
                onClick = {
                    SharedPreferences.shared.appFont = FontOption.SYSTEM
                    SharedPreferences.shared.androidActiveFontId = QuickFontManager.SYSTEM_ID
                    SharedPreferences.shared.androidCustomFontFile = ""
                    SharedPreferences.shared.androidCustomFontName = ""
                    AppFontManager.removeImportedFont(context)
                },
            ),
            SettingsHostAction(
                id = "importFont",
                title = stringResource(Res.string.app_font_choose),
                description = if (hasCustomFont) SharedPreferences.shared.androidCustomFontName else importCustomFontLabel,
                onClick = {
                    fontPicker.launch(
                        arrayOf(
                            "font/ttf",
                            "font/otf",
                            "application/x-font-ttf",
                            "application/x-font-opentype",
                            "application/octet-stream",
                        ),
                    )
                },
            ),
        )
    val fontDownloadActions =
        QuickFontManager.fonts.map { font ->
            val fontName = stringResource(font.labelRes)
            val isDownloaded = font.id in installedFontIds.orEmpty()
            val isActive = activeFontId() == font.id && isDownloaded
            SettingsHostAction(
                id = "downloadFont:${font.id}",
                title = fontName,
                description =
                    when {
                        downloadingFontId == font.id -> stringResource(Res.string.app_font_downloading, fontName)
                        installedFontIds == null -> stringResource(Res.string.app_font_checking)
                        isActive -> stringResource(Res.string.app_font_active)
                        isDownloaded -> stringResource(Res.string.app_font_installed)
                        else -> stringResource(Res.string.app_font_download_and_use)
                    },
                enabled = installedFontIds != null && downloadingFontId == null,
                onClick = {
                    if (isDownloaded) {
                        SharedPreferences.shared.appFont = FontOption.SYSTEM
                        SharedPreferences.shared.androidActiveFontId = font.id
                    } else {
                        downloadingFontId = font.id
                        scope.launch {
                            QuickFontManager
                                .downloadFonts(context, font.id)
                                .onSuccess {
                                    SharedPreferences.shared.appFont = FontOption.SYSTEM
                                    SharedPreferences.shared.androidActiveFontId = font.id
                                }.onFailure {
                                    Toast
                                        .makeText(
                                            context,
                                            getString(Res.string.app_font_download_error, fontName),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                }
                            downloadingFontId = null
                        }
                    }
                },
            )
        }
    var totalCacheSize by remember { mutableLongStateOf(0L) }
    var freeStorageSize by remember { mutableLongStateOf(0L) }
    var isCalculatingCacheSize by remember { mutableStateOf(true) }

    fun updateStorageStats() {
        scope.launch {
            isCalculatingCacheSize = true
            try {
                val exoplayerDir = context.filesDir.resolve("exoplayer")
                val coilDir = context.cacheDir.resolve("coil")
                val songCacheSize =
                    withContext(Dispatchers.IO) {
                        audioCacheManager.getCacheSizeBytes() + exoplayerDir.calculateDirectorySize()
                    }
                val downloadSize = withContext(Dispatchers.IO) { audioCacheManager.getDownloadSizeBytes() }
                val imageCacheSize =
                    withContext(Dispatchers.IO) {
                        context.imageLoader.diskCache?.size ?: coilDir.calculateDirectorySize()
                    }
                val animatedCoverCacheSize = withContext(Dispatchers.IO) { CanvasCache.get(context).sizeBytes() }
                totalCacheSize = songCacheSize + imageCacheSize + downloadSize + animatedCoverCacheSize
                freeStorageSize = withContext(Dispatchers.IO) { context.filesDir.usableSpace }
            } finally {
                isCalculatingCacheSize = false
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) updateStorageStats()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val availablePages =
        buildSet {
            add(SettingsPageId.FONTS)
            add(SettingsPageId.PLAYER_STYLE)
            add(SettingsPageId.PLAYER_ARTWORK)
            add(SettingsPageId.MINI_PLAYER_STYLE)
            add(SettingsPageId.LYRICS_SOURCES)
            add(SettingsPageId.LYRICS_AI_TRANSLATION)
            add(SettingsPageId.LYRICS_ROMANIZATION)
            add(SettingsPageId.HIDDEN_HOME_ITEMS)
            add(SettingsPageId.HIDDEN_LIBRARY_ITEMS)
            add(SettingsPageId.BLOCKED_ARTISTS)
            add(SettingsPageId.STORAGE)
            add(SettingsPageId.BACKUP_RESTORE)
            add(SettingsPageId.UPDATES)
            add(SettingsPageId.UPDATE_DETAILS)
            add(SettingsPageId.ABOUT)
            add(SettingsPageId.SUPPORTED_LINKS)
            if (SharedPreferences.shared.devtoolsUnlocked) {
                add(SettingsPageId.DEVTOOLS)
                add(SettingsPageId.PLAYBACK_BENCHMARK)
            }
            add(SettingsPageId.ANDROID_AUTO)
        }

    fun openSupportedLinks() {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri)
            if (intent.resolveActivity(context.packageManager) != null) {
                try {
                    context.startActivity(intent)
                    return
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        if (fallback.resolveActivity(context.packageManager) != null) context.startActivity(fallback)
    }

    var showSpotifyLogin by remember { mutableStateOf(false) }
    var spotifyCookie by remember { mutableStateOf<String?>(null) }
    var spotifyError by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        SharedSettingsScreen(
            host =
                SharedSettingsHost(
                    platform = SettingsPlatform.ANDROID,
                    lyricsPreview = { modifier ->
                        val viewModel: PlayerViewModel = koinInject()
                        val castState = playerConnection?.castState?.collectAsState()?.value
                        PlayerLyricsSettingsPreview(
                            viewModel = viewModel,
                            modifier = modifier,
                            isCasting = castState?.isConnected == true,
                        )
                    },
                    rememberMetroEqProfilePicker = { onPicked -> rememberMetroEqProfilePicker(onPicked) },
                    onClose = onNavigateBack,
                    onOpenArtist = onNavigateToArtist,
                    onSpotifySignIn = { showSpotifyLogin = true },
                    bottomContentPadding = miniPlayerInset,
                    storageSummary =
                        if (isCalculatingCacheSize) {
                            stringResource(Res.string.calculating)
                        } else {
                            stringResource(
                                Res.string.settings_storage_summary,
                                formatFileSize(totalCacheSize),
                                formatFileSize(freeStorageSize),
                            )
                        },
                    updateSummary =
                        if (
                            appUpdateState is AppUpdateState.Available ||
                            appUpdateState is AppUpdateState.Downloading ||
                            appUpdateState is AppUpdateState.Downloaded ||
                            (appUpdateState as? AppUpdateState.Error)?.release != null
                        ) {
                            stringResource(Res.string.settings_updates_available_description)
                        } else {
                            stringResource(Res.string.updates_no_updates)
                        },
                    availablePages = availablePages,
                    externalPages = setOf(SettingsPageId.SUPPORTED_LINKS),
                    onPageChanged = { page ->
                        if (page != currentPage) {
                            currentPage?.let(pageViewModels::clearKey)
                            currentPage = page
                        }
                    },
                    customPageIcons =
                        mapOf(
                            SettingsPageId.ANDROID_AUTO to { tint ->
                                Icon(
                                    painter = painterResource(R.drawable.ic_android_auto),
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                        ),
                    hostChoices =
                        mapOf(
                            SettingsHostChoice.CONTENT_LANGUAGE to contentLanguageChoices,
                            SettingsHostChoice.CONTENT_COUNTRY to contentCountryChoices,
                            SettingsHostChoice.APP_LANGUAGE to contentLanguageChoices,
                            SettingsHostChoice.LYRICS_TARGET_LANGUAGE to lyricsTargetLanguageChoices,
                            SettingsHostChoice.APP_ICON to appIconChoices,
                        ),
                    onHostChoiceSelected = { choice, selected ->
                        when (choice) {
                            SettingsHostChoice.CONTENT_LANGUAGE -> {
                                SharedPreferences.shared.contentLanguage = selected
                            }

                            SettingsHostChoice.CONTENT_COUNTRY -> {
                                SharedPreferences.shared.contentCountry = selected
                            }

                            SettingsHostChoice.APP_LANGUAGE -> {
                                SharedPreferences.shared.appLanguage = selected
                                AppLocale.applySelection(context, selected)
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                    context.findActivity()?.recreate()
                                }
                            }

                            SettingsHostChoice.LYRICS_TARGET_LANGUAGE -> {
                                SharedPreferences.shared.lyricsAiTargetLanguage = selected
                            }

                            SettingsHostChoice.APP_ICON -> {
                                val icon = AppIcon.fromPreference(selected)
                                SharedPreferences.shared.appIcon = icon.name
                                IconUtils.setIcon(context, icon)
                            }

                            SettingsHostChoice.ARTIST_ICON_SOURCE,
                            SettingsHostChoice.ARTIST_BANNER_SOURCE,
                            -> {
                            }
                        }
                        if (choice == SettingsHostChoice.CONTENT_LANGUAGE || choice == SettingsHostChoice.CONTENT_COUNTRY) {
                            GlobalYouTube.applyContentRegion(
                                contentCountry = SharedPreferences.shared.contentCountry,
                                contentLanguage = SharedPreferences.shared.contentLanguage,
                                systemLocale = systemLocale,
                            )
                            homeViewModel.shared.refresh {}
                        }
                    },
                    fontPrimaryActions = fontPrimaryActions,
                    fontDownloadActions = fontDownloadActions,
                    onClearSearchHistory = searchViewModel.shared::clearSearchHistory,
                    onOpenPage = { page ->
                        if (page == SettingsPageId.SUPPORTED_LINKS) openSupportedLinks()
                    },
                    pageContent = { page, onBack, onNavigate ->
                        CompositionLocalProvider(
                            LocalViewModelStoreOwner provides rememberViewModelStoreOwner(page, pageViewModels),
                        ) {
                            when (page) {
                                SettingsPageId.ANDROID_AUTO -> {
                                    AndroidAutoScreen(onNavigateBack = onBack)
                                }

                                SettingsPageId.STORAGE -> {
                                    StorageSettingsScreen(onBack, onNavigateToDownloads)
                                }

                                SettingsPageId.BACKUP_RESTORE -> {
                                    BackupRestoreScreen(onNavigateBack = onBack)
                                }

                                SettingsPageId.UPDATES -> {
                                    UpdatesScreen(onBack) { onNavigate(SettingsPageId.UPDATE_DETAILS) }
                                }

                                SettingsPageId.UPDATE_DETAILS -> {
                                    UpdateDetailsScreen(onNavigateBack = onBack)
                                }

                                SettingsPageId.ABOUT -> {
                                    var openDevtools by remember { mutableStateOf(false) }
                                    LaunchedEffect(openDevtools) {
                                        if (openDevtools) {
                                            onNavigate(SettingsPageId.DEVTOOLS)
                                            openDevtools = false
                                        }
                                    }
                                    AndroidAboutScreen(onBack, onDevtoolsUnlocked = { openDevtools = true })
                                }

                                SettingsPageId.DEVTOOLS -> {
                                    DevtoolsSettingsScreen(onBack) { onNavigate(SettingsPageId.PLAYBACK_BENCHMARK) }
                                }

                                SettingsPageId.PLAYBACK_BENCHMARK -> {
                                    PlaybackBenchmarkSettingsScreen(onNavigateBack = onBack)
                                }

                                else -> {
                                }
                            }
                        }
                    },
                    onFloatSettingChanged = { effect, value -> applyPlaybackFloatSetting(playerConnection, effect, value) },
                    onSettingChanged = { effect, value ->
                        when (effect) {
                            SettingsChangeEffect.AUDIO -> {
                                playerConnection?.applyAudioPreferences()
                            }

                            SettingsChangeEffect.VIDEO_PLAYBACK -> {
                                if (value == false) playerConnection?.setVideoMode(false)
                            }

                            SettingsChangeEffect.SENTRY -> {
                                value?.let(SentryManager::setEnabled)
                            }

                            SettingsChangeEffect.AUTO_DOWNLOAD_ON_LIKE -> {
                                if (value == true) {
                                    requireDownloadNotifications { SharedPreferences.shared.autoDownloadOnLike = true }
                                } else {
                                    SharedPreferences.shared.autoDownloadOnLike = false
                                }
                            }

                            SettingsChangeEffect.CONTENT,
                            SettingsChangeEffect.RESTART,
                            SettingsChangeEffect.PLAYBACK_SPEED,
                            SettingsChangeEffect.PLAYBACK_PITCH,
                            SettingsChangeEffect.LOGGING,
                            -> {}
                        }
                    },
                ),
            modifier = Modifier.fillMaxSize(),
        )
        if (showSpotifyLogin) {
            LaunchedEffect(spotifyCookie) {
                val cookie = spotifyCookie ?: return@LaunchedEffect
                try {
                    androidSpotifyImportManager.authenticate(cookie)
                    showSpotifyLogin = false
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    spotifyError = getString(Res.string.spotify_import_error_sign_in_failed)
                } finally {
                    spotifyCookie = null
                }
            }
            SpotifyLoginScreen(
                isAuthenticating = spotifyCookie != null,
                authenticationError = spotifyError,
                onClearAuthenticationError = { spotifyError = null },
                onSessionCookie = { spotifyCookie = it },
                onNavigateBack = {
                    showSpotifyLogin = false
                    spotifyCookie = null
                    spotifyError = null
                },
            )
        }
    }
}

private fun File.calculateDirectorySize(): Long {
    if (!exists()) return 0L
    return listFiles().orEmpty().sumOf { file ->
        if (file.isDirectory) file.calculateDirectorySize() else file.length()
    }
}

private fun activeFontId(): String =
    SharedPreferences.shared.androidActiveFontId.ifEmpty {
        when {
            SharedPreferences.shared.appFont == FontOption.GOOGLE_SANS -> QuickFontManager.GOOGLE_SANS_ID
            SharedPreferences.shared.androidCustomFontFile.isNotBlank() -> QuickFontManager.CUSTOM_ID
            else -> QuickFontManager.SYSTEM_ID
        }
    }

internal fun applyPlaybackFloatSetting(
    playerConnection: PlayerConnection?,
    effect: SettingsChangeEffect,
    value: Float,
) {
    when (effect) {
        SettingsChangeEffect.PLAYBACK_SPEED -> playerConnection?.setPlaybackSpeed(value)
        SettingsChangeEffect.PLAYBACK_PITCH -> playerConnection?.setPlaybackPitch(value)
        else -> Unit
    }
}
