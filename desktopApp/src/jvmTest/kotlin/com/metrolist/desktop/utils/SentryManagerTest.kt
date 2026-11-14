package com.metrolist.desktop.utils

import com.metrolist.desktop.playback.PlaybackErrorReporter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackErrorContext
import com.metrolist.shared.playback.PlaybackFailure
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryEvent
import io.sentry.SentryItemType
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.SentryThread
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SentryManagerTest {
    @Test
    fun disabledReportingDoesNotLoadTheSdk() {
        val urls =
            generateSequence(javaClass.classLoader) { it.parent }
                .filterIsInstance<URLClassLoader>()
                .flatMap { it.urLs.asSequence() }
                .plus(System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).toURI().toURL() })
                .distinct()
                .toList()
        assertTrue(urls.isNotEmpty())
        val blocked = mutableListOf<String>()
        val loader =
            object : URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader()) {
                override fun loadClass(
                    name: String,
                    resolve: Boolean,
                ): Class<*> {
                    if (name.startsWith("io.sentry.")) {
                        blocked.add(name)
                        throw ClassNotFoundException(name)
                    }
                    return super.loadClass(name, resolve)
                }
            }
        loader.use {
            it.loadClass(DisabledTelemetryProbe::class.java.name).getMethod("run").invoke(null)
        }
        assertTrue(blocked.isEmpty(), "Disabled telemetry loaded Sentry classes: $blocked")
    }

    @Test
    fun capturesOnlySanitizedDataInTheActualEnvelope() {
        val captured = AtomicReference<SentryEvent>()
        val sent = CountDownLatch(1)
        val nativeSent = CountDownLatch(1)
        val nativeSummary = AtomicReference<String>()
        Sentry.init { options ->
            configureDesktopSentry(options, desktopSentryRelease("test"), "test")
            options.isEnableUncaughtExceptionHandler = false
            options.setTransportFactory(
                ITransportFactory { transportOptions, _ ->
                    object : ITransport {
                        private val rateLimiter = RateLimiter(transportOptions)

                        override fun send(
                            envelope: SentryEnvelope,
                            hint: Hint,
                        ) {
                            envelope.items.forEach { item ->
                                if (item.header.type == SentryItemType.Attachment) {
                                    nativeSummary.set(item.data.decodeToString())
                                    nativeSent.countDown()
                                }
                                runCatching { item.getEvent(transportOptions.serializer) }
                                    .getOrNull()
                                    ?.let {
                                        captured.set(it)
                                        sent.countDown()
                                    }
                            }
                        }

                        override fun flush(timeoutMillis: Long) = Unit

                        override fun getRateLimiter(): RateLimiter = rateLimiter

                        override fun isHealthy(): Boolean = true

                        override fun close() = Unit

                        override fun close(isRestarting: Boolean) = Unit
                    }
                },
            )
        }
        try {
            Sentry.captureEvent(
                SentryEvent().apply {
                    message = Message().apply { formatted = "Song failed: https://cdn.example/media?sig=secret" }
                    request = Request().apply { url = "https://cdn.example/media?sig=secret" }
                    setExtra("playback.video_id", "stable-id")
                    setExtra("diagnostics", "token=secret")
                },
            )
            assertTrue(sent.await(5, TimeUnit.SECONDS), "Sentry transport did not receive an envelope")
            val event = assertNotNull(captured.get())
            assertEquals(desktopSentryRelease("test"), event.release)
            assertEquals("test", event.environment)
            assertEquals("Song failed: https://cdn.example/media?<redacted>", event.message?.formatted)
            assertEquals("https://cdn.example/media?<redacted>", event.request?.url)
            assertEquals(mapOf("playback.video_id" to "stable-id", "diagnostics" to "token=<redacted>"), event.extras)
            val crash =
                DesktopNativeCrashFile(
                    File("hs_err_pid123.log"),
                    "test",
                    summarizeHotspotCrash("# EXCEPTION_ACCESS_VIOLATION\n# C [avcodec.dll+0x123]\n# token=secret\n"),
                )
            assertTrue(capturePreviousDesktopExit(PreviousDesktopExit("version=test pid=123", listOf(crash))))
            assertTrue(nativeSent.await(5, TimeUnit.SECONDS), "Sentry transport did not receive the previous-exit attachment")
            assertTrue(nativeSummary.get().contains("version=test pid=123"))
            assertTrue(nativeSummary.get().contains("avcodec.dll"))
            assertTrue(!nativeSummary.get().contains("secret"))
            assertEquals("true", captured.get().getTag("native.hs_err"))
            assertEquals("desktop_termination", captured.get().getTag("error.domain"))
        } finally {
            Sentry.close()
        }
    }

    @Test
    fun sanitizerKeepsDiagnosticsButRemovesSecrets() {
        val signedUrl = "https://rr1---sn-x.googlevideo.com/videoplayback?sig=secret&ip=203.0.113.9"
        val frame = SentryStackFrame().apply { vars = mutableMapOf("secret" to "value") }
        val event =
            SentryEvent().apply {
                serverName = "host"
                request =
                    Request().apply {
                        url = signedUrl
                        method = "GET"
                        cookies = "SID=secret"
                        headers = mapOf("Authorization" to "Bearer secret")
                    }
                message = Message().apply { formatted = "Playback failed for $signedUrl" }
                throwable = IllegalStateException("token=secret")
                exceptions =
                    listOf(
                        SentryException().apply {
                            value = "HTTP 403 for $signedUrl"
                            stacktrace = SentryStackTrace(listOf(frame))
                        },
                    )
                threads =
                    listOf(
                        SentryThread().apply {
                            name = "ktor-client $signedUrl"
                            stacktrace = SentryStackTrace(listOf(frame))
                        },
                    )
                breadcrumbs =
                    listOf(
                        Breadcrumb("Low memory").apply { category = "device.event" },
                        Breadcrumb("GET").apply {
                            category = "http"
                            setData("url", signedUrl)
                            setData("http.query", "sig=secret")
                        },
                    )
                setExtra("playback.video_id", "stable-id")
            }

        sanitizeSentryEvent(event)

        val redactedUrl = "https://rr1---sn-x.googlevideo.com/videoplayback?<redacted>"
        assertNull(event.serverName)
        assertEquals(redactedUrl, event.request?.url)
        assertEquals("GET", event.request?.method)
        assertNull(event.request?.cookies)
        assertNull(event.request?.headers)
        assertEquals("Playback failed for $redactedUrl", event.message?.formatted)
        assertNull(event.throwable)
        assertEquals(
            "HTTP 403 for $redactedUrl",
            event.exceptions
                .orEmpty()
                .single()
                .value,
        )
        assertNull(frame.vars)
        assertEquals(
            "ktor-client $redactedUrl",
            event.threads
                .orEmpty()
                .single()
                .name,
        )
        assertEquals(listOf("Low memory", "GET"), event.breadcrumbs.orEmpty().map { it.message })
        assertEquals(mapOf("url" to redactedUrl), event.breadcrumbs.orEmpty()[1].data)
        assertEquals(mapOf("playback.video_id" to "stable-id"), event.extras)
    }
}

object DisabledTelemetryProbe {
    @JvmStatic
    fun run() {
        check(!SentryManager.isEnabled)
        SentryManager.shutdown()
        check(!SentryManager.reportPreviousExit(PreviousDesktopExit(null, emptyList())))
        PlaybackErrorReporter.setActivePlayback(
            SongItem(id = "test", title = "test", thumbnail = null, artists = emptyList(), album = null),
            null,
        )
        PlaybackErrorReporter.clearActivePlayback()
        val context = PlaybackErrorContext(stage = "test")
        PlaybackErrorReporter.report(IllegalStateException("test"), context)
        PlaybackErrorReporter.report(PlaybackFailure(stage = "test", message = "test"), context)
    }
}
