package com.metrolist.android.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.FileProvider
import com.metrolist.shared.ui.components.lyrics.LyricsAnimationExport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val lyricsGifMutex = Mutex()

suspend fun shareLyricsGif(context: Context, image: ImageBitmap, title: String): Boolean =
    shareLyricsGif(context,LyricsAnimationExport(6000,{ 0 },{ image }),title)

suspend fun shareLyricsGif(context: Context, request: LyricsAnimationExport, title: String): Boolean = lyricsGifMutex.withLock {
    val frameCount = lyricsVideoFrameCount(request.durationMs,10)
    var staged: File? = null
    var launched = false
    try {
        withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            val directory = File(context.cacheDir,"images")
            check(directory.isDirectory || directory.mkdirs())
            val now = System.currentTimeMillis()
            val previous = directory.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("lyricsgif_") && it.extension == "gif" }
            previous.filter { now-it.lastModified() > 24*60*60*1000L }.forEach { it.delete() }
            check(previous.filter { it.exists() }.sumOf { it.length() } <= 112L*1024*1024) { "Export cache is full" }
            val output = File.createTempFile("lyricsgif_",".gif",directory).also { staged = it }
            val coroutineContext = currentCoroutineContext()
            val frame = Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(frame)
                val pixels = IntArray(320*320)
                LyricsFrameCompositor(request,320).use { compositor ->
                    output.outputStream().buffered().use { stream ->
                        val writer = LyricsGifWriter(stream,320,320,checkCancelled = { coroutineContext.ensureActive() })
                        repeat(frameCount) { index ->
                            coroutineContext.ensureActive()
                            compositor.draw(canvas,(index*100L).coerceAtMost(request.durationMs-1))
                            frame.getPixels(pixels,0,320,0,0,320,320)
                            writer.frame(pixels,10)
                        }
                        writer.finish()
                    }
                }
            } finally { frame.recycle() }
        }
        withContext(Dispatchers.Main) {
            currentCoroutineContext().ensureActive()
            val uri = FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",checkNotNull(staged))
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/gif"
                putExtra(Intent.EXTRA_STREAM,uri)
                clipData = ClipData.newRawUri(title,uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send,title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            launched = true
        }
        true
    } catch (error: Throwable) {
        if (!launched) staged?.delete()
        throw error
    }
}
