package com.metrolist.android.playback

import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(DelicateCoilApi::class)
class DownloadedArtworkTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var database: MusicDatabase
    private lateinit var loader: ImageLoader
    private lateinit var manager: AudioCacheManager
    private val artwork = DownloadedArtwork(context)
    private val url = "https://lh3.googleusercontent.com/download-cover=s1200"
    private val audio = ByteArray(4096) { (it % 251).toByte() }
    private var imageRequests = 0
    private var audioRequests = 0
    private var failArtwork = false
    private var cancelArtwork = false

    @Before
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Settings.shared.maxImageCacheSize = 0
        database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
        loader =
            ImageLoader
                .Builder(context)
                .diskCache(null)
                .components {
                    add(
                        object : Interceptor {
                            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                                imageRequests++
                                if (cancelArtwork) throw CancellationException("Cancelled artwork")
                                if (failArtwork) return ErrorResult(null, chain.request, IOException("Offline"))
                                return SuccessResult(
                                    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImage(),
                                    chain.request,
                                    DataSource.NETWORK,
                                )
                            }
                        },
                    )
                }.build()
        SingletonImageLoader.setUnsafe(loader)
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    audioRequests++
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .message("fixture")
                        .code(206)
                        .header("Content-Range", "bytes 0-${audio.lastIndex}/${audio.size}")
                        .body(audio.toResponseBody())
                        .build()
                }.build()
        manager = AudioCacheManager(context, database, client)
    }

    @After
    fun tearDown() =
        runBlocking {
            manager.clearDownloads()
            artwork.prune(emptyList())
            SingletonImageLoader.reset()
            loader.shutdown()
            database.close()
            Settings.replaceSharedForTest(null)
        }

    @Test
    fun newDownloadRetainsCoverWithImageCachingDisabledAndProviderReadsItOffline() =
        runBlocking {
            val song = song("new")
            manager.downloadSong(song, resource(song))
            assertEquals(1, audioRequests)
            assertEquals(1, imageRequests)
            assertArrayEquals(audio, File(database.dao().cachedAudio(song.id)!!.filePath).readBytes())
            assertTrue(artwork.file(url).length() > 0)
            loader.diskCache?.clear()
            loader.memoryCache?.clear()
            failArtwork = true
            val provider = MediaArtworkProvider()
            provider.attachInfo(context, ProviderInfo().apply { authority = "${context.packageName}.media-artwork" })
            val uri = mediaArtworkUri(context, url.replace("s1200", "s256"))!!
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.readBytes() }
            assertEquals(8, BitmapFactory.decodeByteArray(bytes, 0, bytes.size).width)
            assertEquals(1, imageRequests)
        }

    @Test
    fun legacyVideoCoverRemainsReachableOfflineAndSurvivesPruning() {
        val videoUrl = "https://i.ytimg.com/vi/downloaded/mqdefault.jpg"
        val legacy = File(context.filesDir, "downloaded-artwork/${videoUrl.encodeUtf8().sha256().hex()}.png")
        legacy.parentFile!!.mkdirs()
        legacy.outputStream().use { output ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        loader.memoryCache?.clear()
        failArtwork = true
        artwork.prune(listOf(videoUrl))
        assertEquals(legacy, artwork.file(videoUrl))
        assertTrue(legacy.exists())
        val provider = MediaArtworkProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "${context.packageName}.media-artwork" })
        val bytes =
            ParcelFileDescriptor
                .AutoCloseInputStream(
                    provider.openFile(mediaArtworkUri(context, videoUrl)!!, "r"),
                ).use { it.readBytes() }
        assertEquals(8, BitmapFactory.decodeByteArray(bytes, 0, bytes.size).width)
        assertEquals(0, imageRequests)
    }

    @Test
    fun coversRetainedUnderThePromotedVideoKeyAlsoSurvivePruning() {
        val videoUrl = "https://i.ytimg.com/vi/downloaded/mqdefault.jpg"
        val promotedUrl = videoUrl.replace("mqdefault", "maxresdefault")
        val promoted = File(context.filesDir, "downloaded-artwork/${promotedUrl.encodeUtf8().sha256().hex()}.png")
        promoted.parentFile!!.mkdirs()
        promoted.writeBytes(byteArrayOf(1))
        val legacy = File(context.filesDir, "downloaded-artwork/${videoUrl.encodeUtf8().sha256().hex()}.png")
        legacy.writeBytes(byteArrayOf(2))
        artwork.prune(listOf(videoUrl))
        assertEquals(promoted, artwork.file(videoUrl))
        assertTrue(promoted.exists())
        assertFalse(legacy.exists())
        assertEquals(0, imageRequests)
    }

    @Test
    fun existingDownloadRepairsMissingCoverWithoutTransferringAudioAgain() =
        runBlocking {
            val song = seedDownload("existing")
            val entry = database.dao().cachedAudio(song.id)!!
            database.dao().upsert(entry.copy(thumbnailUrl = null))
            artwork.file(url).apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf())
            }
            manager.downloadSong(song, resource(song))
            assertEquals(0, audioRequests)
            assertEquals(url, database.dao().cachedAudio(song.id)!!.thumbnailUrl)
            assertEquals(1, imageRequests)
            assertTrue(artwork.file(url).length() > 0)
            manager.downloadSong(song, resource(song))
            assertEquals(1, imageRequests)
        }

    @Test
    fun backfillRetriesFailuresAndPrunesOnlyUnreferencedCovers() =
        runBlocking {
            seedDownload("first")
            seedDownload("second")
            failArtwork = true
            manager.repairDownloadedArtwork()
            assertFalse(artwork.file(url).exists())
            assertEquals(2, imageRequests)
            failArtwork = false
            manager.repairDownloadedArtwork()
            assertTrue(artwork.file(url).length() > 0)
            assertEquals(3, imageRequests)
            manager.repairDownloadedArtwork()
            assertEquals(3, imageRequests)
            assertTrue(manager.removeDownload("first"))
            assertTrue(artwork.file(url).exists())
            assertTrue(manager.removeDownload("second"))
            assertFalse(artwork.file(url).exists())
            manager.repairDownloadedArtwork()
            assertEquals(3, imageRequests)
            assertEquals(0, audioRequests)
        }

    @Test
    fun failedArtworkDoesNotFailAudioDownloadAndRemainsRepairable() =
        runBlocking {
            val song = song("retry")
            failArtwork = true
            manager.downloadSong(song, resource(song))
            assertTrue(manager.isDownloaded(song.id))
            assertFalse(artwork.file(url).exists())
            failArtwork = false
            manager.repairDownloadedArtwork()
            assertTrue(artwork.file(url).length() > 0)
            assertEquals(1, audioRequests)
            manager.clearDownloads()
            assertFalse(artwork.file(url).exists())
        }

    @Test
    fun cancellationDoesNotPublishArtworkAndCanBeRetried() =
        runBlocking {
            cancelArtwork = true
            try {
                artwork.retain(url)
                throw AssertionError("Artwork cancellation must propagate")
            } catch (_: CancellationException) {
                assertFalse(artwork.file(url).exists())
            }
            cancelArtwork = false
            artwork.retain(url)
            assertTrue(artwork.file(url).length() > 0)
        }

    private fun song(id: String) = SongItem(id, "Track", url, emptyList(), null)

    private fun resource(song: SongItem) =
        PlaybackResource(
            song.id,
            "https://media.example.test/audio",
            emptyMap(),
            null,
            null,
            contentLengthBytes = audio.size.toLong(),
            mimeType = "audio/mp4",
        )

    private suspend fun seedDownload(id: String): SongItem {
        val song = song(id)
        val source = File(context.filesDir, "$id.m4a").apply { writeBytes(audio) }
        database.dao().upsert(
            CachedAudioEntity(
                song.id,
                song.title,
                "Artist",
                thumbnailUrl = song.thumbnail,
                filePath = source.path,
                fileSize = audio.size.toLong(),
                cachedAt = 1,
                lastAccessedAt = 1,
                cacheLocation = "DOWNLOAD_INTERNAL",
                kind = KIND_DOWNLOAD,
            ),
        )
        return song
    }
}
