package com.metrolist.desktop.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.SongCacheManager
import com.metrolist.desktop.utils.DesktopImageLoader
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.animated_artwork_cache
import com.metrolist.shared.generated.resources.cache_size_warning_message
import com.metrolist.shared.generated.resources.cache_size_warning_title
import com.metrolist.shared.generated.resources.cached_resolutions
import com.metrolist.shared.generated.resources.calculating
import com.metrolist.shared.generated.resources.change_folder
import com.metrolist.shared.generated.resources.clear
import com.metrolist.shared.generated.resources.clear_all_downloads
import com.metrolist.shared.generated.resources.clear_all_downloads_dialog
import com.metrolist.shared.generated.resources.clear_animated_artwork_cache
import com.metrolist.shared.generated.resources.clear_animated_artwork_cache_dialog
import com.metrolist.shared.generated.resources.clear_image_cache
import com.metrolist.shared.generated.resources.clear_image_cache_dialog
import com.metrolist.shared.generated.resources.clear_music_video_cache
import com.metrolist.shared.generated.resources.clear_music_video_cache_dialog
import com.metrolist.shared.generated.resources.clear_song_cache
import com.metrolist.shared.generated.resources.clear_song_cache_dialog
import com.metrolist.shared.generated.resources.continue_action
import com.metrolist.shared.generated.resources.disable
import com.metrolist.shared.generated.resources.download_folder_unavailable
import com.metrolist.shared.generated.resources.download_location
import com.metrolist.shared.generated.resources.downloaded_songs
import com.metrolist.shared.generated.resources.downloads
import com.metrolist.shared.generated.resources.enable_song_cache
import com.metrolist.shared.generated.resources.enable_song_cache_desc
import com.metrolist.shared.generated.resources.error_title
import com.metrolist.shared.generated.resources.error_unknown
import com.metrolist.shared.generated.resources.image_cache
import com.metrolist.shared.generated.resources.library_offline_songs
import com.metrolist.shared.generated.resources.max_animated_artwork_cache_size
import com.metrolist.shared.generated.resources.max_image_cache_size
import com.metrolist.shared.generated.resources.max_song_cache_size
import com.metrolist.shared.generated.resources.music_video_cache
import com.metrolist.shared.generated.resources.ok
import com.metrolist.shared.generated.resources.reset
import com.metrolist.shared.generated.resources.select_download_directory
import com.metrolist.shared.generated.resources.settings_download_folder_summary
import com.metrolist.shared.generated.resources.settings_reset_download_folder_summary
import com.metrolist.shared.generated.resources.song_cache
import com.metrolist.shared.generated.resources.unlimited
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.storage.DesktopAnimatedArtworkCache
import com.metrolist.shared.storage.ResizableImageDiskCache
import com.metrolist.shared.storage.SongCacheLimitsMb
import com.metrolist.shared.storage.cacheAllocationBytes
import com.metrolist.shared.storage.songCacheLimitIndex
import com.metrolist.shared.storage.storageUsageFraction
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.settings.StorageConfirmationDialog
import com.metrolist.shared.ui.settings.StorageSection
import com.metrolist.shared.ui.settings.StorageSettingRow
import com.metrolist.shared.ui.settings.StorageSliderRow
import com.metrolist.shared.ui.settings.StorageSummary
import com.metrolist.shared.ui.settings.StorageUsageRow
import com.metrolist.shared.ui.settings.settingsPageSubtitle
import com.metrolist.shared.ui.utils.rememberFileSizeFormatter
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import java.io.File
import javax.swing.JFileChooser
import kotlin.math.roundToInt

private data class StorageUsage(
    val downloads: Long,
    val songs: Long,
    val images: Long,
    val animatedArtwork: Long,
    val capacity: Long,
    val directory: String,
    val videoEntries: Int,
)

private enum class CacheType { SONG, IMAGE, ANIMATED_ARTWORK }

private data class StorageConfirmation(
    val title: String,
    val message: String,
    val confirmLabel: String? = null,
    val action: suspend () -> Unit,
)

