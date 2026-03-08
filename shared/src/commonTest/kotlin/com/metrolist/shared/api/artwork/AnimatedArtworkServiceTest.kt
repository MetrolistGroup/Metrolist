package com.metrolist.shared.api.artwork

import com.metrolist.shared.settings.AnimatedCoverResolution
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimatedArtworkServiceTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun bothFetchesWaitForBackgroundCacheLoad() =
        runBlocking {
            withFreshSettings {
                val client = HttpClient(MockEngine { error("Cached artwork should not require a request") })
                val dispatcher = StandardTestDispatcher()
                val service = AnimatedArtworkService(client, dispatcher)
                seedArtworkCaches()

                try {
                    val cover = async(start = CoroutineStart.UNDISPATCHED) { service.fetchAnimatedCover("Sharks", "Imagine Dragons") }
                    val artist = async(start = CoroutineStart.UNDISPATCHED) { service.fetchArtistArtwork("Cached Artist") }
                    assertFalse(cover.isCompleted)
                    assertFalse(artist.isCompleted)

                    dispatcher.scheduler.runCurrent()

                    assertEquals(SINGLE_MP4, cover.await()?.url)
                    val artwork = artist.await() as ArtistArtworkFetchResult.Found
                    assertEquals(CACHED_ARTIST_IMAGE_URL, artwork.artwork.imageUrl)
                } finally {
                    service.dispose()
                    service.clearCache()
                    client.close()
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun clearWaitsForBackgroundCacheLoadAndClearsBothCaches() =
        runBlocking {
            withFreshSettings {
                val client = buildClient(songSearch(singleTrack), mapOf(SINGLE_ID to ALBUM_MP4))
                val dispatcher = StandardTestDispatcher()
                val service = AnimatedArtworkService(client, dispatcher)
                seedArtworkCaches()

                try {
                    val clear = async(start = CoroutineStart.UNDISPATCHED) { service.clearCache() }
                    assertFalse(clear.isCompleted)

                    dispatcher.scheduler.runCurrent()
                    clear.await()

                    assertEquals("{}", Settings.shared.animatedCoverCacheJson)
                    assertEquals("{}", Settings.shared.appleMusicArtistCacheJson)
                    assertEquals(ALBUM_MP4, service.fetchAnimatedCover("Sharks", "Imagine Dragons")?.url)
                    assertEquals(ArtistArtworkFetchResult.Missing, service.fetchArtistArtwork("Cached Artist"))
                } finally {
                    service.dispose()
                    service.clearCache()
                    client.close()
                }
            }
        }

    @Test
    fun propagatesRequestCancellation(): Unit =
        runBlocking {
            withFreshSettings {
                val client = HttpClient(MockEngine { throw CancellationException("cancelled") })
                val service = AnimatedArtworkService(client)

                try {
                    assertFailsWith<CancellationException> {
                        service.fetchAnimatedCover("Title", "Artist")
                    }
                    Settings.shared.enableAppleMusicArtistImages = true
                    Settings.shared.lastResolvedContentCountry = "GB"
                    assertFailsWith<CancellationException> {
                        service.fetchArtistArtwork("Artist")
                    }
                } finally {
                    service.dispose()
                    service.clearCache()
                    client.close()
                }
            }
        }

    @Test
    fun songArtworkUsesTheConfiguredAppleVideoResolution() =
        runBlocking {
            withFreshSettings {
                val masterUrl = "https://video-ssl.itunes.apple.com/itunes-assets/sharks/master.m3u8"
                val unsupportedMasterUrl = "https://video-ssl.itunes.apple.com/itunes-assets/unsupported/master.m3u8"
                val client =
                    buildClient(
                        songSearch(
                            singleTrack,
                            """{"trackName":"Other","artistName":"Imagine Dragons","collectionId":223,"collectionName":"Other - Single","kind":"song","trackId":4}""",
                        ),
                        mapOf(SINGLE_ID to masterUrl, 223L to unsupportedMasterUrl),
                        playlistResponses =
                            mapOf(
                                masterUrl to
                                    """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
360.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
720.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
1080.m3u8
""",
                                unsupportedMasterUrl to
                                    """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
https://untrusted.example/video.m3u8
""",
                            ),
                    )
                val service = AnimatedArtworkService(client)

                try {
                    Settings.shared.animatedCoverResolution = AnimatedCoverResolution.LOW_360
                    assertEquals(
                        "https://video-ssl.itunes.apple.com/itunes-assets/sharks/360.m3u8",
                        service.fetchAnimatedCover("Sharks", "Imagine Dragons")?.url,
                    )

                    Settings.shared.animatedCoverResolution = AnimatedCoverResolution.HIGH_1080
                    assertEquals(
                        "https://video-ssl.itunes.apple.com/itunes-assets/sharks/1080.m3u8",
                        service.fetchAnimatedCover("Sharks", "Imagine Dragons")?.url,
                    )

                    Settings.shared.animatedCoverResolution = AnimatedCoverResolution.ORIGINAL
                    assertEquals(
                        "https://video-ssl.itunes.apple.com/itunes-assets/sharks/1080.m3u8",
                        service.fetchAnimatedCover("Sharks", "Imagine Dragons")?.url,
                    )
                    assertNull(service.fetchAnimatedCover("Other", "Imagine Dragons"))
                } finally {
                    service.dispose()
                    service.clearCache()
                    client.close()
                }
            }
        }

    @Test
    fun returnsAndCachesTheTallClipBesideTheSquareClip(): Unit =
        runBlocking {
            withFreshSettings {
                val ampRequests = mutableListOf<String>()
                val client =
                    buildClient(
                        songSearch(albumTrack),
                        mapOf(ALBUM_ID to ALBUM_MP4),
                        ampRequests,
                        tallUrlByCollectionId = mapOf(ALBUM_ID to ALBUM_TALL_MP4),
                    )
                val service = AnimatedArtworkService(client)

                try {
                    val fetched = service.fetchAnimatedCover("Sharks", "Imagine Dragons", "Mercury - Acts 1 & 2")
                    assertEquals(ALBUM_MP4, fetched?.url)
                    assertEquals(ALBUM_TALL_MP4, fetched?.tallUrl)

                    val cached = service.fetchAnimatedCover("Sharks", "Imagine Dragons", "Mercury - Acts 1 & 2")
                    assertEquals(ALBUM_TALL_MP4, cached?.tallUrl)
                    assertEquals(1, ampRequests.size)
                } finally {
                    service.dispose()
                    service.clearCache()
                    client.close()
                }
            }
        }

    @Test
    fun squareClipStaysWhenAppleHasNoTallClip(): Unit =
        runBlocking {
            withFreshSettings {
                val client = buildClient(songSearch(albumTrack), mapOf(ALBUM_ID to ALBUM_MP4))
                val service = AnimatedArtworkService(client)

                try {
                    val fetched = service.fetchAnimatedCover("Sharks", "Imagine Dragons", "Mercury - Acts 1 & 2")
                    assertEquals(ALBUM_MP4, fetched?.url)
                    assertNull(fetched?.tallUrl)
                } finally {
                    service.dispose()
                    service.clearCache()
                    client.close()
                }
            }
        }

    @Test
    fun untrustedCachedUrlIsDroppedAndNotServed(): Unit =
        runBlocking {
            withFreshSettings {
                val now = Long.MAX_VALUE
                Settings.shared.animatedCoverCacheJson =
                    """{"v9|imagine dragons|T|sharks|RMEDIUM_720|CUS":{"url":"http://evil.example.com/x.m3u8","cachedAt":$now}}"""
                val ampRequests = mutableListOf<String>()
                val client =
                    buildClient(
                        songSearch(albumTrack, singleTrack),
                        mapOf(ALBUM_ID to ALBUM_MP4, SINGLE_ID to SINGLE_MP4),
                        ampRequests,
                    )
                val service = AnimatedArtworkService(client)

                try {
                    val result = service.fetchAnimatedCover("Sharks", "Imagine Dragons", null)
                    assertEquals(SINGLE_MP4, result?.url)
                    assertTrue(ampRequests.any { it.endsWith("/albums/$SINGLE_ID") })
                } finally {
                    service.dispose()
                    service.clearCache()
                    client.close()
                }
            }
        }

    @Test
    fun untrustedUserInfoUrlIsDroppedAndNotServed(): Unit =
        runBlocking {
            withFreshSettings {
                val now = Long.MAX_VALUE
                Settings.shared.animatedCoverCacheJson =
                    """{"v9|imagine dragons|T|sharks|RMEDIUM_720|CUS":{"url":"https://itunes.apple.com:99999@evil.example.com/x.m3u8","cachedAt":$now}}"""
                val ampRequests = mutableListOf<String>()
                val client =
                    buildClient(
                        songSearch(albumTrack, singleTrack),
                        mapOf(ALBUM_ID to ALBUM_MP4, SINGLE_ID to SINGLE_MP4),
                        ampRequests,
                    )
                val service = AnimatedArtworkService(client)

                try {
                    val result = service.fetchAnimatedCover("Sharks", "Imagine Dragons", null)
                    assertEquals(SINGLE_MP4, result?.url)
                    assertTrue(ampRequests.any { it.endsWith("/albums/$SINGLE_ID") })
                } finally {
                    service.dispose()
                    service.clearCache()
                    client.close()
                }
            }
        }

    @Test
    fun untrustedMasterUrlIsNotFetchedOrResolved(): Unit =
        runBlocking {
            withFreshSettings {
                val requestedHosts = mutableListOf<String>()
                val ampRequests = mutableListOf<String>()
                val client =
                    buildClient(
                        songSearch(albumTrack, singleTrack),
                        mapOf(
                            ALBUM_ID to "https://evil.example.com/evil.m3u8",
                            SINGLE_ID to SINGLE_MP4,
                        ),
                        ampRequests,
                        requestedHosts,
                    )
                val service = AnimatedArtworkService(client)

                try {
                    val result =
                        service.fetchAnimatedCover("Sharks", "Imagine Dragons", "Mercury - Acts 1 & 2")
                    assertNull(result)
                    assertTrue(requestedHosts.none { it == "evil.example.com" })
                } finally {
                    service.dispose()
                    service.clearCache()
                    client.close()
                }
            }
        }

    @Test
    fun portAndSuffixVariantsAreEnforcedOnCacheLoad(): Unit =
        runBlocking {
            withFreshSettings {
                val now = Long.MAX_VALUE
                Settings.shared.animatedCoverCacheJson =
                    """{"v9|imagine dragons|T|sharks|RMEDIUM_720|CUS":{"url":"https://video-ssl.itunes.apple.com:99999/x.m3u8","cachedAt":$now},"v9|imagine dragons|T|believer|RMEDIUM_720|CUS":{"url":"https://evil-apple.com/x.m3u8","cachedAt":$now},"v9|imagine dragons|T|radioactive|RMEDIUM_720|CUS":{"url":"https://video-ssl.itunes.apple.com:443/x.mp4","cachedAt":$now}}"""
                val ampRequests = mutableListOf<String>()
                val client =
                    buildClient(
                        songSearch(albumTrack, singleTrack),
                        mapOf(ALBUM_ID to ALBUM_MP4, SINGLE_ID to SINGLE_MP4),
                        ampRequests,
                    )
                val service = AnimatedArtworkService(client)

                try {
                    val sharks = service.fetchAnimatedCover("Sharks", "Imagine Dragons", null)
                    assertEquals(SINGLE_MP4, sharks?.url)
                    assertTrue(ampRequests.any { it.endsWith("/albums/$SINGLE_ID") })

                    val believer = service.fetchAnimatedCover("Believer", "Imagine Dragons", null)
                    assertNull(believer)

                    val radioactive = service.fetchAnimatedCover("Radioactive", "Imagine Dragons", null)
                    assertEquals("https://video-ssl.itunes.apple.com:443/x.mp4", radioactive?.url)
                    assertEquals(
                        listOf("https://amp-api.music.apple.com/v1/catalog/us/albums/$SINGLE_ID"),
                        ampRequests,
                    )
                } finally {
                    service.dispose()
                    service.clearCache()
                    client.close()
                }
            }
        }

    @Test
    fun rejectsExpiredAndNonAppleMusicDevTokens(): Unit =
        runBlocking {
            withFreshSettings {
                Settings.shared.enableAppleMusicArtistImages = true
                Settings.shared.lastResolvedContentCountry = "GB"
                Settings.shared.lastResolvedContentLanguage = "en"
                var artistAssetRequests = 0
                val client =
                    HttpClient(
                        MockEngine { request ->
                            when {
                                request.url.host == "itunes.apple.com" -> {
                                    respond(
                                        """{"resultCount":1,"results":[{"artistName":"Billie Eilish","artistId":1065981054}]}""",
                                        HttpStatusCode.OK,
                                    )
                                }

                                request.url.encodedPath == "/gb/browse" -> {
                                    respond(BROWSE_HTML, HttpStatusCode.OK)
                                }

                                request.url.encodedPath == "/assets/index.js" -> {
                                    respond(INVALID_JS_BUNDLE, HttpStatusCode.OK)
                                }

                                request.url.encodedPath == "/v1/catalog/gb/artists/1065981054" -> {
                                    artistAssetRequests++
                                    respond("{}", HttpStatusCode.OK)
                                }

                                else -> {
                                    respond("", HttpStatusCode.NotFound)
                                }
                            }
                        },
                    )
                val service = AnimatedArtworkService(client)
                try {
                    assertEquals(ArtistArtworkFetchResult.Unavailable, service.fetchArtistArtwork("Billie Eilish"))
                    assertEquals(0, artistAssetRequests)
                } finally {
                    service.dispose()
                    service.clearCache()
                    client.close()
                }
            }
        }

    private fun seedArtworkCaches() {
        val now = Long.MAX_VALUE
        Settings.shared.enableAppleMusicArtistImages = true
        Settings.shared.animatedCoverCacheJson =
            """{"v9|imagine dragons|T|sharks|RMEDIUM_720|CUS":{"url":"$SINGLE_MP4","cachedAt":$now}}"""
        Settings.shared.appleMusicArtistCacheJson =
            """{"us||cached artist|true|false|MEDIUM_720":{"imageUrl":"$CACHED_ARTIST_IMAGE_URL","cachedAt":$now,"isMissing":false,"shortLived":false}}"""
    }

    private suspend fun <T> withFreshSettings(block: suspend () -> T): T {
        Settings.replaceSharedForTest(
            Settings(MapSettings()).apply {
                enableAnimatedCovers = true
                lastResolvedContentCountry = "US"
            },
        )
        return try {
            block()
        } finally {
            Settings.replaceSharedForTest(null)
        }
    }

    private fun buildClient(
        songSearchJson: String,
        ampUrlByCollectionId: Map<Long, String>,
        requestedAmpUrls: MutableList<String> = mutableListOf(),
        requestedHosts: MutableList<String>? = null,
        playlistResponses: Map<String, String> = emptyMap(),
        tallUrlByCollectionId: Map<Long, String> = emptyMap(),
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                requestedHosts?.add(request.url.host)
                val host = request.url.host
                val path = request.url.encodedPath
                when {
                    request.url.toString() in playlistResponses -> {
                        respond(playlistResponses.getValue(request.url.toString()), HttpStatusCode.OK)
                    }

                    host == "itunes.apple.com" && path == "/search" && request.url.parameters["entity"] == "song" -> {
                        respond(songSearchJson, HttpStatusCode.OK)
                    }

                    host == "itunes.apple.com" && path == "/search" && request.url.parameters["entity"] == "musicArtist" -> {
                        respond(EMPTY_ITUNES_RESULTS, HttpStatusCode.OK)
                    }

                    host == "itunes.apple.com" && path == "/lookup" -> {
                        respond(EMPTY_ITUNES_RESULTS, HttpStatusCode.OK)
                    }

                    host == "music.apple.com" && path == "/us/browse" -> {
                        respond(BROWSE_HTML, HttpStatusCode.OK)
                    }

                    host == "music.apple.com" && path == "/assets/index.js" -> {
                        respond(JS_BUNDLE, HttpStatusCode.OK)
                    }

                    host == "amp-api.music.apple.com" && path.startsWith("/v1/catalog/us/albums/") -> {
                        val collectionId = path.removePrefix("/v1/catalog/us/albums/").toLong()
                        requestedAmpUrls += "https://amp-api.music.apple.com/v1/catalog/us/albums/$collectionId"
                        val videoUrl = ampUrlByCollectionId[collectionId]
                        if (videoUrl == null) {
                            respond("""{"resources":{"albums":{}}}""", HttpStatusCode.OK)
                        } else {
                            val tall =
                                tallUrlByCollectionId[collectionId]?.let { ""","motionTallVideo3x4":{"video":"$it"}""" }.orEmpty()
                            respond(
                                """{"resources":{"albums":{"$collectionId":{"attributes":{"editorialVideo":{"motionSquareVideo1x1":{"video":"$videoUrl"}$tall}}}}}}""",
                                HttpStatusCode.OK,
                            )
                        }
                    }

                    else -> {
                        respond("", HttpStatusCode.NotFound)
                    }
                }
            },
        )

    private fun songSearch(vararg results: String): String = """{"resultCount":${results.size},"results":[${results.joinToString(",")}]}"""

    companion object {
        private const val ALBUM_ID = 111L
        private const val SINGLE_ID = 222L

        private const val CACHED_ARTIST_IMAGE_URL = "https://is1-ssl.mzstatic.com/image/thumb/cached.webp"
        private const val ALBUM_MP4 = "https://video-ssl.itunes.apple.com/itunes-assets/mercury.mp4"
        private const val ALBUM_TALL_MP4 = "https://video-ssl.itunes.apple.com/itunes-assets/mercury-tall.mp4"
        private const val SINGLE_MP4 = "https://video-ssl.itunes.apple.com/itunes-assets/sharks-single.mp4"

        private const val EMPTY_ITUNES_RESULTS = """{"resultCount":0,"results":[]}"""
        private const val BROWSE_HTML =
            """<html><head><script src="/assets/index.js"></script></head><body></body></html>"""
        private const val EXPIRED_AMP_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJBTVBXZWJQbGF5IiwiZXhwIjoxfQ.XYZ" // gitleaks:allow
        private const val WRONG_ISSUER_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJPdGhlciIsImV4cCI6OTk5OTk5OTk5OX0.XYZ" // gitleaks:allow
        private const val VALID_AMP_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJBTVBXZWJQbGF5IiwiZXhwIjo5OTk5OTk5OTk5fQ.XYZ" // gitleaks:allow
        private const val INVALID_JS_BUNDLE = "window.token=\"$EXPIRED_AMP_TOKEN\"; window.token=\"$WRONG_ISSUER_TOKEN\";"
        private val JS_BUNDLE =
            "window.token=\"$EXPIRED_AMP_TOKEN\"; window.token=\"$WRONG_ISSUER_TOKEN\"; window.token=\"$VALID_AMP_TOKEN\";"

        private val albumTrack =
            """{"trackName":"Sharks","artistName":"Imagine Dragons","collectionId":$ALBUM_ID,"collectionName":"Mercury - Acts 1 & 2","kind":"song","trackId":1}"""
        private val singleTrack =
            """{"trackName":"Sharks","artistName":"Imagine Dragons","collectionId":$SINGLE_ID,"collectionName":"Sharks - Single","kind":"song","trackId":2}"""
    }
}
