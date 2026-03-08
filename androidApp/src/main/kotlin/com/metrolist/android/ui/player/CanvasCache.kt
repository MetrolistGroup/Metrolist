package com.metrolist.android.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.metrolist.shared.api.artwork.CanvasContentSource
import com.metrolist.shared.api.artwork.isHlsArtworkUrl
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

class CanvasCache(
    directory: File,
    databaseProvider: DatabaseProvider,
    limitMb: Int,
) {
    private val evictor = ResizableEvictor(limitMb.toLong() * 1024 * 1024)
    private val cache = SimpleCache(directory, evictor, databaseProvider)
    private var enabled = limitMb > 0

    data class Entry(
        val url: String,
        val mimeType: String?,
        val durationMs: Long?,
    )

    @Synchronized
    fun setLimit(limitMb: Int) {
        enabled = limitMb > 0
        evictor.resize(cache, limitMb.toLong() * 1024 * 1024)
    }

    @Synchronized
    fun find(key: String): Entry? {
        if (!enabled) return null
        val contentKey =
            cache.keys
                .filter { it == key || it.startsWith("$key/") }
                .filter {
                    val length = ContentMetadata.getContentLength(cache.getContentMetadata(it))
                    length > 0 && cache.isCached(it, 0, length)
                }.maxByOrNull { cache.getCachedSpans(it).maxOfOrNull { span -> span.lastTouchTimestamp } ?: 0L }
                ?: return null
        val metadata = cache.getContentMetadata(contentKey)
        return Entry(
            url = contentKey,
            mimeType = metadata.get(MIME, "")?.ifEmpty { null },
            durationMs = metadata.get(DURATION, -1L).takeIf { it > 0 },
        )
    }

    @Synchronized
    fun factory(
        key: String,
        upstream: DataSource.Factory?,
        identity: String = key,
        mimeType: String? = null,
        durationMs: Long? = null,
    ): DataSource.Factory {
        if (!enabled && upstream != null) return upstream
        val hls = isHlsArtworkUrl(identity)
        val contentKey = if (upstream != null) "$key/${hash(identity)}" else key
        if (upstream != null && !hls) {
            cache.applyContentMetadataMutations(
                contentKey,
                ContentMetadataMutations()
                    .set(MIME, mimeType.orEmpty())
                    .set(DURATION, durationMs ?: -1L),
            )
        }
        return CacheDataSource
            .Factory()
            .setCache(cache)
            .setCacheKeyFactory { if (hls) it.key ?: it.uri.toString() else contentKey }
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    internal suspend fun preload(
        key: String,
        upstream: DataSource.Factory,
        identity: String,
        uri: String = identity,
        mimeType: String? = null,
        durationMs: Long? = null,
    ): Entry? {
        if (isHlsArtworkUrl(identity)) return null
        find(key)?.let { return it }
        val limit = synchronized(this) { if (enabled) evictor.limit else 0L }
        if (limit == 0L) return null
        val source = factory(key, upstream, identity, mimeType, durationMs).createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(uri).build())
            val buffer = ByteArray(8192)
            var read = 0L
            val cancellation = currentCoroutineContext()
            while (read < limit) {
                cancellation.ensureActive()
                val size = source.read(buffer, 0, minOf(buffer.size.toLong(), limit - read).toInt())
                if (size == -1) break
                read += size
            }
        } finally {
            source.close()
        }
        return find(key)
    }

    internal suspend fun storeClips(
        key: String,
        file: File,
        mimeType: String,
        durationMs: Long,
    ): Entry? {
        if (!synchronized(this) { enabled && file.length() <= evictor.limit }) return null
        val source =
            factory(
                key,
                DataSource.Factory { FileDataSource() },
                identity = file.name,
                mimeType = mimeType,
                durationMs = durationMs,
            ).createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).build())
            val buffer = ByteArray(8192)
            val cancellation = currentCoroutineContext()
            while (true) {
                cancellation.ensureActive()
                if (source.read(buffer, 0, buffer.size) == -1) break
            }
        } finally {
            source.close()
        }
        return find(key)
    }

    @Synchronized
    fun streamingFactory(upstream: DataSource.Factory): DataSource.Factory =
        if (!enabled) {
            upstream
        } else {
            CacheDataSource
                .Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

    internal fun migrateAnimatedCovers(
        directory: File,
        databaseProvider: DatabaseProvider,
    ) {
        if (!directory.exists()) return
        val legacy = SimpleCache(directory, NoOpCacheEvictor(), databaseProvider)
        try {
            for (key in legacy.keys) {
                if (key in cache.keys) continue
                try {
                    val length = ContentMetadata.getContentLength(legacy.getContentMetadata(key))
                    if (length >= 0) {
                        cache.applyContentMetadataMutations(
                            key,
                            ContentMetadataMutations().also {
                                ContentMetadataMutations.setContentLength(it, length)
                            },
                        )
                    }
                    for (span in legacy.getCachedSpans(key)) {
                        val hole = cache.startReadWriteNonBlocking(key, span.position, span.length) ?: continue
                        if (hole.isCached) continue
                        try {
                            val destination = cache.startFile(key, span.position, span.length)
                            try {
                                requireNotNull(span.file).copyTo(destination, overwrite = true)
                                cache.commitFile(destination, span.length)
                            } catch (e: Exception) {
                                destination.delete()
                                throw e
                            }
                        } finally {
                            cache.releaseHoleSpan(hole)
                        }
                    }
                } catch (e: Exception) {
                    cache.removeResource(key)
                    throw e
                }
            }
        } finally {
            legacy.release()
        }
        SimpleCache.delete(directory, databaseProvider)
    }

    fun sizeBytes(): Long = cache.cacheSpace

    fun clear() {
        cache.keys.toList().forEach(cache::removeResource)
    }

    fun release() = cache.release()

    private class ResizableEvictor(
        var limit: Long,
    ) : CacheEvictor {
        private var delegate = LeastRecentlyUsedCacheEvictor(limit)

        fun resize(
            cache: Cache,
            bytes: Long,
        ) = synchronized(cache) {
            if (limit == bytes) return@synchronized
            limit = bytes
            delegate = LeastRecentlyUsedCacheEvictor(bytes)
            cache.keys
                .flatMap { cache.getCachedSpans(it) }
                .sortedBy { it.lastTouchTimestamp }
                .forEach { if (it in cache.getCachedSpans(it.key)) delegate.onSpanAdded(cache, it) }
        }

        override fun requiresCacheSpanTouches() = true

        override fun onCacheInitialized() = delegate.onCacheInitialized()

        override fun onStartFile(
            cache: Cache,
            key: String,
            position: Long,
            length: Long,
        ) = delegate.onStartFile(cache, key, position, length)

        override fun onSpanAdded(
            cache: Cache,
            span: CacheSpan,
        ) = delegate.onSpanAdded(cache, span)

        override fun onSpanRemoved(
            cache: Cache,
            span: CacheSpan,
        ) = delegate.onSpanRemoved(cache, span)

        override fun onSpanTouched(
            cache: Cache,
            oldSpan: CacheSpan,
            newSpan: CacheSpan,
        ) = delegate.onSpanTouched(cache, oldSpan, newSpan)
    }

    companion object {
        private const val MIME = "canvas.mime"
        private const val DURATION = "canvas.duration"
        private var instance: CanvasCache? = null

        @Synchronized
        fun get(context: Context): CanvasCache =
            (
                instance ?: run {
                    val database = StandaloneDatabaseProvider(context.applicationContext)
                    val created = CanvasCache(File(context.filesDir, "canvas_cache"), database, Settings.shared.animatedArtworkCacheSize)
                    try {
                        created.migrateAnimatedCovers(File(context.filesDir, "animated_cover_video_cache"), database)
                        created.also { instance = it }
                    } catch (e: Exception) {
                        created.release()
                        database.close()
                        throw e
                    }
                }
            ).also { it.setLimit(Settings.shared.animatedArtworkCacheSize) }

        fun key(
            songId: String,
            source: String,
            quality: String = "",
        ): String {
            val revision = if (source == CanvasContentSource.APPLE_MUSIC.name) "hls-safe" else ""
            return "canvas-cache:///" + hash("$songId\n$source\n$quality$revision")
        }

        private fun hash(value: String): String = hash(value.toByteArray())

        private fun hash(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).toHexString()

        fun isCachedUrl(url: String) = url.startsWith("canvas-cache:///")
    }
}

internal fun PlaybackResource.canvasCacheIdentity(): String =
    listOf(
        videoId,
        videoItag,
        videoContentLengthBytes,
        videoMimeType,
        videoCodecs,
        sabrVideoBootstrap?.selectedVideoFormat?.lastModified,
    ).joinToString("|")
