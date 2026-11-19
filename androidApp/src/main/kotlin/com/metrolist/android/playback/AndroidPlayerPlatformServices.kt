package com.metrolist.android.playback

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.media3.datasource.DefaultHttpDataSource
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.android.ui.player.CanvasCache
import com.metrolist.android.ui.player.canvasCacheIdentity
import com.metrolist.android.ui.player.prepareCanvasClips
import com.metrolist.android.ui.theme.PlayerColorExtractor
import com.metrolist.android.utils.artworkUiVisible
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.shared.api.artwork.CanvasContentSource
import com.metrolist.shared.api.artwork.DirectCanvasProvider
import com.metrolist.shared.api.artwork.animatedCoverCacheQuality
import com.metrolist.shared.api.artwork.youtubeCanvasSeekPrerollMs
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.SeekableMediaSource
import com.metrolist.shared.settings.AnimatedCoverResolution
import com.metrolist.shared.settings.MusicVideoQuality
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.YouTubeCanvasMode
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.ui.theme.DefaultThemeColor
import com.metrolist.shared.utils.ShareUrls
import com.metrolist.shared.utils.canonicalArtworkUrl
import com.metrolist.shared.viewmodels.PlayerArtworkColors
import com.metrolist.shared.viewmodels.PlayerPlatformServices
import com.metrolist.shared.viewmodels.PlayerViewModel.MusicVideoCanvas
import com.metrolist.shared.youtube.extraction.models.automaticVideoMaxHeight
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AndroidPlayerPlatformServices(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val directCanvasProvider: DirectCanvasProvider,
) : PlayerPlatformServices {
    override val supportsCanvasPreloading = true
    override val artworkVisible = artworkUiVisible(context)

    override fun onMemoryPressure(trim: () -> Unit) {
        context.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                @Deprecated("Required by ComponentCallbacks on Android versions before API 34")
                override fun onLowMemory() = trim()

                @Suppress("DEPRECATION")
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trim()
                }
            },
        )
    }

    override suspend fun extractArtworkColors(
        songId: String,
        thumbnailUrl: String,
    ): PlayerArtworkColors? = PlayerColorExtractor.extractColors(context, imageLoader, thumbnailUrl, DefaultThemeColor)

    override fun shareText(text: String) {
        val intent =
            Intent().apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override suspend fun shareSongToStory(song: SongItem) {
        val artwork =
            song.thumbnail?.let { url ->
                try {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(url)
                            .allowHardware(false)
                            .build()
                    (imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
        val artist = song.artists.joinToString(", ") { it.name }
        val storyImage = withContext(Dispatchers.Default) { createStoryImage(song.title, artist, artwork) }
        val uri =
            withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "images").apply { mkdirs() }
                val file = File.createTempFile("song_story_", ".png", directory)
                try {
                    file.outputStream().use { check(storyImage.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } catch (error: Exception) {
                    file.delete()
                    throw error
                } finally {
                    storyImage.recycle()
                }
            }
        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, ShareUrls.song(song.id))
                clipData = ClipData.newUri(context.contentResolver, song.title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(shareIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun copyText(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Metrolist", text))
    }

    override suspend fun spotifyCanvas(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        primaryArtist: String?,
    ): String? = directCanvasProvider.getSpotifyCanvas(title, artist, durationMs, album, primaryArtist)

    override fun musicVideoCanvasHints(song: SongItem): ContentHints =
        song
            .toAndroidContentHints(wantVideo = true)
            .copy(maxVideoHeight = androidVideoMaxHeight(MusicVideoQuality.AUTO, automaticVideoMaxHeight()))
            .withStreamCapabilities(allowHls = false, allowSabr = true, allowBoundedRange = true)

    override suspend fun cachedAnimatedCover(
        songId: String,
        source: CanvasContentSource,
        resolution: AnimatedCoverResolution,
        tall: Boolean,
    ): String? = CanvasCache.get(context).find(animatedCanvasKey(songId, source, resolution, tall))?.url

    private fun animatedCanvasKey(
        songId: String,
        source: CanvasContentSource,
        resolution: AnimatedCoverResolution,
        tall: Boolean,
    ): String =
        CanvasCache.key(
            songId,
            source.name,
            if (source == CanvasContentSource.APPLE_MUSIC) animatedCoverCacheQuality(resolution, tall) else "",
        )

    override suspend fun cachedMusicVideoCanvas(
        songId: String,
        mode: YouTubeCanvasMode,
        quality: MusicVideoQuality,
    ): MusicVideoCanvas? {
        val key = CanvasCache.key(songId, CanvasContentSource.YOUTUBE_MUSIC_VIDEO.name, "${quality.name}:${mode.name}")
        val entry = CanvasCache.get(context).find(key) ?: return null
        return localMusicVideoCanvas(songId, entry.url, entry.mimeType, entry.durationMs, key)
    }

    override suspend fun prepareMusicVideoCanvas(
        songId: String,
        canvas: MusicVideoCanvas,
        mode: YouTubeCanvasMode,
        quality: MusicVideoQuality,
        preload: Boolean,
    ): MusicVideoCanvas {
        if (preload) cachedMusicVideoCanvas(songId, mode, quality)?.let { return it }
        val cacheKey = CanvasCache.key(songId, CanvasContentSource.YOUTUBE_MUSIC_VIDEO.name, "${quality.name}:${mode.name}")
        val streamingKey =
            if (mode == YouTubeCanvasMode.SHORT_CLIPS) {
                CanvasCache.key(songId, CanvasContentSource.YOUTUBE_MUSIC_VIDEO.name, "${quality.name}:${mode.name}:stream")
            } else {
                cacheKey
            }
        if (!preload || Settings.shared.animatedArtworkCacheSize == 0) {
            return canvas.copy(cacheKey = streamingKey)
        }
        val resource = canvas.resource
        val videoUri = if (resource.hasSabrVideo) "metrolist://sabr-video/${resource.videoId}" else checkNotNull(resource.videoUrl)
        return coroutineScope {
            val sources = mutableListOf<SeekableMediaSource>()
            val factory =
                checkNotNull(
                    buildVideoDataSourceFactory(
                        context,
                        resource,
                        GlobalYouTube.client,
                        onVideoSourceOpened = sources::add,
                        sabrSeekPrerollMs = checkNotNull(youtubeCanvasSeekPrerollMs(canvas.clips, true)),
                        deferStart = true,
                    ),
                )
            val closeOnCancellation =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        sources.forEach(SeekableMediaSource::close)
                    }
                }
            try {
                if (mode != YouTubeCanvasMode.SHORT_CLIPS) {
                    val entry =
                        CanvasCache.get(context).preload(
                            cacheKey,
                            factory,
                            resource.canvasCacheIdentity(),
                            uri = videoUri,
                            mimeType = resource.videoMimeType,
                            durationMs = resource.durationSeconds?.times(1000),
                        )
                    return@coroutineScope if (entry !=
                        null
                    ) {
                        canvas.copy(cacheKey = cacheKey, cachedUrl = entry.url)
                    } else {
                        canvas.copy(cacheKey = cacheKey)
                    }
                }
                val prepared =
                    prepareCanvasClips(
                        factory = factory,
                        uri = videoUri,
                        clips = canvas.clips,
                        directory = context.cacheDir,
                        onSeek = { position -> sources.forEach { it.updatePlaybackPosition(position) } },
                        cache = CanvasCache.get(context),
                        cacheKey = cacheKey,
                    )
                prepared.temporaryFile?.delete()
                val cached = prepared.cached
                if (cached != null) {
                    canvas.copy(clips = prepared.clips, cacheKey = cacheKey, cachedUrl = cached.url)
                } else {
                    canvas.copy(clips = prepared.clips, cacheKey = streamingKey)
                }
            } finally {
                closeOnCancellation.cancel()
            }
        }
    }

    override suspend fun preloadAnimatedCover(
        songId: String,
        source: CanvasContentSource,
        url: String,
        tall: Boolean,
    ): String {
        if (CanvasCache.isCachedUrl(url)) return url
        val key = animatedCanvasKey(songId, source, Settings.shared.animatedCoverResolution, tall)
        return try {
            CanvasCache.get(context).preload(key, DefaultHttpDataSource.Factory(), url)?.url ?: url
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            url
        }
    }

    private fun localMusicVideoCanvas(
        songId: String,
        url: String,
        mimeType: String?,
        durationMs: Long?,
        cacheKey: String,
    ) = MusicVideoCanvas(
        resource =
            PlaybackResource(
                videoId = songId,
                url = url,
                videoUrl = url,
                headers = emptyMap(),
                loudnessDb = null,
                expiresAt = null,
                videoMimeType = mimeType,
                durationSeconds = durationMs?.div(1000),
            ),
        clips = emptyList(),
        cacheKey = cacheKey,
    )
}

private fun createStoryImage(
    title: String,
    artist: String,
    artwork: Bitmap?,
): Bitmap {
    val width = 1080
    val height = 1920
    val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val background =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader =
                LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), 0xFF30233A.toInt(), 0xFF09090B.toInt(), Shader.TileMode.CLAMP)
        }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)

    val artworkRect = RectF(92f, 260f, 988f, 1156f)
    val artworkPath = Path().apply { addRoundRect(artworkRect, 34f, 34f, Path.Direction.CW) }
    canvas.withClip(artworkPath) {
        if (artwork != null) {
            val scale = maxOf(artworkRect.width() / artwork.width, artworkRect.height() / artwork.height)
            val cropWidth = artworkRect.width() / scale
            val cropHeight = artworkRect.height() / scale
            val source =
                Rect(
                    ((artwork.width - cropWidth) / 2).toInt(),
                    ((artwork.height - cropHeight) / 2).toInt(),
                    ((artwork.width + cropWidth) / 2).toInt(),
                    ((artwork.height + cropHeight) / 2).toInt(),
                )
            drawBitmap(artwork, source, artworkRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            drawRect(artworkRect, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4A4050.toInt() })
        }
    }

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val titlePaint =
        Paint(textPaint).apply {
            textSize = 58f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    val artistPaint =
        Paint(textPaint).apply {
            textSize = 38f
            alpha = 210
        }
    drawWrappedText(canvas, title, titlePaint, 92f, 1280f, 896f, 2)
    drawWrappedText(canvas, artist, artistPaint, 92f, 1435f, 896f, 2)
    return bitmap
}

private fun drawWrappedText(
    canvas: Canvas,
    text: String,
    paint: Paint,
    x: Float,
    baseline: Float,
    maxWidth: Float,
    maxLines: Int,
) {
    val words = text.split(Regex("\\s+")).filter(String::isNotBlank)
    var line = ""
    var lineIndex = 0
    for (word in words) {
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
            canvas.drawText(line, x, baseline + lineIndex * paint.textSize * 1.2f, paint)
            lineIndex++
            if (lineIndex >= maxLines) return
            line = word
        } else {
            line = candidate
        }
    }
    if (line.isNotEmpty() && lineIndex < maxLines) canvas.drawText(line, x, baseline + lineIndex * paint.textSize * 1.2f, paint)
}
