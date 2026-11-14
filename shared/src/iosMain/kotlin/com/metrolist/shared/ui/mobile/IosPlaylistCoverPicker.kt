package com.metrolist.shared.ui.mobile

import com.metrolist.shared.ui.settings.IosNativeFiles
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val MAX_IOS_PLAYLIST_COVER_BYTES = 16 * 1024 * 1024

@OptIn(ExperimentalForeignApi::class)
internal suspend fun pickIosPlaylistCover(presenter: UIViewController): ByteArray? =
    suspendCancellableCoroutine { continuation ->
        val delegate =
            PlaylistCoverPickerDelegate { result ->
                if (continuation.isActive) {
                    result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            }
        continuation.invokeOnCancellation { dispatch_async(dispatch_get_main_queue()) { delegate.cancel() } }
        delegate.present(presenter)
    }

@OptIn(ExperimentalForeignApi::class)
private class PlaylistCoverPickerDelegate(
    private val onResult: (Result<ByteArray?>) -> Unit,
) : NSObject(),
    PHPickerViewControllerDelegateProtocol {
    private val controller =
        PHPickerViewController(
            PHPickerConfiguration().apply {
                filter = PHPickerFilter.imagesFilter
                selectionLimit = 1
            },
        )

    fun present(presenter: UIViewController) {
        controller.delegate = this
        var top = presenter
        while (top.presentedViewController != null) top = checkNotNull(top.presentedViewController)
        top.presentViewController(controller, animated = true, completion = null)
    }

    fun cancel() {
        controller.delegate = null
        controller.dismissViewControllerAnimated(true, completion = null)
    }

    override fun picker(
        picker: PHPickerViewController,
        didFinishPicking: List<*>,
    ) {
        val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
        picker.dismissViewControllerAnimated(true) {
            if (provider == null) {
                onResult(Result.success(null))
            } else {
                provider.loadFileRepresentationForTypeIdentifier("public.image") { url, error ->
                    // The provider deletes its temporary file after this callback returns.
                    onResult(
                        runCatching {
                            check(error == null) { error?.localizedDescription.orEmpty() }
                            val bytes = IosNativeFiles.readPickedFile(checkNotNull(url), MAX_IOS_PLAYLIST_COVER_BYTES.toLong())
                            normalizeIosPlaylistCover(bytes)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal fun normalizeIosPlaylistCover(bytes: ByteArray): ByteArray {
    require(bytes.isNotEmpty() && bytes.size <= MAX_IOS_PLAYLIST_COVER_BYTES)
    val image = checkNotNull(bytes.usePinned { UIImage.imageWithData(NSData.create(it.addressOf(0), bytes.size.toULong())) })
    val (width, height) = image.size.useContents { width * image.scale to height * image.scale }
    require(width > 0 && height > 0 && width * height <= 16_777_216)
    val scale = minOf(1.0, 1024.0 / maxOf(width, height))
    val targetWidth = maxOf(1.0, width * scale)
    val targetHeight = maxOf(1.0, height * scale)
    val format = UIGraphicsImageRendererFormat().apply { this.scale = 1.0 }
    val renderer = UIGraphicsImageRenderer(CGSizeMake(targetWidth, targetHeight), format)
    val jpeg =
        renderer.JPEGDataWithCompressionQuality(0.9) {
            image.drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
        }
    require(jpeg.length > 0uL && jpeg.length <= MAX_IOS_PLAYLIST_COVER_BYTES.toULong())
    return checkNotNull(jpeg.bytes).reinterpret<ByteVar>().readBytes(jpeg.length.toInt())
}
