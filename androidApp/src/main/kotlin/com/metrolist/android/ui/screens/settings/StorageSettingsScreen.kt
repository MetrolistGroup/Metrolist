/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.annotation.DelicateCoilApi
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import com.metrolist.android.R
import com.metrolist.android.playback.AudioCacheManager
import com.metrolist.android.playback.SongDownloadManager
import com.metrolist.android.ui.player.CanvasCache
import com.metrolist.shared.api.artwork.AnimatedArtworkService
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.animated_artwork_cache
import com.metrolist.shared.generated.resources.cache_size_warning_message
import com.metrolist.shared.generated.resources.cache_size_warning_title
import com.metrolist.shared.generated.resources.calculating
import com.metrolist.shared.generated.resources.change_folder
import com.metrolist.shared.generated.resources.clear_all_downloads
import com.metrolist.shared.generated.resources.clear_all_downloads_dialog
import com.metrolist.shared.generated.resources.clear_animated_artwork_cache
import com.metrolist.shared.generated.resources.clear_animated_artwork_cache_dialog
import com.metrolist.shared.generated.resources.clear_image_cache
import com.metrolist.shared.generated.resources.clear_image_cache_dialog
import com.metrolist.shared.generated.resources.clear_music_video_cache
import com.metrolist.shared.generated.resources.clear_music_video_cache_desc
import com.metrolist.shared.generated.resources.clear_music_video_cache_dialog
import com.metrolist.shared.generated.resources.clear_song_cache
import com.metrolist.shared.generated.resources.clear_song_cache_dialog
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.continue_action
import com.metrolist.shared.generated.resources.disable
import com.metrolist.shared.generated.resources.download_folder_access_failed
import com.metrolist.shared.generated.resources.download_location
import com.metrolist.shared.generated.resources.download_migration_failed
import com.metrolist.shared.generated.resources.downloaded_songs
import com.metrolist.shared.generated.resources.downloads
import com.metrolist.shared.generated.resources.error_title
import com.metrolist.shared.generated.resources.error_unknown
import com.metrolist.shared.generated.resources.image_cache
import com.metrolist.shared.generated.resources.library_offline_songs
import com.metrolist.shared.generated.resources.max_animated_artwork_cache_size
import com.metrolist.shared.generated.resources.max_image_cache_size
import com.metrolist.shared.generated.resources.max_song_cache_size
import com.metrolist.shared.generated.resources.music_video_quality
import com.metrolist.shared.generated.resources.music_video_quality_auto
import com.metrolist.shared.generated.resources.music_video_quality_desc
import com.metrolist.shared.generated.resources.music_video_quality_resolution
import com.metrolist.shared.generated.resources.ok
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.select_download_directory
import com.metrolist.shared.generated.resources.settings_download_folder_summary
import com.metrolist.shared.generated.resources.song_cache
import com.metrolist.shared.generated.resources.song_cache_directory
import com.metrolist.shared.generated.resources.song_cache_location
import com.metrolist.shared.generated.resources.song_cache_location_custom
import com.metrolist.shared.generated.resources.song_cache_location_custom_desc
import com.metrolist.shared.generated.resources.song_cache_location_internal
import com.metrolist.shared.generated.resources.song_cache_location_internal_desc
import com.metrolist.shared.generated.resources.song_cache_location_shared_media
import com.metrolist.shared.generated.resources.song_cache_location_shared_media_desc
import com.metrolist.shared.generated.resources.storage
import com.metrolist.shared.generated.resources.unlimited
import com.metrolist.shared.settings.MusicVideoQuality
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.storage.ResizableImageDiskCache
import com.metrolist.shared.storage.SongCacheLimitsMb
import com.metrolist.shared.storage.cacheAllocationBytes
import com.metrolist.shared.storage.songCacheLimitIndex
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.settings.StorageConfirmationDialog
import com.metrolist.shared.ui.settings.StorageSliderRow
import com.metrolist.shared.ui.settings.StorageSummary
import com.metrolist.shared.ui.settings.StorageUsageRow
import com.metrolist.shared.ui.settings.settingsPageSubtitle
import com.metrolist.shared.ui.utils.rememberFileSizeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource as androidStringResource

