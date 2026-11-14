package com.metrolist.shared.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.share
import com.metrolist.shared.ui.components.lyrics.LyricsShareDialog
import com.metrolist.shared.ui.settings.IosNativeFiles
import com.metrolist.shared.viewmodels.PlayerViewModel
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIViewController
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
internal fun iosLyricsShareAction(
    viewModel: PlayerViewModel,
    presenter: UIViewController,
): ((String) -> Unit)? {
    val uiState by viewModel.uiState.collectAsState()
    val song = uiState.currentSong ?: return null
    var selectedText by remember(song.id) { mutableStateOf<String?>(null) }
    selectedText?.let { text ->
        LyricsShareDialog(
            text = text,
            songId = song.id,
            title = song.title,
            artist = song.artists.joinToString { it.name },
            artworkUrl = song.displayThumbnail,
            exportLabel = stringResource(Res.string.share),
            onExport = { shareLyricsImage(presenter, it) },
            onShareText = { IosNativeFiles.shareText(presenter, it) },
            onDismiss = { selectedText = null },
        )
    }
    return { selectedText = it }
}

internal fun encodeLyricsPng(image: ImageBitmap): ByteArray =
    Image.makeFromBitmap(image.asSkiaBitmap()).use { skiaImage ->
        checkNotNull(skiaImage.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
    }

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
private suspend fun shareLyricsImage(
    presenter: UIViewController,
    image: ImageBitmap,
): Boolean {
    val bytes = withContext(Dispatchers.Default) { encodeLyricsPng(image) }
    val path = NSTemporaryDirectory() + "lyrics_${NSUUID().UUIDString}.png"
    return suspendCancellableCoroutine { continuation ->
        try {
            bytes.usePinned { pinned ->
                check(NSData.create(pinned.addressOf(0), bytes.size.toULong()).writeToFile(path, atomically = true))
            }
            IosNativeFiles.shareFile(presenter, path) { completed ->
                if (continuation.isActive) continuation.resume(completed)
            }
        } catch (error: Exception) {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}
