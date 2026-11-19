package com.metrolist.android.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
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

private val lyricsAnimationMutex = Mutex()

suspend fun shareLyricsAnimation(context: Context, image: ImageBitmap, title: String): Boolean =
    shareLyricsAnimation(context,LyricsAnimationExport(6000,{ 0 },{ image }),title)

suspend fun shareLyricsAnimation(context: Context, request: LyricsAnimationExport, title: String): Boolean = lyricsAnimationMutex.withLock {
    val frameCount = lyricsVideoFrameCount(request.durationMs,24)
    var staged: File? = null
    var launched = false
    try {
        withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            val directory = File(context.cacheDir,"images")
            check(directory.isDirectory || directory.mkdirs())
            val now = System.currentTimeMillis()
            val previous = directory.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("lyrics_") && it.extension == "mp4" }
            previous.filter { now-it.lastModified() > 24*60*60*1000L }.forEach { it.delete() }
            check(previous.filter { it.exists() }.sumOf { it.length() } <= 112L*1024*1024) { "Export cache is full" }
            val output = File.createTempFile("lyrics_",".mp4",directory).also { staged = it }
            LyricsFrameCompositor(request,720).use { compositor ->
                encodeLyricsMp4(output,720,720,24,frameCount) { canvas,index,_ ->
                    compositor.draw(canvas,(lyricsFrameTimeUs(index,24)/1000).coerceAtMost(request.durationMs-1))
                }
            }
        }
        withContext(Dispatchers.Main) {
            currentCoroutineContext().ensureActive()
            val uri = FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",checkNotNull(staged))
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
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