@Composable
fun StorageSettingsScreen(
    colorScheme: ColorScheme,
    onNavigateToDownloads: () -> Unit,
) {
    val formatFileSize = rememberFileSizeFormatter()
    val scope = rememberCoroutineScope()
    val context = LocalPlatformContext.current
    val imageCache = remember { DesktopImageLoader.create(context).diskCache as ResizableImageDiskCache }
    val resolver: MusicVideoResolver = koinInject()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var maxSongMb by remember { mutableStateOf<Long?>(null) }
    var maxImageMb by remember { mutableIntStateOf(Settings.shared.maxImageCacheSize) }
    var maxAnimatedMb by remember { mutableIntStateOf(Settings.shared.animatedArtworkCacheSize) }
    val animatedLimits =
        remember(maxAnimatedMb) {
            (
                listOf(0L, 128L, 256L, 512L, 1024L, 2048L, 4096L, 8192L, 16384L) +
                    maxAnimatedMb.toLong()
            ).distinct().sorted()
        }
    var confirmation by remember { mutableStateOf<StorageConfirmation?>(null) }
    var failureMessage by remember { mutableStateOf<StringResource?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sliderRevision by remember { mutableIntStateOf(0) }
    val songLimits = remember { SongCacheLimitsMb.map(Int::toLong) }
    val imageLimits = remember { listOf(0L, 128L, 256L, 512L, 1024L, 2048L, 4096L, 8192L) }

    suspend fun refreshUsage(scanDownloads: Boolean = true) {
        val knownDownloads = usage?.downloads
        if (!scanDownloads && knownDownloads == null) return
        val snapshot =
            withContext(Dispatchers.IO) {
                val directory = DownloadManager.getDownloadDir()
                StorageUsage(
                    downloads =
                        if (scanDownloads) {
                            DownloadManager.downloadedFiles.value.sumOf {
                                File(it.audioFilePath).length() + (it.thumbnailFilePath?.let { path -> File(path).length() } ?: 0L)
                            }
                        } else {
                            checkNotNull(knownDownloads)
                        },
                    songs = SongCacheManager.getCacheSizeBytes(),
                    images = imageCache.size,
                    animatedArtwork = DesktopAnimatedArtworkCache.get().sizeBytes(),
                    capacity = directory.totalSpace,
                    directory = directory.absolutePath,
                    videoEntries = resolver.getCacheEntryCount(),
                ) to SongCacheManager.getMaxCacheSizeMB()
            }
        usage = if (scanDownloads) snapshot.first else snapshot.first.copy(downloads = checkNotNull(usage).downloads)
        maxSongMb = snapshot.second
    }

    fun perform(
        errorMessage: StringResource = Res.string.error_unknown,
        action: suspend () -> Unit,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
                refreshUsage()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failureMessage = errorMessage
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        DownloadManager.downloadedFiles.collect {
            try {
                refreshUsage()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failureMessage = Res.string.error_unknown
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            try {
                refreshUsage(scanDownloads = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failureMessage = Res.string.error_unknown
            }
        }
    }

    val calculating = stringResource(Res.string.calculating)
    val unlimited = stringResource(Res.string.unlimited)
    val disabled = stringResource(Res.string.disable)
    val downloadsTitle = stringResource(Res.string.downloads)
    val songTitle = stringResource(Res.string.song_cache)
    val imageTitle = stringResource(Res.string.image_cache)
    val animatedTitle = stringResource(Res.string.animated_artwork_cache)

    fun limitLabel(value: Long) =
        when (value) {
            -1L -> unlimited
            0L -> disabled
            else -> formatFileSize(value * 1024 * 1024)
        }

    fun sizeLabel(bytes: Long?) = bytes?.let(formatFileSize) ?: calculating

    fun requestLimit(
        value: Long,
        type: CacheType,
    ) {
        perform {
            val actual =
                withContext(Dispatchers.IO) {
                    when (type) {
                        CacheType.IMAGE -> imageCache.size
                        CacheType.SONG -> SongCacheManager.getCacheSizeBytes()
                        CacheType.ANIMATED_ARTWORK -> DesktopAnimatedArtworkCache.get().sizeBytes()
                    }
                }
            val title =
                when (type) {
                    CacheType.IMAGE -> imageTitle
                    CacheType.SONG -> songTitle
                    CacheType.ANIMATED_ARTWORK -> animatedTitle
                }
            val apply: suspend () -> Unit = {
                withContext(Dispatchers.IO) {
                    when (type) {
                        CacheType.IMAGE -> {
                            imageCache.resize(value * 1024 * 1024)
                            Settings.shared.maxImageCacheSize = value.toInt()
                        }

                        CacheType.SONG -> {
                            SongCacheManager.setMaxCacheSizeMB(value)
                        }

                        CacheType.ANIMATED_ARTWORK -> {
                            DesktopAnimatedArtworkCache.get().resize(value * 1024 * 1024)
                            Settings.shared.animatedArtworkCacheSize = value.toInt()
                        }
                    }
                }
                when (type) {
                    CacheType.IMAGE -> maxImageMb = value.toInt()
                    CacheType.SONG -> maxSongMb = value
                    CacheType.ANIMATED_ARTWORK -> maxAnimatedMb = value.toInt()
                }
            }
            if (value >= 0 && actual > value * 1024 * 1024) {
                sliderRevision++
                confirmation =
                    StorageConfirmation(
                        getString(Res.string.cache_size_warning_title),
                        getString(Res.string.cache_size_warning_message, formatFileSize(actual), title),
                        getString(Res.string.continue_action),
                        apply,
                    )
            } else {
                apply()
            }
        }
    }

    val clearDownloads = stringResource(Res.string.clear_all_downloads)
    val clearDownloadsMessage = stringResource(Res.string.clear_all_downloads_dialog)
    val clearSongs = stringResource(Res.string.clear_song_cache)
    val clearSongsMessage = stringResource(Res.string.clear_song_cache_dialog)
    val clearImages = stringResource(Res.string.clear_image_cache)
    val clearImagesMessage = stringResource(Res.string.clear_image_cache_dialog)
    val clearAnimated = stringResource(Res.string.clear_animated_artwork_cache)
    val clearAnimatedMessage = stringResource(Res.string.clear_animated_artwork_cache_dialog)

    MaterialTheme(colorScheme = colorScheme) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScrollWithDrag(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            StorageSummary(
                usedBytes = usage?.let { it.songs + it.images + it.animatedArtwork } ?: 0L,
                totalBytes = maxSongMb?.let { cacheAllocationBytes(it.toInt(), maxImageMb, maxAnimatedMb) },
                loading = usage == null,
            )
            StorageSection {
                StorageUsageRow(
                    MetrolistIcons.Outlined.Download,
                    downloadsTitle,
                    sizeLabel(usage?.downloads),
                    storageUsageFraction(usage?.downloads ?: 0L, usage?.capacity ?: 0L),
                    isFirst = true,
                    onClick = {
                        if (!busy) {
                            confirmation =
                                StorageConfirmation(clearDownloads, clearDownloadsMessage) {
                                    withContext(Dispatchers.IO) { DownloadManager.clearAllDownloads() }
                                }
                        }
                    },
                    onClickLabel = clearDownloads,
                )
                StorageUsageRow(
                    MetrolistIcons.Outlined.MusicNote,
                    songTitle,
                    sizeLabel(usage?.songs),
                    storageUsageFraction(usage?.songs ?: 0L, maxSongMb?.takeIf { it >= 0 }?.times(1024 * 1024) ?: 0L),
                    onClick = {
                        if (!busy) {
                            confirmation =
                                StorageConfirmation(clearSongs, clearSongsMessage) {
                                    withContext(Dispatchers.IO) { SongCacheManager.clearCache() }
                                }
                        }
                    },
                    onClickLabel = clearSongs,
                )
                StorageUsageRow(
                    MetrolistIcons.Outlined.Image,
                    imageTitle,
                    sizeLabel(usage?.images),
                    storageUsageFraction(usage?.images ?: 0L, maxImageMb * 1024L * 1024),
                    onClick = {
                        if (!busy) {
                            confirmation =
                                StorageConfirmation(clearImages, clearImagesMessage) {
                                    withContext(Dispatchers.IO) { DesktopImageLoader.clearCache() }
                                }
                        }
                    },
                    onClickLabel = clearImages,
                )
                StorageUsageRow(
                    MetrolistIcons.Outlined.Equalizer,
                    animatedTitle,
                    sizeLabel(usage?.animatedArtwork),
                    storageUsageFraction(usage?.animatedArtwork ?: 0L, maxAnimatedMb * 1024L * 1024),
                    onClick = {
                        if (!busy) {
                            confirmation =
                                StorageConfirmation(clearAnimated, clearAnimatedMessage) {
                                    withContext(Dispatchers.IO) { DesktopAnimatedArtworkCache.get().clear() }
                                }
                        }
                    },
                    onClickLabel = clearAnimated,
                )
                val clearVideo = stringResource(Res.string.clear_music_video_cache)
                val clearVideoMessage = stringResource(Res.string.clear_music_video_cache_dialog)
                StorageSettingRow(
                    stringResource(Res.string.music_video_cache),
                    usage?.let { stringResource(Res.string.cached_resolutions, it.videoEntries) } ?: calculating,
                    isLast = true,
                    enabled = !busy,
                    onClick = {
                        confirmation =
                            StorageConfirmation(clearVideo, clearVideoMessage) {
                                withContext(Dispatchers.IO) { resolver.clearCache() }
                            }
                    },
                    trailingContent = {
                        Icon(
                            MetrolistIcons.Outlined.Delete,
                            contentDescription = clearVideo,
                        )
                    },
                )
            }
            DesktopStorageSection(downloadsTitle, listOf("download", "storage", "directory", "folder", "path")) {
                StorageSettingRow(
                    stringResource(Res.string.downloaded_songs),
                    settingsPageSubtitle(stringResource(Res.string.library_offline_songs), "${sizeLabel(usage?.downloads)}/$unlimited"),
                    isFirst = true,
                    onClick = onNavigateToDownloads,
                )
                StorageSettingRow(stringResource(Res.string.download_location), usage?.directory ?: calculating)
                val selectTitle = stringResource(Res.string.select_download_directory)
                StorageSettingRow(
                    stringResource(Res.string.change_folder),
                    isLast = Settings.shared.customDownloadPath.isBlank(),
                    enabled = !busy && usage != null,
                    onClick = {
                        val chooser =
                            JFileChooser().apply {
                                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                dialogTitle = selectTitle
                                currentDirectory = usage?.directory?.let(::File)
                            }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            chooser.selectedFile?.let { directory ->
                                perform(
                                    Res.string.download_folder_unavailable,
                                ) { withContext(Dispatchers.IO) { DownloadManager.changeDownloadDirectory(directory) } }
                            }
                        }
                    },
                    subtitle = stringResource(Res.string.settings_download_folder_summary),
                )
                if (Settings.shared.customDownloadPath.isNotBlank()) {
                    StorageSettingRow(stringResource(Res.string.reset), isLast = true, enabled = !busy, onClick = {
                        perform(
                            Res.string.download_folder_unavailable,
                        ) { withContext(Dispatchers.IO) { DownloadManager.changeDownloadDirectory(null) } }
                    }, subtitle = stringResource(Res.string.settings_reset_download_folder_summary))
                }
            }
            DesktopStorageSection(songTitle, listOf("song", "cache")) {
                StorageSettingRow(
                    stringResource(Res.string.enable_song_cache),
                    stringResource(Res.string.enable_song_cache_desc),
                    isFirst = true,
                    checked = Settings.shared.songCacheEnabled,
                    onClick = { Settings.shared.songCacheEnabled = !Settings.shared.songCacheEnabled },
                    trailingContent = { Switch(Settings.shared.songCacheEnabled, onCheckedChange = null) },
                )
                StorageSliderRow(
                    stringResource(Res.string.max_song_cache_size),
                    "${sizeLabel(usage?.songs)}/${maxSongMb?.let(::limitLabel) ?: calculating}",
                    songCacheLimitIndex(maxSongMb?.toInt() ?: 0).toFloat(),
                    { requestLimit(songLimits[it.roundToInt()], type = CacheType.SONG) },
                    songLimits.lastIndex,
                    revision = sliderRevision,
                    enabled = !busy && maxSongMb != null,
                    isFirst = false,
                    isLast = true,
                )
            }
            DesktopStorageSection(imageTitle, listOf("image", "cache")) {
                StorageSliderRow(
                    stringResource(Res.string.max_image_cache_size),
                    "${sizeLabel(usage?.images)}/${limitLabel(maxImageMb.toLong())}",
                    imageLimits.indexOf(maxImageMb.toLong()).coerceAtLeast(0).toFloat(),
                    { requestLimit(imageLimits[it.roundToInt()], type = CacheType.IMAGE) },
                    imageLimits.lastIndex,
                    revision = sliderRevision,
                    enabled = !busy && usage != null,
                    isLast = true,
                )
            }
            DesktopStorageSection(animatedTitle, listOf("animated", "artwork", "canvas", "cache")) {
                StorageSliderRow(
                    stringResource(Res.string.max_animated_artwork_cache_size),
                    "${sizeLabel(usage?.animatedArtwork)}/${limitLabel(maxAnimatedMb.toLong())}",
                    animatedLimits.indexOf(maxAnimatedMb.toLong()).coerceAtLeast(0).toFloat(),
                    { requestLimit(animatedLimits[it.roundToInt()], CacheType.ANIMATED_ARTWORK) },
                    animatedLimits.lastIndex,
                    revision = sliderRevision,
                    enabled = !busy && usage != null,
                    isLast = true,
                )
            }
        }
        PopupContent(confirmation) { prompt ->
            StorageConfirmationDialog(
                prompt.title,
                prompt.message,
                onDismiss = {
                    confirmation = null
                    sliderRevision++
                },
                onConfirm = {
                    confirmation = null
                    perform(action = prompt.action)
                },
                confirmLabel = prompt.confirmLabel ?: stringResource(Res.string.clear),
            )
        }
        PopupContent(failureMessage) { message ->
            StorageConfirmationDialog(
                stringResource(Res.string.error_title),
                stringResource(message),
                onDismiss = { failureMessage = null },
                onConfirm = { failureMessage = null },
                confirmLabel = stringResource(Res.string.ok),
            )
        }
    }
}

@Composable
private fun DesktopStorageSection(
    title: String,
    keywords: List<String>,
    content: @Composable ColumnScope.() -> Unit,
) {
    val query = LocalSettingsSearchQuery.current.trim().lowercase()
    if (query.isEmpty() || title.lowercase().contains(query) || keywords.any { it.contains(query) || query.contains(it) }) {
        StorageSection(title, content)
    }
}
