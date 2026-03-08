package com.metrolist.android.playback

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
