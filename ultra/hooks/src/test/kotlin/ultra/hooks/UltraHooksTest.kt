package ultra.hooks

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

class UltraHooksTest {
    @After
    fun tearDown() = UltraHooks.uninstall()

    private val noopInterceptor = Interceptor { chain -> chain.proceed(chain.request()) }

    private fun interceptorCount(configure: (OkHttpClient.Builder) -> Unit): Int =
        OkHttpClient.Builder().also(configure).build().interceptors.size

    private class FakeProcessor : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat) = inputAudioFormat

        override fun queueInput(inputBuffer: ByteBuffer) = Unit
    }

    private val request = StreamRequest("video")
    private val stream = ResolvedStream(url = "https://example.invalid/a", mimeType = "audio/mp4", sourceName = "test")

    @Test
    fun `without an installed registry every seam is a no-op`() = runBlocking {
        assertFalse(UltraHooks.isInstalled)
        assertEquals(0, interceptorCount(UltraHooks::configureApiClient))
        assertEquals(0, interceptorCount(UltraHooks::configureStreamClient))
        assertEquals(0, UltraHooks.audioProcessors().size)
        assertSame(StreamResolution.Pass, UltraHooks.resolveStream(request))
        assertTrue(UltraHooks.destinations().isEmpty())
        assertTrue(UltraHooks.settingsEntries().isEmpty())
        assertEquals(HookStats(false, 0, 0, 0, 0, 0, 0), UltraHooks.stats())
    }

    @Test
    fun `an empty but installed registry reports installed with zero contributors`() {
        UltraHooks.install(HookRegistry())
        assertTrue(UltraHooks.isInstalled)
        assertEquals(HookStats(true, 0, 0, 0, 0, 0, 0), UltraHooks.stats())
    }

    @Test
    fun `api and stream client contributors are applied independently`() {
        UltraHooks.install(
            HookRegistry(
                apiClient = listOf(ApiClientContributor { it.addInterceptor(noopInterceptor) }),
                streamClient =
                    listOf(
                        StreamClientContributor { it.addInterceptor(noopInterceptor) },
                        StreamClientContributor { it.addInterceptor(noopInterceptor) },
                    ),
            ),
        )

        assertEquals(1, interceptorCount(UltraHooks::configureApiClient))
        assertEquals(2, interceptorCount(UltraHooks::configureStreamClient))
    }

    @Test
    fun `a throwing client contributor is skipped and the others still run`() {
        UltraHooks.install(
            HookRegistry(
                apiClient =
                    listOf(
                        ApiClientContributor { error("boom") },
                        ApiClientContributor { it.addInterceptor(noopInterceptor) },
                    ),
            ),
        )

        assertEquals(1, interceptorCount(UltraHooks::configureApiClient))
    }

    @Test
    fun `audio processors are created fresh on every call and appended in contributor order`() {
        val first = AudioProcessorsContributor { listOf(FakeProcessor()) }
        val second = AudioProcessorsContributor { listOf(FakeProcessor(), FakeProcessor()) }
        UltraHooks.install(HookRegistry(audioProcessors = listOf(first, second)))

        val a = UltraHooks.audioProcessors()
        val b = UltraHooks.audioProcessors()

        assertEquals(3, a.size)
        assertEquals(3, b.size)
        a.indices.forEach { assertNotSame("instance $it must not be shared between sinks", a[it], b[it]) }
    }

    @Test
    fun `a throwing audio contributor does not lose the healthy ones`() {
        UltraHooks.install(
            HookRegistry(
                audioProcessors =
                    listOf(
                        AudioProcessorsContributor { error("boom") },
                        AudioProcessorsContributor { listOf(FakeProcessor()) },
                    ),
            ),
        )

        assertEquals(1, UltraHooks.audioProcessors().size)
    }

    @Test
    fun `first resolver that does not pass wins`() = runBlocking {
        var thirdCalled = false
        UltraHooks.install(
            HookRegistry(
                streamResolvers =
                    listOf(
                        StreamResolverContributor { StreamResolution.Pass },
                        StreamResolverContributor { StreamResolution.Resolved(stream) },
                        StreamResolverContributor {
                            thirdCalled = true
                            StreamResolution.Pass
                        },
                    ),
            ),
        )

        assertEquals(StreamResolution.Resolved(stream), UltraHooks.resolveStream(request))
        assertFalse("resolvers after the winner must not run", thirdCalled)
    }

    @Test
    fun `a throwing resolver counts as pass and a later resolver can still answer`() = runBlocking {
        UltraHooks.install(
            HookRegistry(
                streamResolvers =
                    listOf(
                        StreamResolverContributor { error("boom") },
                        StreamResolverContributor { StreamResolution.Resolved(stream) },
                    ),
            ),
        )

        assertEquals(StreamResolution.Resolved(stream), UltraHooks.resolveStream(request))
    }

    @Test
    fun `a throwing resolver alone falls through to pass so upstream playback proceeds`() = runBlocking {
        UltraHooks.install(HookRegistry(streamResolvers = listOf(StreamResolverContributor { error("boom") })))

        assertSame(StreamResolution.Pass, UltraHooks.resolveStream(request))
    }

    @Test
    fun `failed resolutions are returned to the caller`() = runBlocking {
        val cause = IllegalStateException("blocked by policy")
        UltraHooks.install(
            HookRegistry(streamResolvers = listOf(StreamResolverContributor { StreamResolution.Failed(cause) })),
        )

        val resolution = UltraHooks.resolveStream(request)

        assertSame(cause, (resolution as StreamResolution.Failed).cause)
    }

    @Test
    fun `cancellation is not swallowed`() {
        UltraHooks.install(
            HookRegistry(
                streamResolvers = listOf(StreamResolverContributor { throw CancellationException("cancelled") }),
            ),
        )

        try {
            runBlocking { UltraHooks.resolveStream(request) }
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }

    @Test
    fun `destinations and settings entries are merged and de-duplicated by route`() {
        fun destination(route: String) = UltraDestination(route, titleRes = 1) { }

        UltraHooks.install(
            HookRegistry(
                destinations =
                    listOf(
                        DestinationContributor { listOf(destination("a"), destination("b")) },
                        DestinationContributor { error("boom") },
                        DestinationContributor { listOf(destination("b"), destination("c")) },
                    ),
                settingsEntries =
                    listOf(
                        SettingsEntryContributor { listOf(UltraSettingsEntry("a", 1, 2)) },
                        SettingsEntryContributor { listOf(UltraSettingsEntry("a", 3, 4), UltraSettingsEntry("b", 1, 2)) },
                    ),
            ),
        )

        assertEquals(listOf("a", "b", "c"), UltraHooks.destinations().map { it.route })
        assertEquals(listOf("a", "b"), UltraHooks.settingsEntries().map { it.route })
        assertEquals(
            "first registration of a route wins",
            1,
            UltraHooks.settingsEntries().first { it.route == "a" }.titleRes,
        )
    }

    @Test
    fun `reinstalling replaces the previous registry`() {
        UltraHooks.install(HookRegistry(apiClient = listOf(ApiClientContributor { it.addInterceptor(noopInterceptor) })))
        UltraHooks.install(HookRegistry())

        assertEquals(0, interceptorCount(UltraHooks::configureApiClient))
        assertEquals(0, UltraHooks.stats().apiClient)
    }
}