@OptIn(
    ExperimentalCoilApi::class,
    DelicateCoilApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun StorageSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDownloads: () -> Unit,
) {
    val formatFileSize = rememberFileSizeFormatter()
    val context = LocalContext.current
    var showCacheDirectory by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val audioCacheManager: AudioCacheManager = koinInject()
    val songDownloadManager: SongDownloadManager = koinInject()
    val musicVideoResolver: MusicVideoResolver = koinInject()
    val animatedArtworkService: AnimatedArtworkService = koinInject()

    val songCacheTypeString = stringResource(Res.string.song_cache).lowercase()
    val imageCacheTypeString = stringResource(Res.string.image_cache).lowercase()
    val animatedCoverCacheTypeString = stringResource(Res.string.animated_artwork_cache).lowercase()

    var clearSongCacheDialog by remember { mutableStateOf(false) }
    var clearImageCacheDialog by remember { mutableStateOf(false) }
    var clearDownloadsDialog by remember { mutableStateOf(false) }
    var clearAnimatedCoverCacheDialog by remember { mutableStateOf(false) }
    var clearMusicVideoCacheDialog by remember { mutableStateOf(false) }
    var showCacheWarningDialog by remember { mutableStateOf(false) }
    var cacheWarningType by remember { mutableStateOf("") }
    var cacheWarningUsage by remember { mutableLongStateOf(0L) }
    var onCacheWarningConfirm by remember { mutableStateOf<suspend () -> Unit>({}) }
    var migrationFailed by remember { mutableStateOf(false) }
    var folderAccessFailed by remember { mutableStateOf(false) }
    var migrationInProgress by remember { mutableStateOf(false) }
    var storageOperationFailed by remember { mutableStateOf(false) }
    var activeStorageOperations by remember { mutableIntStateOf(0) }

    fun launchStorageOperation(action: suspend () -> Unit) {
        activeStorageOperations++
        coroutineScope.launch {
            try {
                action()
            } finally {
                activeStorageOperations--
            }
        }
    }

    var cacheSliderRevision by remember { mutableIntStateOf(0) }

    val exoplayerDir = remember { context.filesDir.resolve("exoplayer") }
    val coilDir = remember { context.cacheDir.resolve("coil") }
    val imageDiskCache = context.imageLoader.diskCache
    var audioCacheLocation by remember { mutableStateOf(Settings.shared.audioCacheLocation) }
    var downloadLocation by remember { mutableStateOf(Settings.shared.downloadLocation) }
    var musicVideoQuality by remember { mutableStateOf(Settings.shared.musicVideoQuality) }

    var deviceCapacity by remember { mutableLongStateOf(0L) }
    var songCacheSize by remember { mutableLongStateOf(0L) }
    var imageCacheSize by remember { mutableLongStateOf(0L) }
    var isCalculatingSizes by remember { mutableStateOf(true) }
    var downloadSize by remember { mutableLongStateOf(0L) }
    var animatedCoverCacheSize by remember { mutableLongStateOf(0L) }
    var musicVideoCacheEntries by remember { mutableIntStateOf(0) }
    var maxSongCacheSize by remember { mutableIntStateOf(Settings.shared.maxSongCacheSize) }
    var maxImageCacheSize by remember { mutableIntStateOf(Settings.shared.maxImageCacheSize) }
    var maxAnimatedCoverCacheSize by remember { mutableIntStateOf(Settings.shared.animatedArtworkCacheSize) }
    var cacheLimitRequestId by remember { mutableIntStateOf(0) }

    val selectDownloadLocation: (String) -> Unit = { location ->
        Settings.shared.downloadLocation = location
        downloadLocation = location
        migrationFailed = false
        migrationInProgress = true
        coroutineScope.launch {
            runCatching { audioCacheManager.migrateDownloadsToConfiguredLocation() }
                .onFailure {
                    if (it is CancellationException) throw it
                    migrationFailed = true
                    Timber.w(it, "Failed to migrate downloads")
                }
            migrationInProgress = false
            downloadSize = audioCacheManager.getDownloadSizeBytes()
        }
    }
    val legacyStoragePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) selectDownloadLocation(AudioCacheManager.LOCATION_SHARED_MEDIA)
        }
    val customFolderPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to take persistable URI permission")
                    folderAccessFailed = true
                    return@rememberLauncherForActivityResult
                }
                Settings.shared.customDownloadPath = uri.toString()
                selectDownloadLocation(AudioCacheManager.LOCATION_CUSTOM)
            }
        }

    val songCacheSizeText =
        if (isCalculatingSizes) {
            stringResource(Res.string.calculating)
        } else {
            formatFileSize(songCacheSize)
        }
    val imageCacheSizeText =
        if (isCalculatingSizes) {
            stringResource(Res.string.calculating)
        } else {
            formatFileSize(imageCacheSize)
        }
    val downloadSizeText =
        if (isCalculatingSizes) {
            stringResource(Res.string.calculating)
        } else {
            formatFileSize(downloadSize)
        }

    val downloadProgress =
        if (deviceCapacity > 0) {
            (downloadSize.toFloat() / deviceCapacity).coerceIn(0f, 1f)
        } else {
            0f
        }

    val songCacheProgress by animateFloatAsState(
        targetValue =
            if (maxSongCacheSize > 0 && songCacheSize > 0) {
                (songCacheSize.toFloat() / (maxSongCacheSize * 1024 * 1024L)).coerceIn(0f, 1f)
            } else {
                0f
            },
        label = "songCacheProgress",
    )

    val imageCacheProgress by animateFloatAsState(
        targetValue =
            if (maxImageCacheSize > 0 && imageCacheSize > 0) {
                (imageCacheSize.toFloat() / (maxImageCacheSize * 1024 * 1024L)).coerceIn(0f, 1f)
            } else {
                0f
            },
        label = "imageCacheProgress",
    )

    val animatedCoverCacheSizeText =
        if (isCalculatingSizes) {
            stringResource(Res.string.calculating)
        } else {
            formatFileSize(animatedCoverCacheSize)
        }

    val animatedCoverProgress by animateFloatAsState(
        targetValue =
            if (maxAnimatedCoverCacheSize > 0 && animatedCoverCacheSize > 0) {
                (animatedCoverCacheSize.toFloat() / (maxAnimatedCoverCacheSize * 1024 * 1024L)).coerceIn(0f, 1f)
            } else {
                0f
            },
        label = "animatedCoverProgress",
    )

    suspend fun readSongCacheSize(): Long =
        withContext(Dispatchers.IO) {
            audioCacheManager.getCacheSizeBytes() + exoplayerDir.calculateDirectorySize()
        }

    suspend fun readImageCacheSize(): Long =
        withContext(Dispatchers.IO) {
            imageDiskCache?.size ?: coilDir.calculateDirectorySize()
        }

    suspend fun readAnimatedCoverCacheSize(): Long = withContext(Dispatchers.IO) { CanvasCache.get(context).sizeBytes() }

    val refreshStorageSizes: suspend () -> Unit = {
        isCalculatingSizes = true
        try {
            deviceCapacity =
                withContext(Dispatchers.IO) {
                    if (Settings.shared.downloadLocation ==
                        AudioCacheManager.LOCATION_CUSTOM
                    ) {
                        0L
                    } else {
                        audioCacheManager.downloadDirectory().totalSpace
                    }
                }
            songCacheSize = readSongCacheSize()
            downloadSize = withContext(Dispatchers.IO) { audioCacheManager.getDownloadSizeBytes() }
            imageCacheSize = readImageCacheSize()
            animatedCoverCacheSize = readAnimatedCoverCacheSize()
            musicVideoCacheEntries = withContext(Dispatchers.IO) { musicVideoResolver.getCacheEntryCount() }
        } finally {
            isCalculatingSizes = false
        }
    }

    fun requestCacheLimit(
        newValue: Int,
        cacheType: String,
        readSize: suspend () -> Long,
        updateSize: (Long) -> Unit,
        applyLimit: suspend () -> Unit,
    ) {
        val requestId = ++cacheLimitRequestId
        coroutineScope.launch {
            isCalculatingSizes = true
            try {
                val actualSize = readSize()
                if (requestId != cacheLimitRequestId) return@launch
                updateSize(actualSize)
                val newLimitInBytes =
                    if (newValue == -1) {
                        Long.MAX_VALUE
                    } else {
                        newValue * 1024 * 1024L
                    }
                if (newValue != -1 && newLimitInBytes < actualSize) {
                    cacheWarningUsage = actualSize
                    cacheWarningType = cacheType
                    onCacheWarningConfirm = {
                        if (requestId == cacheLimitRequestId) applyLimit()
                    }
                    cacheSliderRevision++
                    showCacheWarningDialog = true
                } else {
                    applyLimit()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to change cache limit")
                storageOperationFailed = true
                cacheSliderRevision++
            } finally {
                if (requestId == cacheLimitRequestId) isCalculatingSizes = false
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    maxSongCacheSize = Settings.shared.maxSongCacheSize
                    maxImageCacheSize = Settings.shared.maxImageCacheSize
                    maxAnimatedCoverCacheSize = Settings.shared.animatedArtworkCacheSize
                    coroutineScope.launch { refreshStorageSizes() }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PopupVisibility(visible = clearSongCacheDialog) {
        StorageConfirmationDialog(
            title = stringResource(Res.string.clear_song_cache),
            message = stringResource(Res.string.clear_song_cache_dialog),
            onDismiss = { clearSongCacheDialog = false },
            onConfirm = {
                clearSongCacheDialog = false
                launchStorageOperation {
                    storageOperationFailed =
                        !withContext(Dispatchers.IO) {
                            exoplayerDir.deleteRecursively()
                            exoplayerDir.mkdirs()
                            audioCacheManager.clearCache()
                        }
                    refreshStorageSizes()
                }
            },
        )
    }
    PopupVisibility(visible = clearImageCacheDialog) {
        StorageConfirmationDialog(
            title = stringResource(Res.string.clear_image_cache),
            message = stringResource(Res.string.clear_image_cache_dialog),
            onDismiss = { clearImageCacheDialog = false },
            onConfirm = {
                clearImageCacheDialog = false
                launchStorageOperation {
                    withContext(Dispatchers.IO) {
                        imageDiskCache?.clear()
                        context.imageLoader.memoryCache?.clear()
                    }
                    refreshStorageSizes()
                }
            },
        )
    }
    PopupVisibility(visible = clearDownloadsDialog) {
        StorageConfirmationDialog(
            title = stringResource(Res.string.clear_all_downloads),
            message = stringResource(Res.string.clear_all_downloads_dialog),
            onDismiss = { clearDownloadsDialog = false },
            onConfirm = {
                clearDownloadsDialog = false
                launchStorageOperation {
                    storageOperationFailed =
                        !withContext(Dispatchers.IO) {
                            songDownloadManager.clearDownloads()
                        }
                    refreshStorageSizes()
                }
            },
        )
    }

    PopupVisibility(visible = clearAnimatedCoverCacheDialog) {
        StorageConfirmationDialog(
            title = stringResource(Res.string.clear_animated_artwork_cache),
            message = stringResource(Res.string.clear_animated_artwork_cache_dialog),
            onDismiss = { clearAnimatedCoverCacheDialog = false },
            onConfirm = {
                clearAnimatedCoverCacheDialog = false
                launchStorageOperation {
                    try {
                        withContext(Dispatchers.IO) {
                            CanvasCache.get(context).clear()
                            animatedArtworkService.clearCache()
                        }
                        refreshStorageSizes()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to clear animated artwork cache")
                        storageOperationFailed = true
                    }
                }
            },
        )
    }

    PopupVisibility(visible = clearMusicVideoCacheDialog) {
        AlertDialog(
            onDismissRequest = { clearMusicVideoCacheDialog = false },
            title = { Text(stringResource(Res.string.clear_music_video_cache)) },
            text = { Text(stringResource(Res.string.clear_music_video_cache_dialog)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        launchStorageOperation {
                            withContext(Dispatchers.IO) {
                                musicVideoResolver.clearCache()
                            }
                            refreshStorageSizes()
                        }
                        clearMusicVideoCacheDialog = false
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        stringResource(Res.string.clear_music_video_cache),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { clearMusicVideoCacheDialog = false }, shapes = ButtonDefaults.shapes()) {
                    Text(androidStringResource(android.R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    PopupVisibility(visible = showCacheWarningDialog) {
        AlertDialog(
            onDismissRequest = {
                showCacheWarningDialog = false
                cacheSliderRevision++
            },
            title = { Text(stringResource(Res.string.cache_size_warning_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.cache_size_warning_message,
                        formatFileSize(cacheWarningUsage),
                        cacheWarningType,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCacheWarningDialog = false
                        coroutineScope.launch {
                            isCalculatingSizes = true
                            try {
                                onCacheWarningConfirm()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to change cache limit")
                                storageOperationFailed = true
                                cacheSliderRevision++
                            } finally {
                                isCalculatingSizes = false
                            }
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        stringResource(Res.string.continue_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCacheWarningDialog = false
                        cacheSliderRevision++
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(androidStringResource(android.R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    PopupVisibility(visible = migrationFailed || folderAccessFailed) {
        StorageConfirmationDialog(
            title = stringResource(Res.string.error_title),
            message =
                stringResource(
                    if (folderAccessFailed) Res.string.download_folder_access_failed else Res.string.download_migration_failed,
                ),
            onDismiss = {
                migrationFailed = false
                folderAccessFailed = false
            },
            onConfirm = {
                if (folderAccessFailed) {
                    folderAccessFailed = false
                    customFolderPickerLauncher.launch(null)
                } else if (!migrationInProgress) {
                    selectDownloadLocation(Settings.shared.downloadLocation)
                }
            },
            confirmLabel = stringResource(Res.string.retry),
        )
    }

    PopupVisibility(visible = storageOperationFailed) {
        StorageConfirmationDialog(
            title = stringResource(Res.string.error_title),
            message = stringResource(Res.string.error_unknown),
            onDismiss = { storageOperationFailed = false },
            onConfirm = { storageOperationFailed = false },
            confirmLabel = stringResource(Res.string.ok),
        )
    }

    PopupVisibility(visible = showCacheDirectory) {
        AlertDialog(
            onDismissRequest = { showCacheDirectory = false },
            title = { Text(stringResource(Res.string.song_cache_directory)) },
            text = { SelectionContainer { Text(audioCacheManager.cacheDirectoryLabel()) } },
            confirmButton = {
                TextButton(
                    onClick = { showCacheDirectory = false },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.close)) }
            },
        )
    }

    SettingsSubScreen(
        title = stringResource(Res.string.storage),
        onNavigateBack = onNavigateBack,
    ) {
        if (activeStorageOperations > 0 || migrationInProgress) {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        }
        Column(
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                StorageSummary(
                    usedBytes = songCacheSize + imageCacheSize + animatedCoverCacheSize,
                    totalBytes = cacheAllocationBytes(maxSongCacheSize, maxImageCacheSize, maxAnimatedCoverCacheSize),
                    loading = isCalculatingSizes,
                )
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                CacheInfoCard(
                    icon = MetrolistIcons.Outlined.Download,
                    label = stringResource(Res.string.downloads),
                    sizeText = downloadSizeText,
                    progress = downloadProgress,
                    isFirst = true,
                    onClick = { clearDownloadsDialog = true },
                    onClickLabel = stringResource(Res.string.clear_all_downloads),
                )
                CacheInfoCard(
                    icon = MetrolistIcons.Outlined.MusicNote,
                    label = stringResource(Res.string.song_cache),
                    sizeText = songCacheSizeText,
                    progress = songCacheProgress,
                    onClick = { clearSongCacheDialog = true },
                    onClickLabel = stringResource(Res.string.clear_song_cache),
                )
                CacheInfoCard(
                    icon = MetrolistIcons.Outlined.Image,
                    label = stringResource(Res.string.image_cache),
                    sizeText = imageCacheSizeText,
                    progress = imageCacheProgress,
                    onClick = { clearImageCacheDialog = true },
                    onClickLabel = stringResource(Res.string.clear_image_cache),
                )
                CacheInfoCard(
                    icon = MetrolistIcons.Outlined.Equalizer,
                    label = stringResource(Res.string.animated_artwork_cache),
                    sizeText = animatedCoverCacheSizeText,
                    progress = animatedCoverProgress,
                    isLast = true,
                    onClick = { clearAnimatedCoverCacheDialog = true },
                    onClickLabel = stringResource(Res.string.clear_animated_artwork_cache),
                )
            }

            SettingsSubScreenGroup(
                title = stringResource(Res.string.downloads),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SettingsSubScreenItemRow(
                        item =
                            SettingsSubScreenItem(
                                title = stringResource(Res.string.downloaded_songs),
                                description =
                                    settingsPageSubtitle(
                                        stringResource(Res.string.library_offline_songs),
                                        "$downloadSizeText/${stringResource(Res.string.unlimited)}",
                                    ),
                                onClick = onNavigateToDownloads,
                            ),
                        isFirst = true,
                        isLast = false,
                    )
                    val isCustomLocation = downloadLocation == AudioCacheManager.LOCATION_CUSTOM
                    SettingsSubScreenItemRow(
                        item =
                            listChooserSettingsItem(
                                title = stringResource(Res.string.download_location),
                                description =
                                    when (downloadLocation) {
                                        AudioCacheManager.LOCATION_SHARED_MEDIA -> {
                                            audioCacheManager.downloadDirectoryLabel(
                                                stringResource(Res.string.song_cache_location_internal),
                                            )
                                        }

                                        AudioCacheManager.LOCATION_CUSTOM -> {
                                            val customPath = Settings.shared.customDownloadPath
                                            if (customPath.isNotBlank()) {
                                                AudioCacheManager.formatDocumentTreeUriLabel(
                                                    customPath,
                                                    stringResource(Res.string.song_cache_location_internal),
                                                )
                                            } else {
                                                stringResource(Res.string.song_cache_location_custom_desc)
                                            }
                                        }

                                        else -> {
                                            stringResource(Res.string.song_cache_location_internal_desc)
                                        }
                                    },
                                options =
                                    listOf(
                                        AudioCacheManager.LOCATION_INTERNAL,
                                        AudioCacheManager.LOCATION_SHARED_MEDIA,
                                        AudioCacheManager.LOCATION_CUSTOM,
                                    ),
                                selectedOption = downloadLocation,
                                optionToString = {
                                    when (it) {
                                        AudioCacheManager.LOCATION_SHARED_MEDIA -> {
                                            stringResource(
                                                Res.string.song_cache_location_shared_media,
                                            )
                                        }

                                        AudioCacheManager.LOCATION_CUSTOM -> {
                                            stringResource(Res.string.song_cache_location_custom)
                                        }

                                        else -> {
                                            stringResource(Res.string.song_cache_location_internal)
                                        }
                                    }
                                },
                                onOptionSelected = { location ->
                                    when (location) {
                                        AudioCacheManager.LOCATION_CUSTOM -> {
                                            customFolderPickerLauncher.launch(null)
                                        }

                                        AudioCacheManager.LOCATION_SHARED_MEDIA -> {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                                                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                                                PackageManager.PERMISSION_GRANTED
                                            ) {
                                                selectDownloadLocation(location)
                                            } else {
                                                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                            }
                                        }

                                        else -> {
                                            selectDownloadLocation(location)
                                        }
                                    }
                                },
                            ),
                        isFirst = false,
                        isLast = !isCustomLocation,
                    )
                    if (isCustomLocation) {
                        SettingsSubScreenItemRow(
                            item =
                                SettingsSubScreenItem(
                                    title = stringResource(Res.string.change_folder),
                                    description = stringResource(Res.string.settings_download_folder_summary),
                                    onClick = { customFolderPickerLauncher.launch(null) },
                                ),
                            isFirst = false,
                            isLast = true,
                        )
                    }
                }
            }

            SettingsSubScreenGroup(
                title = stringResource(Res.string.music_video_quality),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SettingsSubScreenItemRow(
                        item =
                            listChooserSettingsItem(
                                title = stringResource(Res.string.music_video_quality),
                                description = stringResource(Res.string.music_video_quality_desc),
                                options = MusicVideoQuality.entries,
                                selectedOption = musicVideoQuality,
                                optionToString = {
                                    when (it) {
                                        MusicVideoQuality.AUTO -> stringResource(Res.string.music_video_quality_auto)
                                        else -> stringResource(Res.string.music_video_quality_resolution, it.name.drop(1))
                                    }
                                },
                                onOptionSelected = { option ->
                                    Settings.shared.musicVideoQuality = option
                                    musicVideoQuality = option
                                },
                            ),
                        isFirst = true,
                        isLast = false,
                    )
                    SettingsSubScreenItemRow(
                        item =
                            SettingsSubScreenItem(
                                title = stringResource(Res.string.clear_music_video_cache),
                                description = stringResource(Res.string.clear_music_video_cache_desc),
                                onClick = { clearMusicVideoCacheDialog = true },
                            ),
                        isFirst = false,
                        isLast = true,
                    )
                }
            }

            SettingsSubScreenGroup(
                title = stringResource(Res.string.song_cache),
            ) {
                val songCacheValues = SongCacheLimitsMb

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CacheSliderCard(
                        revision = cacheSliderRevision,
                        title = stringResource(Res.string.max_song_cache_size),
                        subtitle =
                            when (maxSongCacheSize) {
                                0 -> "$songCacheSizeText/${stringResource(Res.string.disable)}"
                                -1 -> "$songCacheSizeText/${stringResource(Res.string.unlimited)}"
                                else -> "$songCacheSizeText/${formatFileSize(maxSongCacheSize * 1024 * 1024L)}"
                            },
                        value = songCacheLimitIndex(maxSongCacheSize).toFloat(),
                        onValueChangeFinished = {
                            val newValue = songCacheValues[it.roundToInt()]
                            requestCacheLimit(
                                newValue = newValue,
                                cacheType = songCacheTypeString,
                                readSize = ::readSongCacheSize,
                                updateSize = { it -> songCacheSize = it },
                                applyLimit = {
                                    Settings.shared.maxSongCacheSize = newValue
                                    maxSongCacheSize = newValue
                                    audioCacheManager.enforceCacheLimit()
                                    songCacheSize = audioCacheManager.getCacheSizeBytes()
                                },
                            )
                        },
                        steps = songCacheValues.size - 1,
                        enabled = !isCalculatingSizes,
                        isFirst = true,
                        isLast = false,
                    )

                    SettingsSubScreenItemRow(
                        item =
                            listChooserSettingsItem(
                                title = stringResource(Res.string.song_cache_location),
                                description =
                                    when (audioCacheLocation) {
                                        AudioCacheManager.LOCATION_SHARED_MEDIA -> {
                                            stringResource(
                                                Res.string.song_cache_location_shared_media_desc,
                                            )
                                        }

                                        else -> {
                                            stringResource(Res.string.song_cache_location_internal_desc)
                                        }
                                    },
                                options = listOf(AudioCacheManager.LOCATION_INTERNAL, AudioCacheManager.LOCATION_SHARED_MEDIA),
                                selectedOption = audioCacheLocation,
                                optionToString = {
                                    stringResource(
                                        if (it ==
                                            AudioCacheManager.LOCATION_SHARED_MEDIA
                                        ) {
                                            Res.string.song_cache_location_shared_media
                                        } else {
                                            Res.string.song_cache_location_internal
                                        },
                                    )
                                },
                                onOptionSelected = { location ->
                                    Settings.shared.audioCacheLocation = location
                                    audioCacheLocation = Settings.shared.audioCacheLocation
                                },
                            ),
                        isFirst = false,
                        isLast = false,
                    )

                    SettingsSubScreenItemRow(
                        item =
                            SettingsSubScreenItem(
                                title = stringResource(Res.string.song_cache_directory),
                                description = audioCacheManager.cacheDirectoryLabel(),
                                onClick = { showCacheDirectory = true },
                            ),
                        isFirst = false,
                        isLast = true,
                    )
                }
            }

            SettingsSubScreenGroup(
                title = stringResource(Res.string.image_cache),
            ) {
                val imageCacheValues = remember { listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192) }

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CacheSliderCard(
                        revision = cacheSliderRevision,
                        title = stringResource(Res.string.max_image_cache_size),
                        subtitle =
                            when (maxImageCacheSize) {
                                0 -> "$imageCacheSizeText/${stringResource(Res.string.disable)}"
                                else -> "$imageCacheSizeText/${formatFileSize(maxImageCacheSize * 1024 * 1024L)}"
                            },
                        value =
                            imageCacheValues
                                .indexOf(maxImageCacheSize)
                                .toFloat()
                                .coerceIn(0f, (imageCacheValues.size - 1).toFloat()),
                        onValueChangeFinished = {
                            val newValue = imageCacheValues[it.roundToInt()]
                            requestCacheLimit(
                                newValue = newValue,
                                cacheType = imageCacheTypeString,
                                readSize = ::readImageCacheSize,
                                updateSize = { it -> imageCacheSize = it },
                                applyLimit = {
                                    withContext(Dispatchers.IO) {
                                        (imageDiskCache as ResizableImageDiskCache).resize(newValue * 1024L * 1024L)
                                        Settings.shared.maxImageCacheSize = newValue
                                    }
                                    maxImageCacheSize = newValue
                                    imageCacheSize = readImageCacheSize()
                                },
                            )
                        },
                        steps = imageCacheValues.size - 1,
                        enabled = !isCalculatingSizes,
                        isFirst = true,
                        isLast = true,
                    )
                }
            }

            SettingsSubScreenGroup(
                title = stringResource(Res.string.animated_artwork_cache),
            ) {
                val animatedCoverCacheValues =
                    remember(maxAnimatedCoverCacheSize) {
                        (listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192, 16384) + maxAnimatedCoverCacheSize).distinct().sorted()
                    }

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CacheSliderCard(
                        revision = cacheSliderRevision,
                        title = stringResource(Res.string.max_animated_artwork_cache_size),
                        subtitle =
                            when (maxAnimatedCoverCacheSize) {
                                0 -> "$animatedCoverCacheSizeText/${stringResource(Res.string.disable)}"

                                else -> "$animatedCoverCacheSizeText/${formatFileSize(
                                    maxAnimatedCoverCacheSize * 1024 * 1024L,
                                )}"
                            },
                        value =
                            animatedCoverCacheValues
                                .indexOf(maxAnimatedCoverCacheSize)
                                .toFloat()
                                .coerceIn(0f, (animatedCoverCacheValues.size - 1).toFloat()),
                        onValueChangeFinished = {
                            val newValue = animatedCoverCacheValues[it.roundToInt()]
                            requestCacheLimit(
                                newValue = newValue,
                                cacheType = animatedCoverCacheTypeString,
                                readSize = ::readAnimatedCoverCacheSize,
                                updateSize = { it -> animatedCoverCacheSize = it },
                                applyLimit = {
                                    withContext(Dispatchers.IO) {
                                        CanvasCache.get(context).setLimit(newValue)
                                        Settings.shared.animatedArtworkCacheSize = newValue
                                    }
                                    maxAnimatedCoverCacheSize = newValue
                                    animatedCoverCacheSize = readAnimatedCoverCacheSize()
                                },
                            )
                        },
                        steps = animatedCoverCacheValues.size - 1,
                        enabled = !isCalculatingSizes,
                        isFirst = true,
                        isLast = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun CacheInfoCard(
    icon: ImageVector,
    label: String,
    sizeText: String,
    progress: Float,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    StorageUsageRow(icon, label, sizeText, progress, isFirst, isLast, onClick, onClickLabel)
}

@Composable
private fun CacheSliderCard(
    title: String,
    subtitle: String,
    value: Float,
    onValueChangeFinished: (Float) -> Unit,
    steps: Int,
    revision: Int,
    enabled: Boolean = true,
    isFirst: Boolean = false,
    isLast: Boolean = false,
) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        StorageSliderRow(title, subtitle, value, onValueChangeFinished, steps, revision, enabled, isFirst, isLast)
    }
}

private fun File.calculateDirectorySize(): Long {
    if (!exists()) return 0L
    var size = 0L
    listFiles()?.forEach { file ->
        size +=
            if (file.isDirectory) {
                file.calculateDirectorySize()
            } else {
                file.length()
            }
    }
    return size
}
