package com.metrolist.shared.ui.settings

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.uikit.LocalUIViewController
import com.metrolist.shared.backup.requireBackupSize
import com.metrolist.shared.settings.IOS_FILE_COPY_CHUNK_BYTES
import com.metrolist.shared.settings.nextIosFileCopyChunkSize
import com.metrolist.shared.ui.theme.IosFontManager
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVarOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.writeData
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode.UIDocumentPickerModeImport
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
internal object IosNativeFiles {
    private var documentPickerDelegate: DocumentPickerDelegate? = null
    private var documentPicker: UIDocumentPickerViewController? = null

    fun pickFont(
        presenter: UIViewController,
        onResult: (String?) -> Unit,
    ) = pickDocument(presenter, listOf("public.truetype-ttf-font", "public.opentype-font")) { url ->
        onResult(url?.let(IosFontManager::importFont))
    }

    fun pickDocument(
        presenter: UIViewController,
        documentTypes: List<String>,
        onPicked: (NSURL?) -> Unit,
    ) {
        val picker = UIDocumentPickerViewController(documentTypes = documentTypes, inMode = UIDocumentPickerModeImport)
        documentPicker = picker
        documentPickerDelegate =
            DocumentPickerDelegate { url ->
                documentPickerDelegate = null
                documentPicker = null
                onPicked(url)
            }
        picker.delegate = documentPickerDelegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    fun cancelDocumentPicker() {
        documentPicker?.dismissViewControllerAnimated(true, completion = null)
        documentPickerDelegate = null
        documentPicker = null
    }

    @OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
    fun readPickedFile(
        url: NSURL,
        maximumBytes: Long,
    ): ByteArray =
        withSecurityScopedAccess(url) {
            val filePath = requireNotNull(url.path)
            require(maximumBytes in 1..Int.MAX_VALUE.toLong())
            val size = fileSize(filePath)
            require(size in 1..maximumBytes)
            val bytes = ByteArray(size.toInt())
            val file = NSFileHandle.fileHandleForReadingAtPath(filePath) ?: error("Could not open the selected file")
            try {
                var offset = 0
                while (offset < bytes.size) {
                    val data = readFileChunk(file, minOf(IOS_FILE_COPY_CHUNK_BYTES, bytes.size - offset).toULong())
                    val count = data.length.toInt()
                    if (count == 0) break
                    val chunk = data.bytes?.reinterpret<ByteVarOf<Byte>>() ?: error("Document data is unavailable")
                    chunk.readBytes(count).copyInto(bytes, offset)
                    offset += count
                }
                check(offset == bytes.size && readFileChunk(file, 1uL).length == 0uL) { "Selected file changed while being read" }
                bytes
            } finally {
                file.closeFile()
            }
        }

    fun copyPickedFile(
        url: NSURL,
        maximumBytes: Long,
    ): String =
        withSecurityScopedAccess(url) {
            val sourcePath = requireNotNull(url.path)
            val sourceSize = fileSize(sourcePath)
            requireBackupSize(sourceSize, maximumBytes)
            require(sourceSize > 0)
            val destinationPath = NSTemporaryDirectory() + "metrolist-import-${NSUUID().UUIDString}"
            try {
                check(NSFileManager.defaultManager.createFileAtPath(destinationPath, contents = null, attributes = null)) {
                    "Could not create a copy of the selected file"
                }
                val source = NSFileHandle.fileHandleForReadingAtPath(sourcePath) ?: error("Could not open the selected file")
                try {
                    val destination = NSFileHandle.fileHandleForWritingAtPath(destinationPath) ?: error("Could not create a file copy")
                    try {
                        var copiedBytes = 0L
                        while (true) {
                            val data = readFileChunk(source, nextIosFileCopyChunkSize(copiedBytes, sourceSize).toULong())
                            val count = data.length.toLong()
                            if (count == 0L) break
                            check(count <= sourceSize - copiedBytes) { "Selected file changed while being copied" }
                            check(destination.writeData(data, error = null)) { "Could not copy the selected file" }
                            copiedBytes += count
                        }
                        check(copiedBytes == sourceSize) { "Selected file changed while being copied" }
                    } finally {
                        destination.closeFile()
                    }
                } finally {
                    source.closeFile()
                }
                destinationPath
            } catch (error: Throwable) {
                NSFileManager.defaultManager.removeItemAtPath(destinationPath, error = null)
                throw error
            }
        }

    @OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
    fun shareTextFile(
        presenter: UIViewController,
        fileName: String,
        text: String,
    ) {
        if (!fileName.matches(Regex("[A-Za-z0-9._-]+"))) return
        val path = NSTemporaryDirectory() + fileName
        val bytes = text.encodeToByteArray()
        if (bytes.isEmpty()) return
        val written =
            bytes.usePinned { pinned ->
                NSData.create(pinned.addressOf(0), bytes.size.toULong()).writeToFile(path, atomically = true)
            }
        if (written) shareFile(presenter, path)
    }

    @OptIn(BetaInteropApi::class)
    suspend fun shareImage(
        presenter: UIViewController,
        image: ImageBitmap,
    ): Boolean {
        val path = NSTemporaryDirectory() + "metrolist-statistics-${NSUUID().UUIDString}.png"
        try {
            withContext(Dispatchers.Default) {
                Image.makeFromBitmap(image.asSkiaBitmap()).use { encodedImage ->
                    requireNotNull(encodedImage.encodeToData(EncodedImageFormat.PNG)).use { data ->
                        val bytes = data.bytes
                        bytes.usePinned { pinned ->
                            check(NSData.create(pinned.addressOf(0), bytes.size.toULong()).writeToFile(path, atomically = true))
                        }
                    }
                }
            }
            return suspendCancellableCoroutine { continuation ->
                val visiblePresenter = generateSequence(presenter) { it.presentedViewController }.last()
                shareFile(visiblePresenter, path) { shared ->
                    if (continuation.isActive) continuation.resume(shared)
                }
            }
        } catch (error: Throwable) {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
            throw error
        }
    }

    fun shareText(
        presenter: UIViewController,
        text: String,
    ) {
        val activity = UIActivityViewController(listOf(text), applicationActivities = null)
        anchorShareSheet(activity, presenter)
        presenter.presentViewController(activity, animated = true, completion = null)
    }

    private fun anchorShareSheet(
        activity: UIActivityViewController,
        presenter: UIViewController,
    ) {
        activity.popoverPresentationController?.let { popover ->
            popover.sourceView = presenter.view
            popover.sourceRect =
                presenter.view.bounds.useContents {
                    CGRectMake(origin.x + size.width / 2, origin.y + size.height / 2, 1.0, 1.0)
                }
            popover.permittedArrowDirections = 0uL
        }
    }

    fun shareFile(
        presenter: UIViewController,
        path: String,
        onFinished: (Boolean) -> Unit = {},
    ) {
        val fileUrl = NSURL.fileURLWithPath(path)
        val activity = UIActivityViewController(listOf(fileUrl), applicationActivities = null)
        anchorShareSheet(activity, presenter)
        activity.completionWithItemsHandler = { _, completed, _, _ ->
            onFinished(completed)
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
        presenter.presentViewController(activity, animated = true, completion = null)
    }

    @OptIn(BetaInteropApi::class)
    private fun readFileChunk(
        file: NSFileHandle,
        length: ULong,
    ): NSData =
        memScoped {
            val readError = alloc<ObjCObjectVar<NSError?>>()
            readError.value = null
            val data = file.readDataUpToLength(length, error = readError.ptr)
            readError.value?.let { error(it.localizedDescription) }
            requireNotNull(data) { "Could not read the selected file" }
        }

    private fun fileSize(path: String): Long =
        NSFileManager.defaultManager
            .attributesOfItemAtPath(path, error = null)
            ?.get(NSFileSize)
            ?.toString()
            ?.toLongOrNull()
            ?: error("Selected file size is unavailable")

    private fun <T> withSecurityScopedAccess(
        url: NSURL,
        block: () -> T,
    ): T {
        val accessed = url.startAccessingSecurityScopedResource()
        return try {
            block()
        } finally {
            if (accessed) url.stopAccessingSecurityScopedResource()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private class DocumentPickerDelegate(
        private val onPicked: (NSURL?) -> Unit,
    ) : NSObject(),
        UIDocumentPickerDelegateProtocol {
        @ObjCSignatureOverride
        override fun documentPicker(
            controller: UIDocumentPickerViewController,
            didPickDocumentsAtURLs: List<*>,
        ) {
            onPicked(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            onPicked(null)
        }
    }
}
