package com.metrolist.shared.ui.settings

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.auto_download_on_like
import com.metrolist.shared.generated.resources.cache_size_warning_message
import com.metrolist.shared.generated.resources.cache_size_warning_title
import com.metrolist.shared.generated.resources.calculating
import com.metrolist.shared.generated.resources.clear
import com.metrolist.shared.generated.resources.clear_all_downloads
import com.metrolist.shared.generated.resources.clear_all_downloads_dialog
import com.metrolist.shared.generated.resources.clear_image_cache
import com.metrolist.shared.generated.resources.clear_image_cache_dialog
import com.metrolist.shared.generated.resources.clear_song_cache
import com.metrolist.shared.generated.resources.clear_song_cache_dialog
import com.metrolist.shared.generated.resources.continue_action
import com.metrolist.shared.generated.resources.default_app_storage
import com.metrolist.shared.generated.resources.disable
import com.metrolist.shared.generated.resources.download_location
import com.metrolist.shared.generated.resources.downloaded_songs
import com.metrolist.shared.generated.resources.downloads
import com.metrolist.shared.generated.resources.enable_song_cache
import com.metrolist.shared.generated.resources.enable_song_cache_desc
import com.metrolist.shared.generated.resources.error_title
import com.metrolist.shared.generated.resources.error_unknown
import com.metrolist.shared.generated.resources.image_cache
import com.metrolist.shared.generated.resources.library_offline_songs
import com.metrolist.shared.generated.resources.max_image_cache_size
import com.metrolist.shared.generated.resources.max_song_cache_size
import com.metrolist.shared.generated.resources.ok
import com.metrolist.shared.generated.resources.song_cache
import com.metrolist.shared.generated.resources.unlimited
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.storage.IosAudioDownloadManager
import com.metrolist.shared.storage.ResizableImageDiskCache
import com.metrolist.shared.storage.SongCacheLimitsMb
import com.metrolist.shared.storage.cacheAllocationBytes
import com.metrolist.shared.storage.songCacheLimitIndex
import com.metrolist.shared.storage.storageUsageFraction
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.utils.rememberFileSizeFormatter
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSNumber
import kotlin.math.roundToInt

private data class StorageConfirmation(
    val title: String,
    val message: String,
    val confirmLabel: String? = null,
    val action: suspend () -> Unit,
)

