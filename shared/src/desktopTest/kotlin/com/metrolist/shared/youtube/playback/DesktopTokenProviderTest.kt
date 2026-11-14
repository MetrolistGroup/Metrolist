package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.potoken.PoTokenException
import com.metrolist.innertubex.extraction.potoken.requireTrustedAttestationInterpreterUrl
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopTokenProviderTest {
    @Test
    fun pageBoundMinterRequestsDesktopPageForMobileUserAgent() =
        runTest {
            val mobileUserAgent =
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 " +
                    "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
            val requests = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request.url.encodedPath
                        when (request.url.encodedPath) {
                            "/watch" -> {
                                assertEquals(mobileUserAgent, request.headers[HttpHeaders.UserAgent])
                                assertEquals("video", request.url.parameters["v"])
                                assertEquals("visitor", request.headers["X-Goog-Visitor-Id"])
                                assertEquals("session=test; SOCS=CAI", request.headers[HttpHeaders.Cookie])
                                if (request.url.parameters["app"] == "desktop") {
                                    respond(pageAttestation())
                                } else {
                                    respond(
                                        "",
                                        HttpStatusCode.Found,
                                        headersOf(HttpHeaders.Location, "https://m.youtube.com/watch?v=video"),
                                    )
                                }
                            }

                            "/botguard.js" -> {
                                respond("interpreter-source")
                            }

                            "/api/jnn/v1/GenerateIT" -> {
                                respond("[\"AQID\",1200]")
                            }

                            else -> {
                                error("Unexpected request: ${request.url}")
                            }
                        }
                    },
                )
            try {
                val minter =
                    PageBoundPoTokenMinter.create(
                        client,
                        ScriptedDesktopPoTokenRuntime(),
                        "visitor",
                        "session=test",
                        "video",
                        mobileUserAgent,
                    )

                assertEquals("AQID", minter.generate("visitor"))
                assertEquals("BAUG", minter.generate("video"))
                assertEquals(listOf("/watch", "/botguard.js", "/api/jnn/v1/GenerateIT"), requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun minterDeadlineIsAFailureRatherThanCallerCancellation() =
        runTest {
            val client = pageBoundClient()
            val runtime =
                object : DesktopPoTokenJavaScriptRuntime {
                    override val available = true

                    override suspend fun evaluate(script: String): String = """{"state":"running"}"""

                    override suspend fun reset() = Unit
                }
            try {
                assertFailsWith<PoTokenException> {
                    PageBoundPoTokenMinter.create(client, runtime, "visitor", null, "video", PAGE_BOUND_PO_TOKEN_USER_AGENT)
                }
                assertEquals(17_000L, testScheduler.currentTime)
            } finally {
                client.close()
            }
        }

    @Test
    fun failedPageMinterBacksOffWithoutDowngradingAttestation() =
        runBlocking {
            var now = 0L
            var pageRequests = 0
            var bootstraps = 0
            var failing = true
            val runtime = ScriptedDesktopPoTokenRuntime()
            val client =
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.encodedPath == "/watch" -> {
                                pageRequests++
                                if (failing) respond("unavailable", HttpStatusCode.ServiceUnavailable) else respond(pageAttestation())
                            }

                            request.url.host == "www.gstatic.com" -> {
                                respond("interpreter-source")
                            }

                            request.url.encodedPath == "/api/jnn/v1/GenerateIT" -> {
                                respond("[\"AQID\",1200]")
                            }

                            else -> {
                                respond("unavailable", HttpStatusCode.ServiceUnavailable)
                            }
                        }
                    },
                )
            val provider =
                DesktopTokenProvider(
                    client,
                    pageBoundRuntime = runtime,
                    startManagedServer = {
                        bootstraps++
                        false
                    },
                    elapsedMs = { now },
                )
            try {
                assertNull(provider.getPoToken("first", "visitor", null))
                provider.invalidateAttestation()
                assertNull(provider.getPoToken("second", "visitor", null))
                assertEquals(1, pageRequests)
                assertEquals(0, bootstraps)
                now = 30_000
                assertNull(provider.getPoToken("third", "visitor", null))
                assertEquals(2, pageRequests)
                assertEquals(0, bootstraps)
                now = 89_999
                assertNull(provider.getPoToken("fourth", "visitor", null))
                assertEquals(2, pageRequests)
                now++
                failing = false
                assertNotNull(provider.getPoToken("fifth", "visitor", null))
                assertEquals(3, pageRequests)
                assertEquals(0, bootstraps)
            } finally {
                provider.close()
            }
        }

    @Test
    fun sidecarFailuresBackOffAndRetryAfterExpiry() =
        runBlocking {
            var now = 0L
            var bootstraps = 0
            val client = HttpClient(MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) })
            val provider =
                DesktopTokenProvider(
                    client,
                    startManagedServer = {
                        bootstraps++
                        false
                    },
                    elapsedMs = { now },
                )
            try {
                assertNull(provider.getPoToken("video", "visitor", null))
                assertNull(provider.getPoToken("video", "visitor", null))
                assertEquals(1, bootstraps)
                now = 30_000
                assertNull(provider.getPoToken("video", "visitor", null))
                assertEquals(2, bootstraps)
            } finally {
                provider.close()
                client.close()
            }
        }

    @Test
    fun configuredLoopbackProviderIsExternal() {
        val endpoint = DesktopPoTokenEndpoint.parse("http://127.0.0.1:4417/")

        assertEquals("http://127.0.0.1:4417", endpoint.baseUrl)
        assertEquals(4417, endpoint.port)
        assertFalse(endpoint.managed)
        assertEquals(
            setOf(PoTokenProviderKind.WEB_BOTGUARD),
            endpoint.providerKinds(setOf(PoTokenProviderKind.WEB_BOTGUARD)),
        )
        assertEquals(
            setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION),
            endpoint.providerKinds(setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION)),
        )
    }

    @Test
    fun providerReadinessUsesHttpPing() =
        runBlocking {
            val paths = Collections.synchronizedList(mutableListOf<String>())
            val engine =
                MockEngine { request ->
                    paths += request.url.encodedPath
                    when (request.url.encodedPath) {
                        "/ping" -> {
                            respond("pong", HttpStatusCode.OK)
                        }

                        "/get_pot" -> {
                            val body = (request.body as TextContent).text
                            val binding = if ("visitor" in body) "visitor" else "video"
                            respond("""{"poToken":"token-$binding","contentBinding":"$binding"}""")
                        }

                        else -> {
                            error("Unexpected sidecar path ${request.url.encodedPath}")
                        }
                    }
                }
            val provider =
                DesktopTokenProvider(
                    client = HttpClient(engine),
                    providerUrl = "http://127.0.0.1:65534",
                    externalProviderKinds = setOf(PoTokenProviderKind.WEB_BOTGUARD),
                )

            val tokens = requireNotNull(provider.getPoToken("video", "visitor", null))

            assertEquals("token-visitor", tokens.playerRequestToken)
            assertEquals("token-video", tokens.streamingDataToken)
            assertEquals("/ping", paths.first())
            assertEquals(2, paths.count { it == "/get_pot" })
            provider.close()
        }

    @Test
    fun managedProviderCannotClaimExternalAttestationCapabilities() {
        val endpoint = DesktopPoTokenEndpoint.parse(null)

        assertEquals(
            setOf(PoTokenProviderKind.WEB_BOTGUARD),
            endpoint.providerKinds(setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION)),
        )
    }

    @Test
    fun externalProviderRejectsMissingOrNativeCapabilities() {
        val endpoint = DesktopPoTokenEndpoint.parse("http://127.0.0.1:4417")

        assertFailsWith<IllegalArgumentException> { endpoint.providerKinds(emptySet()) }
        assertFailsWith<IllegalArgumentException> {
            endpoint.providerKinds(setOf(PoTokenProviderKind.ANDROID_DROIDGUARD))
        }
    }

    @Test
    fun rejectsNonLoopbackOrAmbiguousProviderUrls() {
        assertFailsWith<IllegalArgumentException> { DesktopPoTokenEndpoint.parse("https://127.0.0.1:4416") }
        assertFailsWith<IllegalArgumentException> { DesktopPoTokenEndpoint.parse("http://localhost:4416") }
        assertFailsWith<IllegalArgumentException> { DesktopPoTokenEndpoint.parse("http://127.0.0.1:4416/provider") }
        assertFailsWith<IllegalArgumentException> { DesktopPoTokenEndpoint.parse("http://user@127.0.0.1:4416") }
        assertFailsWith<IllegalArgumentException> { DesktopPoTokenEndpoint.parse("http://127.0.0.1:4416?redirect=true") }
        assertFailsWith<IllegalArgumentException> { DesktopPoTokenEndpoint.parse("http://127.0.0.1:4416#fragment") }
        assertFailsWith<IllegalArgumentException> { DesktopPoTokenEndpoint.parse("http://example.com:4416") }
    }

    @Test
    fun managedProviderAdvertisesPageAttestationWhenRuntimeIsAvailable() {
        val runtime = FakeDesktopPoTokenRuntime(available = false)
        val provider = DesktopTokenProvider(HttpClient(MockEngine { error("No request expected") }), pageBoundRuntime = runtime)

        assertEquals(setOf(PoTokenProviderKind.WEB_BOTGUARD), provider.capabilities.providers)
        assertFalse(provider.capabilities.usesWebView)

        runtime.available = true
        assertEquals(
            setOf(PoTokenProviderKind.WEB_BOTGUARD, PoTokenProviderKind.WEBPAGE_ATTESTATION),
            provider.capabilities.providers,
        )
        assertTrue(provider.capabilities.usesWebView)
    }

    @Test
    fun sidecarFirstProviderLeavesWebViewUnusedWhileSidecarWorks() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/ping" -> {
                            respond("pong", HttpStatusCode.OK)
                        }

                        "/get_pot" -> {
                            val binding = if ("visitor" in (request.body as TextContent).text) "visitor" else "video"
                            respond("""{"poToken":"token-$binding","contentBinding":"$binding"}""")
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }
            val runtime = ScriptedDesktopPoTokenRuntime()
            val provider = DesktopTokenProvider(HttpClient(engine), pageBoundRuntime = runtime, preferSidecar = true)

            assertEquals(setOf(PoTokenProviderKind.WEB_BOTGUARD), provider.capabilities.providers)
            assertFalse(provider.capabilities.usesWebView)
            val tokens = requireNotNull(provider.getPoToken("video", "visitor", null))

            assertEquals("token-video", tokens.streamingDataToken)
            assertTrue(runtime.scripts.isEmpty())
            runtime.available = false
            provider.invalidateAttestation()
            assertEquals("token-video", provider.getPoToken("video", "visitor", null)?.streamingDataToken)
            assertEquals(setOf(PoTokenProviderKind.WEB_BOTGUARD), provider.capabilities.providers)
            assertTrue(runtime.scripts.isEmpty())
            provider.close()
        }

    @Test
    fun sidecarSuccessThenAttestationRejectionActivatesWebViewForRecoveryAndFollowingTracks() =
        runBlocking {
            val sidecarRequests = AtomicInteger()
            var pageUnavailable = false
            val client =
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.encodedPath == "/ping" -> {
                                respond("pong")
                            }

                            request.url.encodedPath == "/get_pot" -> {
                                sidecarRequests.incrementAndGet()
                                val binding =
                                    Json
                                        .decodeFromString<Map<String, String>>(
                                            (request.body as TextContent).text,
                                        ).getValue("content_binding")
                                respond("""{"poToken":"token-$binding","contentBinding":"$binding"}""")
                            }

                            request.url.encodedPath == "/watch" -> {
                                if (pageUnavailable) {
                                    respond(
                                        "unavailable",
                                        HttpStatusCode.ServiceUnavailable,
                                    )
                                } else {
                                    respond(pageAttestation())
                                }
                            }

                            request.url.host == "www.gstatic.com" -> {
                                respond("interpreter-source")
                            }

                            request.url.encodedPath == "/api/jnn/v1/GenerateIT" -> {
                                respond("[\"AQID\",1200]")
                            }

                            else -> {
                                error("Unexpected request: ${request.url}")
                            }
                        }
                    },
                )
            val runtime = ScriptedDesktopPoTokenRuntime()
            val provider = DesktopTokenProvider(client, providerUrl = null, pageBoundRuntime = runtime, preferSidecar = true)
            try {
                assertEquals("token-video", provider.getPoToken("video", "visitor", null)?.streamingDataToken)
                assertEquals(setOf(PoTokenProviderKind.WEB_BOTGUARD), provider.capabilities.providers)
                assertTrue(runtime.scripts.isEmpty())

                provider.invalidateAttestation()

                assertTrue(PoTokenProviderKind.WEBPAGE_ATTESTATION in provider.capabilities.providers)
                assertTrue(provider.capabilities.usesWebView)
                val candidates =
                    ContentAwareFallbackStrategy()
                        .selectClients(
                            ClientSelectionRequest(
                                hints = ContentHints(isExplicit = true),
                                authenticated = true,
                                availablePoTokenProviders = provider.capabilities.providers,
                                webViewAvailable = provider.capabilities.usesWebView,
                                excludedClients = setOf("VISIONOS_SABR__nopo"),
                            ),
                        ).candidates
                        .map { it.manifest?.id }
                assertTrue("WEB_SABR" in candidates)
                assertFalse("VISIONOS_SABR" in candidates)
                assertEquals("BAUG", provider.getPoToken("video", "visitor", null)?.streamingDataToken)
                assertEquals("BAUG", provider.getPoToken("next-video", "visitor", null)?.streamingDataToken)
                assertEquals(2, sidecarRequests.get())
                assertEquals(1, runtime.minterCount)
                runtime.available = false
                assertEquals("token-video", provider.getPoToken("video", "visitor", null)?.streamingDataToken)
                assertEquals(4, sidecarRequests.get())
                runtime.available = true

                provider.invalidateAttestation()
                pageUnavailable = true
                assertTrue(PoTokenProviderKind.WEBPAGE_ATTESTATION in provider.capabilities.providers)
                assertNull(provider.getPoToken("video", "visitor", null))
                assertNull(provider.getPoToken("next-video", "visitor", null))
                provider.prewarm(null)
                assertEquals(4, sidecarRequests.get())
                runtime.available = false
                assertFalse(PoTokenProviderKind.WEBPAGE_ATTESTATION in provider.capabilities.providers)
                assertFalse(provider.capabilities.usesWebView)
                provider.prewarm(null)
                assertEquals(5, sidecarRequests.get())
                assertEquals("token-other-video", provider.getPoToken("other-video", "visitor", null)?.streamingDataToken)
                assertEquals(7, sidecarRequests.get())
            } finally {
                provider.close()
                client.close()
            }
        }

    @Test
    fun pageFirstProviderResumesSidecarPrewarmAndMintingAfterRuntimeFailure() =
        runBlocking {
            val sidecarRequests = AtomicInteger()
            val client =
                HttpClient(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/ping" -> {
                                respond("pong")
                            }

                            "/get_pot" -> {
                                sidecarRequests.incrementAndGet()
                                val binding =
                                    Json
                                        .decodeFromString<Map<String, String>>(
                                            (request.body as TextContent).text,
                                        ).getValue("content_binding")
                                respond("""{"poToken":"token-$binding","contentBinding":"$binding"}""")
                            }

                            "/watch" -> {
                                respond(pageAttestation())
                            }

                            "/botguard.js" -> {
                                respond("interpreter-source")
                            }

                            "/api/jnn/v1/GenerateIT" -> {
                                respond("[\"AQID\",1200]")
                            }

                            else -> {
                                error("Unexpected request: ${request.url}")
                            }
                        }
                    },
                )
            val runtime = ScriptedDesktopPoTokenRuntime()
            val provider = DesktopTokenProvider(client, providerUrl = null, pageBoundRuntime = runtime)
            try {
                assertEquals("BAUG", provider.getPoToken("video", "visitor", null)?.streamingDataToken)
                provider.prewarm(null)
                assertEquals(0, sidecarRequests.get())

                runtime.beforeEvaluate = {
                    runtime.available = false
                    error("WebView evaluation timed out")
                }
                assertNull(provider.getPoToken("next-video", "visitor", null))
                assertFalse(runtime.available)
                assertFalse(PoTokenProviderKind.WEBPAGE_ATTESTATION in provider.capabilities.providers)

                provider.prewarm(null)
                assertEquals(1, sidecarRequests.get())
                val tokens = assertNotNull(provider.getPoToken("video", "visitor", null))
                assertEquals("token-visitor", tokens.playerRequestToken)
                assertEquals("token-video", tokens.streamingDataToken)
                assertEquals(3, sidecarRequests.get())
            } finally {
                provider.close()
                client.close()
            }
        }

    @Test
    fun sidecarFirstProviderFallsBackToWebViewWhenSidecarCannotStart() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath == "/ping" -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                        request.url.encodedPath == "/watch" -> respond(pageAttestation(), HttpStatusCode.OK)
                        request.url.host == "www.gstatic.com" -> respond("interpreter-source", HttpStatusCode.OK)
                        request.url.encodedPath == "/api/jnn/v1/GenerateIT" -> respond("[\"AQID\",1200]", HttpStatusCode.OK)
                        else -> error("Unexpected request: ${request.url}")
                    }
                }
            val runtime = ScriptedDesktopPoTokenRuntime()
            val provider =
                DesktopTokenProvider(
                    HttpClient(engine),
                    providerUrl = null,
                    pageBoundRuntime = runtime,
                    startManagedServer = { false },
                    preferSidecar = true,
                )

            val tokens = requireNotNull(provider.getPoToken("video", "visitor", null))

            assertEquals("BAUG", tokens.streamingDataToken)
            assertTrue(PoTokenProviderKind.WEBPAGE_ATTESTATION in provider.capabilities.providers)
            assertTrue(provider.capabilities.usesWebView)
            provider.close()
        }

    @Test
    fun cancellingATrackDoesNotDiscardBootstrapButClosingProviderDoes() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val client = HttpClient(MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) })
            val provider =
                DesktopTokenProvider(
                    client = client,
                    providerUrl = null,
                    startManagedServer = {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    },
                )
            try {
                val request = launch { provider.getPoToken("video", "visitor", null) }
                withTimeout(2_000.milliseconds) { started.await() }
                request.cancelAndJoin()
                assertFalse(cancelled.isCompleted)
                withTimeout(2_000.milliseconds) { provider.close() }
                assertTrue(cancelled.isCompleted)
            } finally {
                provider.close()
                client.close()
            }
        }

    @Test
    fun prewarmDoesNotStartSidecarWhenPageRuntimeIsAvailable() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        error("Lazy page runtime must not start the sidecar")
                    },
                )
            val provider = DesktopTokenProvider(client, pageBoundRuntime = FakeDesktopPoTokenRuntime(available = true))
            try {
                provider.prewarm(null)
                assertEquals(0, requests)
            } finally {
                provider.close()
                client.close()
            }
        }

    @Test
    fun attestationInvalidationResetsMountedRuntime() =
        runBlocking {
            val runtime = FakeDesktopPoTokenRuntime(available = true)
            val provider = DesktopTokenProvider(runtime)

            provider.invalidateAttestation()

            assertEquals(1, runtime.resetCount)
        }

    @Test
    fun interpreterMustUseGoogleHttpsHost() {
        assertEquals(
            "https://www.gstatic.com/botguard.js",
            requireTrustedAttestationInterpreterUrl("//www.gstatic.com/botguard.js"),
        )
        assertFailsWith<IllegalStateException> {
            requireTrustedAttestationInterpreterUrl("https://example.com/botguard.js")
        }
        assertFailsWith<IllegalStateException> {
            requireTrustedAttestationInterpreterUrl("http://www.gstatic.com/botguard.js")
        }
        assertFailsWith<IllegalStateException> {
            requireTrustedAttestationInterpreterUrl("https://www.gstatic.com:8443/botguard.js")
        }
        assertFailsWith<IllegalStateException> {
            requireTrustedAttestationInterpreterUrl("https://user@www.gstatic.com/botguard.js")
        }
        assertFailsWith<IllegalStateException> {
            requireTrustedAttestationInterpreterUrl("https://www.gstatic.com/other.js")
        }
    }

    @Test
    fun pageBoundWatchPageBodyIsBound() {
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond("x".repeat(4 * 1024 * 1024 + 1), HttpStatusCode.OK)
                    },
                )
            try {
                assertFailsWith<IllegalStateException> {
                    DesktopPageBoundPoTokenMinter.create(
                        client,
                        ScriptedDesktopPoTokenRuntime(),
                        "visitor",
                        null,
                        "video",
                        PAGE_BOUND_PO_TOKEN_USER_AGENT,
                    )
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun managedPageBoundProviderMintsSeparateTokensAndRemintsAfterInvalidation() =
        runBlocking {
            var pageRequests = 0
            var interpreterRequests = 0
            var integrityRequests = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.url.host == "www.youtube.com" && request.url.encodedPath == "/watch" -> {
                            pageRequests++
                            respond(pageAttestation(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                        }

                        request.url.host == "www.gstatic.com" -> {
                            interpreterRequests++
                            respond("interpreter-source", HttpStatusCode.OK)
                        }

                        else -> {
                            assertEquals("www.youtube.com", request.url.host)
                            assertEquals("/api/jnn/v1/GenerateIT", request.url.encodedPath)
                            integrityRequests++
                            respond("[\"AQID\",1200]", HttpStatusCode.OK)
                        }
                    }
                }
            val runtime = ScriptedDesktopPoTokenRuntime()
            val provider = DesktopTokenProvider(client = HttpClient(engine), pageBoundRuntime = runtime)

            val first = requireNotNull(provider.getPoToken("video", "visitor", null))
            val second = requireNotNull(provider.getPoToken("other-video", "visitor", null))
            provider.invalidateAttestation()
            val afterInvalidation = requireNotNull(provider.getPoToken("video", "visitor", null))

            assertEquals("AQID", first.playerRequestToken)
            assertEquals("BAUG", first.streamingDataToken)
            assertEquals("AQID", second.playerRequestToken)
            assertEquals("BAUG", second.streamingDataToken)
            assertEquals("AQID", afterInvalidation.playerRequestToken)
            assertEquals(2, pageRequests)
            assertEquals(2, interpreterRequests)
            assertEquals(2, integrityRequests)
            assertEquals(1, runtime.resetCount)
            assertTrue(runtime.scripts.any { it.contains("EVENT_ID: \"page-event\"") })
            assertTrue(runtime.scripts.any { it.contains("interpreter-source") })
            assertTrue(runtime.scripts.any { it.contains("\"interpreterHash\":\"hash\"") })
            provider.close()
        }

    @Test
    fun idleReleaseKeepsTokensAndWarmsOnlyTwoUpcomingVideos() =
        runTest {
            val maintenance = TestScope(TestCoroutineScheduler())
            val runtime = ScriptedDesktopPoTokenRuntime()
            val provider =
                DesktopTokenProvider(
                    client = pageBoundClient(),
                    pageBoundRuntime = runtime,
                    upcomingVideoIds = { listOf("next", "next", "after-next", "later") },
                    maintenanceScope = maintenance,
                )
            try {
                val first = requireNotNull(provider.getPoToken("video", "visitor", null))
                maintenance.advanceTimeBy(7_000)
                maintenance.runCurrent()
                assertEquals(1, runtime.resetCount)
                val scriptCount = runtime.scripts.size
                assertEquals(first, provider.getPoToken("video", "visitor", null))
                requireNotNull(provider.getPoToken("next", "visitor", null))
                requireNotNull(provider.getPoToken("after-next", "visitor", null))
                assertEquals(scriptCount, runtime.scripts.size)
                assertEquals(1, runtime.minterCount)

                requireNotNull(provider.getPoToken("later", "visitor", null))
                assertEquals(2, runtime.minterCount)
                maintenance.advanceTimeBy(7_000)
                maintenance.runCurrent()
                assertEquals(2, runtime.resetCount)
            } finally {
                provider.close()
            }
        }

    @Test
    fun cachedTokensExpireWithMinterAndFollowSessionInvalidation() =
        runTest {
            val maintenance = TestScope(TestCoroutineScheduler())
            val runtime = ScriptedDesktopPoTokenRuntime()
            var now = 0L
            val provider =
                DesktopTokenProvider(
                    client = pageBoundClient(),
                    pageBoundRuntime = runtime,
                    maintenanceScope = maintenance,
                    nowMs = { now },
                )
            try {
                requireNotNull(provider.getPoToken("video", "visitor", null))
                maintenance.advanceTimeBy(7_000)
                maintenance.runCurrent()
                requireNotNull(provider.getPoToken("video", "visitor", null))
                assertEquals(1, runtime.minterCount)
                now = Long.MAX_VALUE
                requireNotNull(provider.getPoToken("video", "visitor", null))
                assertEquals(2, runtime.minterCount)
                now = 0L
                requireNotNull(provider.getPoToken("video", "other-visitor", null))
                assertEquals(3, runtime.minterCount)
                requireNotNull(provider.getPoToken("video", "other-visitor", "cookie"))
                assertEquals(4, runtime.minterCount)
                provider.invalidateAttestation()
                requireNotNull(provider.getPoToken("video", "other-visitor", "cookie"))
                assertEquals(5, runtime.minterCount)
            } finally {
                provider.close()
            }
        }

    @Test
    fun cancelledMintStillReleasesRuntimeAndRecreatesMinter() =
        runTest {
            val maintenance = TestScope(TestCoroutineScheduler())
            val runtime = ScriptedDesktopPoTokenRuntime()
            val provider = DesktopTokenProvider(client = pageBoundClient(), pageBoundRuntime = runtime, maintenanceScope = maintenance)
            try {
                val started = CompletableDeferred<Unit>()
                runtime.beforeEvaluate = { script ->
                    if (script.contains("obtainPoToken(")) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
                val request = async { provider.getPoToken("video", "visitor", null) }
                started.await()
                maintenance.advanceTimeBy(20_000)
                maintenance.runCurrent()
                assertEquals(0, runtime.resetCount)
                request.cancelAndJoin()
                runtime.beforeEvaluate = {}
                maintenance.advanceTimeBy(7_000)
                maintenance.runCurrent()
                assertEquals(1, runtime.resetCount)
                requireNotNull(provider.getPoToken("video", "visitor", null))
                assertEquals(2, runtime.minterCount)
            } finally {
                provider.close()
            }
        }

    @Test
    fun repeatedCacheHitsDoNotKeepRuntimeAliveAndCloseCancelsIdleWork() =
        runTest {
            val maintenance = TestScope(TestCoroutineScheduler())
            val runtime = ScriptedDesktopPoTokenRuntime()
            val provider = DesktopTokenProvider(client = pageBoundClient(), pageBoundRuntime = runtime, maintenanceScope = maintenance)
            requireNotNull(provider.getPoToken("video", "visitor", null))
            repeat(7) {
                maintenance.advanceTimeBy(1_000)
                maintenance.runCurrent()
                requireNotNull(provider.getPoToken("video", "visitor", null))
            }
            assertEquals(1, runtime.resetCount)
            requireNotNull(provider.getPoToken("other-video", "visitor", null))
            provider.close()
            val resets = runtime.resetCount
            maintenance.advanceTimeBy(10_000)
            maintenance.runCurrent()
            assertEquals(resets, runtime.resetCount)
        }

    @Test
    fun speculativeMintFailureStillReleasesRuntime() =
        runTest {
            val maintenance = TestScope(TestCoroutineScheduler())
            val runtime = ScriptedDesktopPoTokenRuntime()
            val provider =
                DesktopTokenProvider(
                    client = pageBoundClient(),
                    pageBoundRuntime = runtime,
                    maintenanceScope = maintenance,
                    upcomingVideoIds = { listOf("next") },
                )
            try {
                requireNotNull(provider.getPoToken("video", "visitor", null))
                runtime.beforeEvaluate = { error("Speculative mint failed") }
                maintenance.advanceTimeBy(7_000)
                maintenance.runCurrent()
                assertEquals(1, runtime.resetCount)
                requireNotNull(provider.getPoToken("video", "visitor", null))
            } finally {
                provider.close()
            }
        }

    @Test
    fun pageBoundCacheIsBounded() =
        runTest {
            val maintenance = TestScope(TestCoroutineScheduler())
            val runtime = ScriptedDesktopPoTokenRuntime()
            val provider = DesktopTokenProvider(client = pageBoundClient(), pageBoundRuntime = runtime, maintenanceScope = maintenance)
            try {
                repeat(33) { requireNotNull(provider.getPoToken("video-$it", "visitor", null)) }
                maintenance.advanceTimeBy(7_000)
                maintenance.runCurrent()
                requireNotNull(provider.getPoToken("video-32", "visitor", null))
                assertEquals(1, runtime.minterCount)
                requireNotNull(provider.getPoToken("video-0", "visitor", null))
                assertEquals(2, runtime.minterCount)
            } finally {
                provider.close()
            }
        }

    private fun pageBoundClient(): HttpClient =
        HttpClient(
            MockEngine { request ->
                when {
                    request.url.encodedPath == "/watch" -> respond(pageAttestation(), HttpStatusCode.OK)
                    request.url.host == "www.gstatic.com" -> respond("interpreter-source", HttpStatusCode.OK)
                    request.url.encodedPath == "/api/jnn/v1/GenerateIT" -> respond("[\"AQID\",1200]", HttpStatusCode.OK)
                    else -> error("Unexpected request: ${request.url}")
                }
            },
        )

    private fun pageAttestation(): String =
        """
        <script>
        ytcfg.set({"EVENT_ID":"page-event"});
        window.ytAtN({R:'{"bgChallenge":{"interpreterUrl":{"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue":"//www.gstatic.com/botguard.js"},"interpreterHash":"hash","program":"program","globalName":"BotGuard"}}'});
        </script>
        """.trimIndent()

    private class FakeDesktopPoTokenRuntime(
        override var available: Boolean,
    ) : DesktopPoTokenJavaScriptRuntime {
        var resetCount = 0

        override suspend fun evaluate(script: String): String = error("No JavaScript evaluation expected")

        override suspend fun reset() {
            resetCount++
        }
    }

    private class ScriptedDesktopPoTokenRuntime : DesktopPoTokenJavaScriptRuntime {
        override var available = true
        val scripts = mutableListOf<String>()
        var resetCount = 0
        private var nextTokenValue = ""
        var minterCount = 0
        private var initialized = false
        var beforeEvaluate: suspend (String) -> Unit = {}

        override suspend fun evaluate(script: String): String {
            beforeEvaluate(script)
            scripts += script
            if (script.contains("createPoTokenMinter(")) {
                minterCount++
                initialized = true
            }
            if (script.contains("obtainPoToken(")) {
                check(initialized) { "Attempted to mint with a disposed VM" }
                nextTokenValue = if (script.contains("118,105,115")) "1,2,3" else "4,5,6"
            }
            return when {
                script.contains("JSON.stringify(globalThis.__metrolistPoSnapshot") -> {
                    "{\"state\":\"ready\",\"value\":\"botguard-response\"}"
                }

                script.contains("JSON.stringify(globalThis.__metrolistPoMinter") -> {
                    "{\"state\":\"ready\",\"value\":\"ready\"}"
                }

                script.contains("JSON.stringify(globalThis.__metrolistPoToken") -> {
                    "{\"state\":\"ready\",\"value\":\"$nextTokenValue\"}"
                }

                else -> {
                    "started"
                }
            }
        }

        override suspend fun reset() {
            resetCount++
            initialized = false
        }
    }
}
