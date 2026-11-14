package com.metrolist.shared.api.lastfm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LastFmPhotoDetailsTest {
    private val pageUrl = "https://www.last.fm/music/Sydney+Rose/+images/f1c2577f5d06374747908506356ddd32"
    private val photoHtml =
        """
        <h3 class="gallery-image-title">Sydney &amp; Rose &#x1F339;</h3>
        <p class="gallery-image-description">A &quot;portrait&quot;.<br>Photo by <a href="https://example.com">Caity</a>.</p>
        <p class="gallery-image-uploaded-by">
            Uploaded by <a href="/user/emilychau">emilychau</a> on 28 Feb 2025
        </p>
        """.trimIndent()

    @BeforeTest
    fun configureApi() {
        LastFM.initialize("api-key", "secret")
    }

    @Test
    fun parsesImageMetadataRatherThanArtistOrSidebarDetails() {
        val html = """<a href="/user/unrelated">Unrelated user</a>""" + photoHtml + photoHtml
        assertEquals(
            LastFmPhotoDetails(
                title = "Sydney & Rose 🌹",
                description = "A \"portrait\". Photo by Caity.",
                authorName = "emilychau",
                authorProfileUrl = "https://www.last.fm/user/emilychau",
                uploadDate = "28 Feb 2025",
            ),
            parsePhotoDetails(html),
        )
    }

    @Test
    fun absentFieldsStayAbsentAndDeletedUsersAreNotAttributedToTheArtist() {
        assertEquals(
            LastFmPhotoDetails(title = "Photo"),
            parsePhotoDetails("""<h3 class='gallery-image-title'>Photo</h3><p class='gallery-image-uploaded-by'>Deleted user</p>"""),
        )
        assertNull(parsePhotoDetails("<title>Last.fm - Temporarily Unavailable</title>"))
    }

    @Test
    fun entitiesDecodeAsTextAndPreserveUnknownReferences() {
        for ((encoded, decoded) in listOf(
            "&lt;portrait&gt; &amp; &#invalid;" to "<portrait> & &#invalid;",
            "&copy; Jane &mdash; portrait &#X1F339; &unknown;" to "© Jane — portrait 🌹 &unknown;",
            "&#0; &#xD800; &#x110000;" to "&#0; &#xD800; &#x110000;",
        )) {
            assertEquals(decoded, parsePhotoDetails("""<h3 class="gallery-image-title">$encoded</h3>""")?.title)
        }
    }

    @Test
    fun avatarLookupDoesNotRequireLoginAndFailuresKeepPhotoMetadata() =
        runTest {
            for (available in listOf(true, false)) {
                HttpClient(
                    MockEngine { request ->
                        if (request.url.host == "www.last.fm") {
                            respond(photoHtml)
                        } else {
                            assertEquals("user.getinfo", request.url.parameters["method"])
                            assertEquals("emilychau", request.url.parameters["user"])
                            if (available) {
                                respond("""{"user":{"image":[{"#text":"https://example.com/avatar.jpg"}]}}""")
                            } else {
                                respond("Unavailable", HttpStatusCode.InternalServerError)
                            }
                        }
                    },
                ).use { client ->
                    val details = LastFM.getPhotoDetails(client, pageUrl)
                    assertEquals("emilychau", details?.authorName)
                    assertEquals("Sydney & Rose 🌹", details?.title)
                    assertEquals(if (available) "https://example.com/avatar.jpg" else null, details?.authorAvatarUrl)
                }
            }
        }

    @Test
    fun challengedPageUsesTheExistingRendererAndPropagatesCancellation() =
        runTest {
            HttpClient(MockEngine { respond("<title>Client Challenge</title>", HttpStatusCode.Forbidden) }).use { client ->
                assertEquals(
                    "Photo",
                    LastFM
                        .getPhotoDetails(client, pageUrl) { url ->
                            assertEquals(pageUrl, url)
                            """<h3 class="gallery-image-title">Photo</h3>"""
                        }?.title,
                )
                assertNull(LastFM.getPhotoDetails(client, pageUrl) { null })
                assertFailsWith<CancellationException> {
                    LastFM.getPhotoDetails(client, pageUrl) { throw CancellationException("Viewer closed") }
                }
            }
        }

    @Test
    fun rateLimitsPreserveRetryAfterAndAnyLoadedPhotoMetadata() =
        runTest {
            for (limitedHost in listOf("www.last.fm", "ws.audioscrobbler.com")) {
                HttpClient(
                    MockEngine { request ->
                        if (request.url.host == limitedHost) {
                            respond("Rate limited", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "300"))
                        } else {
                            respond(photoHtml)
                        }
                    },
                ).use { client ->
                    val error =
                        assertFailsWith<LastFmPhotoBackoff> {
                            LastFM.getPhotoDetails(client, pageUrl) { error("Must not render a rate limit") }
                        }
                    assertEquals(300_000L, error.retryAfterMs)
                    assertEquals(if (limitedHost == "www.last.fm") null else "emilychau", error.details?.authorName)
                }
            }
        }

    @Test
    fun rejectsNonLastFmImagePagesBeforeFetchingOrRendering() =
        runTest {
            HttpClient(MockEngine { error("Untrusted URLs must not be fetched") }).use { client ->
                listOf(
                    "https://www.last.fm.evil.test/music/Artist/+images/f1c2577f5d06374747908506356ddd32",
                    "https://www.last.fm@evil.test/music/Artist/+images/f1c2577f5d06374747908506356ddd32",
                    "http://www.last.fm/music/Artist/+images/f1c2577f5d06374747908506356ddd32",
                    "https://www.last.fm/user/emilychau",
                ).forEach { url ->
                    assertNull(LastFM.getPhotoDetails(client, url) { error("Untrusted URLs must not be rendered") })
                }
            }
        }

    @Test
    fun uploadDatesAcceptOnlyLastFmsCalendarFormat() {
        for ((text, date) in listOf(
            "28 Feb 2025" to LocalDate(2025, 2, 28),
            " 1 Mar 2019 " to LocalDate(2019, 3, 1),
            "3 hours ago" to null,
            "28. Februar 2025" to null,
        )) {
            assertEquals(date, parsePhotoUploadDate(text))
        }
    }
}
