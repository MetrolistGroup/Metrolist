package com.metrolist.shared.youtube.playback

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class AndroidTokenProviderTest {
    private val dispatcher = StandardTestDispatcher()
    private val looper get() = shadowOf(Looper.getMainLooper())

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun prewarmAndFirstMintShareTheirBinding() =
        runTest {
            val context = ServiceContext()
            val provider = AndroidTokenProvider(context)
            provider.prewarm()
            assertEquals(1, context.binds)
            assertEquals(0, context.requests)
            assertNotNull(mint(provider))
            assertEquals(1, context.binds)
            assertEquals(0, context.unbinds)
            provider.close()
            assertEquals(1, context.unbinds)
        }

    @Test
    fun unusedPrewarmReleasesItsBindingAfterIdleTimeout() =
        runTest {
            val context = ServiceContext()
            val provider = AndroidTokenProvider(context)
            provider.prewarm()
            looper.idleFor(Duration.ofMillis(RemotePoTokenMinter.IDLE_TIMEOUT_MS))
            assertEquals(1, context.unbinds)
            assertNotNull(mint(provider))
            assertEquals(2, context.binds)
            provider.close()
        }

    @Test
    fun cachedTokenDoesNotWaitForUnrelatedMint() =
        runTest {
            val context = ServiceContext()
            val provider = AndroidTokenProvider(context)
            val cached = assertNotNull(mint(provider))
            context.respond = false
            val stalled = async { provider.getPoToken("next", "visitor", "cookie") }
            runCurrent()
            looper.idle()
            assertFalse(stalled.isCompleted)
            val hit = async { provider.getPoToken("video", "visitor", "cookie") }
            runCurrent()
            assertTrue(hit.isCompleted)
            assertEquals(cached, hit.await())
            assertEquals(2, context.requests)
            stalled.cancel()
            runCurrent()
            provider.close()
        }

    @Test
    fun concurrentMissesRecheckCacheAfterMintCompletes() =
        runTest {
            val context = ServiceContext()
            val provider = AndroidTokenProvider(context)
            val first = async { provider.getPoToken("video", "visitor", "cookie") }
            val second = async { provider.getPoToken("video", "visitor", "cookie") }
            runCurrent()
            looper.idle()
            runCurrent()
            assertEquals(assertNotNull(first.await()), second.await())
            assertEquals(1, context.requests)
            provider.close()
        }

    @Test
    fun cachedTokensSurviveIdleUnbindAndMissesRestartTheService() =
        runTest {
            val context = ServiceContext()
            val provider = AndroidTokenProvider(context)
            assertNotNull(mint(provider))
            looper.idleFor(Duration.ofMillis(RemotePoTokenMinter.IDLE_TIMEOUT_MS))
            assertEquals(1, context.unbinds)
            assertNotNull(provider.getPoToken("video", "visitor", "cookie"))
            assertEquals(1, context.binds)
            assertNotNull(mint(provider, video = "next"))
            assertEquals(2, context.binds)
            assertEquals(2, context.requests)
            provider.close()
            assertEquals(2, context.unbinds)
            assertEquals(2, context.actions.distinct().size)
        }

    @Test
    fun failureCooldownIsLocalAndInvalidationAllowsImmediateRetry() =
        runTest {
            val context = ServiceContext().apply { failMint = true }
            val provider = AndroidTokenProvider(context)
            assertNull(mint(provider))
            assertEquals(1, context.unbinds)
            assertNull(provider.getPoToken("next", "visitor", "cookie"))
            assertEquals(1, context.binds)
            provider.invalidateAttestation()
            context.failMint = false
            assertNotNull(mint(provider))
            provider.close()
        }

    @Test
    fun changedCredentialsAndInvalidationDiscardCachedTokens() =
        runTest {
            val context = ServiceContext()
            val provider = AndroidTokenProvider(context)
            assertNotNull(mint(provider))
            assertNotNull(mint(provider, cookie = "changed"))
            assertEquals(2, context.binds)
            provider.invalidateAttestation()
            assertNotNull(mint(provider, cookie = "changed"))
            assertEquals(3, context.binds)
            provider.close()
        }

    @Test
    fun callerCancellationKeepsMintAliveUntilProviderCloses() =
        runTest {
            for (connect in listOf(false, true)) {
                val context =
                    ServiceContext().apply {
                        connectImmediately = connect
                        respond = false
                    }
                val provider = AndroidTokenProvider(context)
                val request = async { provider.getPoToken("video", "visitor", null) }
                runCurrent()
                looper.idle()
                request.cancel()
                runCurrent()
                assertTrue(request.isCancelled)
                assertEquals(0, context.unbinds)
                provider.close()
                assertEquals(1, context.unbinds)
            }
        }

    @Test
    fun cancelledPreloadFinishesAndForegroundMintReusesTheService() =
        runTest {
            val context = ServiceContext().apply { respond = false }
            val provider = AndroidTokenProvider(context)
            val preload = async { provider.getPoToken("preload", "visitor", "cookie") }
            runCurrent()
            looper.idle()
            preload.cancel()
            runCurrent()
            val foreground = async { provider.getPoToken("foreground", "visitor", "cookie") }
            runCurrent()
            assertFalse(foreground.isCompleted)
            assertEquals(0, context.unbinds)
            context.respond = true
            context.completePendingMint()
            looper.idle()
            runCurrent()
            looper.idle()
            runCurrent()
            assertNotNull(foreground.await())
            assertEquals(1, context.binds)
            assertEquals(2, context.requests)
            assertNotNull(provider.getPoToken("preload", "visitor", "cookie"))
            assertEquals(2, context.requests)
            provider.close()
        }

    @Test
    fun cancellingOneWaiterDoesNotCancelAnotherWaiterForTheSameToken() =
        runTest {
            val context = ServiceContext().apply { respond = false }
            val provider = AndroidTokenProvider(context)
            val first = async { provider.getPoToken("video", "visitor", "cookie") }
            val second = async { provider.getPoToken("video", "visitor", "cookie") }
            runCurrent()
            looper.idle()
            first.cancel()
            runCurrent()
            context.completePendingMint()
            looper.idle()
            runCurrent()
            assertNotNull(second.await())
            assertEquals(1, context.requests)
            assertEquals(0, context.unbinds)
            provider.close()
        }

    @Test
    fun invalidationAndIdleCloseRetryActiveAndQueuedCallers() =
        runTest {
            for (invalidate in listOf(true, false)) {
                val context = ServiceContext().apply { respond = false }
                val provider = AndroidTokenProvider(context)
                val first = async { provider.getPoToken("video", "visitor", "cookie") }
                val queued = async { provider.getPoToken("next", "visitor", "cookie") }
                runCurrent()
                looper.idle()
                assertEquals(1, context.requests)

                if (invalidate) provider.invalidateAttestation() else provider.close()
                runCurrent()
                assertFalse(first.isCancelled)
                assertFalse(queued.isCancelled)
                assertEquals(1, context.unbinds)
                context.respond = true
                looper.idle()
                runCurrent()
                looper.idle()
                runCurrent()
                assertNotNull(first.await())
                assertNotNull(queued.await())
                assertEquals(2, context.binds)
                assertEquals(3, context.requests)
                provider.close()
            }
        }

    @Test
    fun invalidationOnImmediateMainCompletesBeforeTheReplacementMint() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val context = ServiceContext().apply { respond = false }
            val provider = AndroidTokenProvider(context)
            val request = async { provider.getPoToken("video", "visitor", "cookie") }
            runCurrent()
            looper.idle()
            val invalidation = async { provider.invalidateAttestation() }
            runCurrent()
            assertTrue(invalidation.isCompleted)
            assertFalse(request.isCompleted)
            context.respond = true
            looper.idle()
            runCurrent()
            assertNotNull(request.await())
            assertEquals(2, context.binds)
            provider.close()
        }

    @Test
    fun callerCancellationAfterInvalidationStillPropagatesWithoutRetrying() =
        runTest {
            val context = ServiceContext().apply { respond = false }
            val provider = AndroidTokenProvider(context)
            val request = async { provider.getPoToken("video", "visitor", "cookie") }
            runCurrent()
            looper.idle()
            provider.invalidateAttestation()
            runCurrent()
            assertFalse(request.isCancelled)
            request.cancel()
            runCurrent()
            assertFailsWith<CancellationException> { request.await() }
            assertEquals(2, context.binds)
            provider.close()
            runCurrent()
            assertEquals(2, context.unbinds)
            assertEquals(2, context.binds)
        }

    @Test
    fun bindingAndReplyTimeoutsBecomeFailuresWithCooldown() =
        runTest {
            for (connect in listOf(false, true)) {
                val context =
                    ServiceContext().apply {
                        connectImmediately = connect
                        respond = false
                    }
                val provider = AndroidTokenProvider(context)
                val request = async { provider.getPoToken("video", "visitor", null) }
                runCurrent()
                looper.idle()
                advanceTimeBy(RemotePoTokenMinter.REQUEST_TIMEOUT_MS)
                runCurrent()
                assertNull(request.await())
                assertEquals(1, context.unbinds)
                assertNull(provider.getPoToken("next", "visitor", null))
                assertEquals(1, context.binds)
                provider.close()
            }
        }

    @Test
    fun deathAndNullBindingFailPendingRequestsAndAllowRebinding() =
        runTest {
            val disconnects: List<(ServiceConnection, ComponentName) -> Unit> =
                listOf(
                    { connection, name -> connection.onServiceDisconnected(name) },
                    { connection, name -> connection.onBindingDied(name) },
                    { connection, name -> connection.onNullBinding(name) },
                    { connection, _ -> (connection as IBinder.DeathRecipient).binderDied() },
                )
            for (disconnect in disconnects) {
                val context = ServiceContext().apply { respond = false }
                val provider = AndroidTokenProvider(context)
                val request = async { provider.getPoToken("video", "visitor", null) }
                runCurrent()
                looper.idle()
                disconnect(context.connection, context.component)
                looper.idle()
                runCurrent()
                assertNull(request.await())
                assertEquals(1, context.unbinds)
                provider.invalidateAttestation()
                context.respond = true
                assertNotNull(mint(provider))
                provider.close()
            }
        }

    @Test
    fun attestationExpiryBoundsIdleLifetimeAndCacheLifetime() =
        runTest {
            val context = ServiceContext().apply { expiresAtMs = 1L }
            val provider = AndroidTokenProvider(context)
            assertNotNull(mint(provider))
            looper.idle()
            assertEquals(1, context.unbinds)
            context.expiresAtMs = Long.MAX_VALUE
            assertNotNull(mint(provider))
            assertEquals(2, context.binds)
            provider.close()
        }

    private suspend fun TestScope.mint(
        provider: AndroidTokenProvider,
        video: String = "video",
        cookie: String? = "cookie",
    ) = async { provider.getPoToken(video, "visitor", cookie) }.let { request ->
        runCurrent()
        looper.idle()
        runCurrent()
        assertTrue(request.isCompleted)
        request.await()
    }

    private class ServiceContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val component = ComponentName(this, PoTokenService::class.java)
        lateinit var connection: ServiceConnection
        var connects = false
        var binds = 0
        var unbinds = 0
        var requests = 0
        var connectImmediately = true
        var respond = true
        var failMint = false
        var expiresAtMs = Long.MAX_VALUE
        val actions = mutableListOf<String?>()
        private var pendingMint: Message? = null
        private val service =
            Messenger(
                Handler(Looper.getMainLooper()) { request ->
                    requests++
                    if (respond) reply(request) else pendingMint = Message.obtain(request)
                    true
                },
            )

        fun completePendingMint() {
            val request = requireNotNull(pendingMint)
            pendingMint = null
            reply(request)
            request.recycle()
        }

        private fun reply(request: Message) {
            val data = Bundle()
            if (!failMint) {
                data.putString(PoTokenIpc.PLAYER_TOKEN, "session-token")
                data.putString(PoTokenIpc.VIDEO_TOKEN, "video-token")
                data.putString(PoTokenIpc.VISITOR_DATA, request.data.getString(PoTokenIpc.VISITOR_DATA))
                data.putLong(PoTokenIpc.EXPIRES_AT, expiresAtMs)
            }
            request.replyTo.send(Message.obtain(null, request.what, request.arg1, 0).apply { this.data = data })
        }

        override fun getApplicationContext(): Context = this

        override fun bindService(
            intent: Intent,
            connection: ServiceConnection,
            flags: Int,
        ): Boolean {
            assertFalse(connects)
            connects = true
            binds++
            actions += intent.action
            assertEquals(component, intent.component)
            this.connection = connection
            if (connectImmediately) connection.onServiceConnected(component, service.binder)
            return true
        }

        override fun unbindService(connection: ServiceConnection) {
            assertTrue(connects)
            assertEquals(this.connection, connection)
            connects = false
            unbinds++
        }
    }
}