@OptIn(ExperimentalForeignApi::class)
@Composable
fun IosStorageSettingsPage(
    manager: IosAudioDownloadManager,
    onNavigateToDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatFileSize = rememberFileSizeFormatter()
    val scope = rememberCoroutineScope()
    val context = LocalPlatformContext.current
    val imageLoader = remember { SingletonImageLoader.get(context) }
    val imageCache = remember { imageLoader.diskCache as ResizableImageDiskCache }
    val usage by manager.storageUsageUpdates.collectAsState()
    var imageBytes by remember { mutableLongStateOf(0L) }
    var capacity by remember { mutableLongStateOf(0L) }
    var maxCacheMb by remember { mutableIntStateOf(Settings.shared.maxSongCacheSize) }
    var maxImageMb by remember { mutableIntStateOf(Settings.shared.maxImageCacheSize) }
    var cacheEnabled by remember { mutableStateOf(Settings.shared.songCacheEnabled) }
    var autoDownloadOnLike by remember { mutableStateOf(Settings.shared.autoDownloadOnLike) }
    var confirmation by remember { mutableStateOf<StorageConfirmation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var sliderRevision by remember { mutableIntStateOf(0) }
    val cacheLimits = SongCacheLimitsMb
    val imageLimits = remember { listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192) }

    suspend fun refreshImageUsage() {
        val (images, diskCapacity) =
            withContext(Dispatchers.IO) {
                imageCache.size to
                    (
                        NSFileManager.defaultManager
                            .attributesOfFileSystemForPath(imageCache.directory.toString(), error = null)
                            ?.get(NSFileSystemSize) as? NSNumber
                    )?.longLongValue
            }
        imageBytes = images
        capacity = diskCapacity ?: 0L
    }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
                refreshImageUsage()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(manager) {
        try {
            manager.storageUsage()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
        while (true) {
            try {
                refreshImageUsage()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
                break
            }
            delay(2_000)
        }
    }

    val unlimited = stringResource(Res.string.unlimited)
    val disabled = stringResource(Res.string.disable)
    val calculating = stringResource(Res.string.calculating)
    val downloadsTitle = stringResource(Res.string.downloads)
    val songTitle = stringResource(Res.string.song_cache)
    val imageTitle = stringResource(Res.string.image_cache)

    fun limitLabel(value: Int) =
        when (value) {
            -1 -> unlimited
            0 -> disabled
            else -> formatFileSize(value * 1024L * 1024L)
        }

    fun sizeLabel(bytes: Long) = if (usage == null) calculating else formatFileSize(bytes)

    fun requestLimit(
        value: Int,
        image: Boolean,
    ) {
        perform {
            val actual = if (image) withContext(Dispatchers.IO) { imageCache.size } else manager.storageUsage().cacheBytes
            val apply: suspend () -> Unit = {
                if (image) {
                    withContext(Dispatchers.IO) { imageCache.resize(value * 1024L * 1024L) }
                    Settings.shared.maxImageCacheSize = value
                    maxImageMb = value
                } else {
                    Settings.shared.maxSongCacheSize = value
                    maxCacheMb = value
                    manager.enforceCacheLimit()
                }
            }
            if (value >= 0 && actual > value * 1024L * 1024L) {
                sliderRevision++
                confirmation =
                    StorageConfirmation(
                        getString(Res.string.cache_size_warning_title),
                        getString(Res.string.cache_size_warning_message, formatFileSize(actual), if (image) imageTitle else songTitle),
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

    StorageContent(modifier) {
        StorageSummary(
            (usage?.cacheBytes ?: 0L) + imageBytes,
            cacheAllocationBytes(maxCacheMb, maxImageMb),
            loading = usage == null,
        )
        StorageSection {
            StorageUsageRow(
                MetrolistIcons.Outlined.Download,
                downloadsTitle,
                sizeLabel(usage?.downloadBytes ?: 0L),
                storageUsageFraction(usage?.downloadBytes ?: 0L, capacity),
                isFirst = true,
                onClick = {
                    if (!busy) {
                        confirmation =
                            StorageConfirmation(clearDownloads, clearDownloadsMessage) { manager.clearDownloads() }
                    }
                },
                onClickLabel = clearDownloads,
            )
            StorageUsageRow(
                MetrolistIcons.Outlined.MusicNote,
                songTitle,
                sizeLabel(usage?.cacheBytes ?: 0L),
                storageUsageFraction(usage?.cacheBytes ?: 0L, if (maxCacheMb < 0) capacity else maxCacheMb * 1024L * 1024L),
                onClick = { if (!busy) confirmation = StorageConfirmation(clearSongs, clearSongsMessage) { manager.clearCache() } },
                onClickLabel = clearSongs,
            )
            StorageUsageRow(
                MetrolistIcons.Outlined.Image,
                imageTitle,
                sizeLabel(imageBytes),
                storageUsageFraction(imageBytes, maxImageMb * 1024L * 1024L),
                isLast = true,
                onClick = {
                    if (!busy) {
                        confirmation =
                            StorageConfirmation(clearImages, clearImagesMessage) {
                                withContext(Dispatchers.IO) {
                                    imageCache.clear()
                                    imageLoader.memoryCache?.clear()
                                }
                            }
                    }
                },
                onClickLabel = clearImages,
            )
        }
        StorageSection(downloadsTitle) {
            StorageSettingRow(
                stringResource(Res.string.downloaded_songs),
                settingsPageSubtitle(
                    stringResource(Res.string.library_offline_songs),
                    "${sizeLabel(usage?.downloadBytes ?: 0L)}/$unlimited",
                ),
                isFirst = true,
                onClick = onNavigateToDownloads,
            )
            StorageSettingRow(stringResource(Res.string.download_location), stringResource(Res.string.default_app_storage))
            StorageSettingRow(
                stringResource(Res.string.auto_download_on_like),
                checked = autoDownloadOnLike,
                onClick = {
                    autoDownloadOnLike = !autoDownloadOnLike
                    Settings.shared.autoDownloadOnLike = autoDownloadOnLike
                },
                isLast = true,
                trailingContent = { Switch(autoDownloadOnLike, onCheckedChange = null) },
            )
        }
        StorageSection(songTitle) {
            StorageSettingRow(
                stringResource(Res.string.enable_song_cache),
                stringResource(Res.string.enable_song_cache_desc),
                isFirst = true,
                checked = cacheEnabled,
                onClick = {
                    cacheEnabled = !cacheEnabled
                    Settings.shared.songCacheEnabled = cacheEnabled
                },
                trailingContent = { Switch(cacheEnabled, onCheckedChange = null) },
            )
            StorageSliderRow(
                stringResource(Res.string.max_song_cache_size),
                "${sizeLabel(usage?.cacheBytes ?: 0L)}/${limitLabel(maxCacheMb)}",
                songCacheLimitIndex(maxCacheMb).toFloat(),
                { requestLimit(cacheLimits[it.roundToInt()], image = false) },
                cacheLimits.lastIndex,
                revision = sliderRevision,
                enabled = !busy && usage != null,
                isFirst = false,
                isLast = true,
            )
        }
        StorageSection(imageTitle) {
            StorageSliderRow(
                stringResource(Res.string.max_image_cache_size),
                "${sizeLabel(imageBytes)}/${limitLabel(maxImageMb)}",
                imageLimits.indexOf(maxImageMb).coerceAtLeast(0).toFloat(),
                { requestLimit(imageLimits[it.roundToInt()], image = true) },
                imageLimits.lastIndex,
                revision = sliderRevision,
                enabled = !busy && usage != null,
                isLast = true,
            )
        }
    }
    confirmation?.let { prompt ->
        StorageConfirmationDialog(
            prompt.title,
            prompt.message,
            onDismiss = {
                confirmation = null
                sliderRevision++
            },
            onConfirm = {
                confirmation = null
                perform(prompt.action)
            },
            confirmLabel = prompt.confirmLabel ?: stringResource(Res.string.clear),
        )
    }
    if (failed) {
        StorageConfirmationDialog(
            stringResource(Res.string.error_title),
            stringResource(Res.string.error_unknown),
            onDismiss = { failed = false },
            onConfirm = { failed = false },
            confirmLabel = stringResource(Res.string.ok),
        )
    }
}
