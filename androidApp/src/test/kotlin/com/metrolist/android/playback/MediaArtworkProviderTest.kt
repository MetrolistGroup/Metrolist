package com.metrolist.android.playback

import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.media3.common.Timeline
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.storage.ResizableImageDiskCache
import com.metrolist.shared.utils.bucketedArtworkUrl
import com.russhwolf.settings.MapSettings
import okio.Path.Companion.toOkioPath
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MediaArtworkProviderTest {
    @Test
    @OptIn(ExperimentalResourceApi::class)
    fun builtinCoversAreReadableWithoutNetworkOrAnImageCache() {
        val context = RuntimeEnvironment.getApplication()
        setResourceReaderAndroidContext(context)
        val provider = MediaArtworkProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "${context.packageName}.media-artwork" })
        BuiltinPlaylist.entries.filter { it != BuiltinPlaylist.HISTORY }.forEach { builtin ->
            val uri = checkNotNull(mediaArtworkUri(context, builtin.icon))
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.readBytes() }
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            assertEquals(bitmap.width, bitmap.height)
            bitmap.recycle()
        }
    }

    @Test
    @OptIn(DelicateCoilApi::class)
    fun currentArtworkLoadsWithoutAnActivityAfterQueuePublicationEvictsItsApproval() {
        val context = RuntimeEnvironment.getApplication()
        val fetchedUrls = mutableListOf<String>()
        val loader =
            ImageLoader
                .Builder(context)
                .diskCache(null)
                .components {
                    add(
                        object : Interceptor {
                            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                                fetchedUrls += chain.request.data as String
                                return SuccessResult(
                                    image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImage(),
                                    request = chain.request,
                                    dataSource = DataSource.NETWORK,
                                )
                            }
                        },
                    )
                }.build()
        SingletonImageLoader.setUnsafe(loader)
        Settings.replaceSharedForTest(Settings(MapSettings()))
        try {
            val provider = MediaArtworkProvider()
            provider.attachInfo(context, ProviderInfo().apply { authority = "${context.packageName}.media-artwork" })
            val cache = MediaSessionItemCache { mediaArtworkUri(context, it, isCurrent = true) }
            val songs =
                List(2_002) { index ->
                    SongItem(
                        id = "background-$index",
                        title = "Song $index",
                        artists = emptyList(),
                        album = null,
                        thumbnail = "https://lh3.googleusercontent.com/background-$index=s512",
                    )
                }
            var previousUri: Uri? = null
            for (song in songs.take(2)) {
                val metadata = cache.get(song)!!
                val uri = metadata.mediaMetadata.artworkUri!!
                val timeline =
                    mediaSessionTimeline(
                        songs.mapIndexed { index, entry -> MusicQueueEntry(index.toLong(), entry) },
                        songs.indexOf(song),
                        1_000,
                        artworkUri = { mediaArtworkUri(context, it) },
                    )
                val window = Timeline.Window()
                repeat(timeline.windowCount) { timeline.getWindow(it, window) }
                assertSame(metadata, cache.get(song))
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.readBytes() }
                assertEquals(8, BitmapFactory.decodeByteArray(bytes, 0, bytes.size).width)
                previousUri?.let { expired ->
                    assertThrows(FileNotFoundException::class.java) { provider.openFile(expired, "r") }
                }
                previousUri = uri
            }
            assertEquals(songs.take(2).map { it.thumbnail }, fetchedUrls)
            mediaArtworkUri(context, null, isCurrent = true)
            assertThrows(FileNotFoundException::class.java) { provider.openFile(previousUri!!, "r") }
            assertEquals(2, fetchedUrls.size)
        } finally {
            MediaArtworkProvider.currentArtwork = null
            Settings.replaceSharedForTest(null)
            SingletonImageLoader.reset()
            loader.shutdown()
        }
    }

    @Test
    @OptIn(DelicateCoilApi::class)
    fun fetchedArtworkRemainsReadableWhenDiskCachingIsDisabledOrAbsent() {
        val context = RuntimeEnvironment.getApplication()
        val directory = Files.createTempDirectory("media-artwork-disabled").toFile()
        val disabledCache = ResizableImageDiskCache(directory.toOkioPath(), 0)
        try {
            for (cache in listOf(disabledCache, null)) {
                val loader =
                    ImageLoader
                        .Builder(context)
                        .diskCache(cache)
                        .components {
                            add(
                                object : Interceptor {
                                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                                        SuccessResult(
                                            image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImage(),
                                            request = chain.request,
                                            dataSource = DataSource.NETWORK,
                                        )
                                },
                            )
                        }.build()
                SingletonImageLoader.setUnsafe(loader)
                try {
                    val uri = mediaArtworkUri(context, "https://lh3.googleusercontent.com/uncached=s512")!!
                    val provider = MediaArtworkProvider()
                    provider.attachInfo(context, ProviderInfo().apply { authority = "${context.packageName}.media-artwork" })
                    val bytes = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.readBytes() }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    assertEquals(8, bitmap.width)
                    assertEquals(8, bitmap.height)
                    assertEquals(null, cache?.openSnapshot(uri.getQueryParameter("key")!!))
                    assertEquals(0, context.cacheDir.listFiles()!!.count { it.name.startsWith("media-artwork-") })
                } finally {
                    SingletonImageLoader.reset()
                    loader.shutdown()
                }
            }
        } finally {
            disabledCache.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    @OptIn(DelicateCoilApi::class)
    fun cachedSizeVariantIsReadableThroughProviderAndSessionMetadata() {
        val context = RuntimeEnvironment.getApplication()
        val directory = Files.createTempDirectory("media-artwork").toFile()
        val cache =
            DiskCache
                .Builder()
                .directory(directory.toOkioPath())
                .maxSizeBytes(1024 * 1024)
                .build()
        var fetches = 0
        val snapshots = AtomicInteger()
        val checkedCache =
            object : DiskCache by cache {
                override fun openSnapshot(key: String): DiskCache.Snapshot? {
                    assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
                    snapshots.incrementAndGet()
                    return cache.openSnapshot(key)
                }
            }
        val loader =
            ImageLoader
                .Builder(context)
                .diskCache(checkedCache)
                .components {
                    add(
                        object : Interceptor {
                            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                                fetches++
                                cache.openEditor(chain.request.diskCacheKey!!)!!.apply {
                                    data.toFile().writeBytes(byteArrayOf(4, 5, 6))
                                    commit()
                                }
                                return ErrorResult(null, chain.request, FileNotFoundException())
                            }
                        },
                    )
                }.build()
        SingletonImageLoader.setUnsafe(loader)
        Settings.replaceSharedForTest(Settings(MapSettings()))
        try {
            val url = "https://lh3.googleusercontent.com/cover=s1200"
            val cachedUrl = bucketedArtworkUrl(url, 256)
            val song = SongItem(id = "song", title = "Song", artists = emptyList(), album = null, thumbnail = url)
            val resolver: (String?) -> Uri? = { mediaArtworkUri(context, it) }
            val itemCache = MediaSessionItemCache(resolver)
            val item = itemCache.get(song)!!
            val originalUri = item.mediaMetadata.artworkUri!!
            assertEquals("content", originalUri.scheme)
            assertEquals(0, snapshots.get())
            cache.openEditor(url)!!.apply {
                data.toFile().writeBytes(byteArrayOf())
                commit()
            }
            cache.openEditor(cachedUrl)!!.apply {
                data.toFile().writeBytes(byteArrayOf(1, 2, 3))
                commit()
            }
            val uri = mediaArtworkUri(context, url)!!
            assertEquals(originalUri, uri)
            assertEquals("content", uri.scheme)
            assertEquals(0, snapshots.get())
            val provider = MediaArtworkProvider()
            provider.attachInfo(context, ProviderInfo().apply { authority = "${context.packageName}.media-artwork" })
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.readBytes() }
            assertEquals(listOf<Byte>(1, 2, 3), bytes.toList())
            assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "w") }
            assertThrows(FileNotFoundException::class.java) { provider.openFile(Uri.parse("content://artwork/private/file"), "r") }
            assertThrows(FileNotFoundException::class.java) { openMediaArtwork(cache, "/etc/passwd") }
            assertSame(item, itemCache.get(song))
            assertEquals(uri, itemCache.get(song)!!.mediaMetadata.artworkUri)
            val timeline = mediaSessionTimeline(listOf(MusicQueueEntry(1, song)), 0, 1000, artworkUri = resolver)
            assertEquals(
                uri,
                timeline
                    .getWindow(0, Timeline.Window())
                    .mediaItem.mediaMetadata.artworkUri,
            )
            cache.remove(cachedUrl)
            val replacementUrl = bucketedArtworkUrl(url, 544)
            cache.openEditor(replacementUrl)!!.apply {
                data.toFile().writeBytes(byteArrayOf(7, 8, 9))
                commit()
            }
            assertEquals(uri, mediaArtworkUri(context, url))
            val replacement = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.readBytes() }
            assertEquals(listOf<Byte>(7, 8, 9), replacement.toList())
            assertEquals(0, fetches)
            cache.remove(replacementUrl)
            assertEquals(uri, mediaArtworkUri(context, url))
            val fetched = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.readBytes() }
            assertEquals(listOf<Byte>(4, 5, 6), fetched.toList())
            assertEquals(1, fetches)
            provider.openFile(uri, "r").close()
            assertEquals(1, fetches)
            val unapproved =
                Uri
                    .Builder()
                    .scheme("content")
                    .authority("${context.packageName}.media-artwork")
                    .appendPath("artwork")
                    .appendQueryParameter("key", "https://unapproved.example/image")
                    .build()
            cache.openEditor(unapproved.getQueryParameter("key")!!)!!.apply {
                data.toFile().writeBytes(byteArrayOf(7, 8, 9))
                commit()
            }
            assertThrows(FileNotFoundException::class.java) { provider.openFile(unapproved, "r") }
            assertEquals(1, fetches)
            for (cachedOnlyUrl in listOf("http://example.com/artwork", "file:///private/artwork")) {
                val cachedOnlyUri = mediaArtworkUri(context, cachedOnlyUrl)!!
                cache.openEditor(cachedOnlyUrl)!!.apply {
                    data.toFile().writeBytes(byteArrayOf(1, 2, 3))
                    commit()
                }
                val cachedOnly = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(cachedOnlyUri, "r")).use { it.readBytes() }
                assertEquals(listOf<Byte>(1, 2, 3), cachedOnly.toList())
                cache.remove(cachedOnlyUrl)
                assertThrows(FileNotFoundException::class.java) { provider.openFile(cachedOnlyUri, "r") }
                assertEquals(1, fetches)
            }
            assertEquals(null, mediaArtworkUri(context, null))
            for (source in listOf("content://artwork/image", "android.resource://${context.packageName}/drawable/artwork")) {
                assertEquals(Uri.parse(source), mediaArtworkUri(context, source))
            }
        } finally {
            Settings.replaceSharedForTest(null)
            SingletonImageLoader.reset()
            loader.shutdown()
            directory.deleteRecursively()
        }
    }
}
