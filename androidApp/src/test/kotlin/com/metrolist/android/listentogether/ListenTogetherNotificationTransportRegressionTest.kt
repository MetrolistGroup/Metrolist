package com.metrolist.android.listentogether

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import com.metrolist.android.R
import com.metrolist.shared.listentogether.ListenTogetherJoinRequest
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.listentogether.ListenTogetherPlayback
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.listentogether.PlaybackReadiness
import com.metrolist.shared.listentogether.network.RoomState
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = ListenTogetherNotificationTestApplication::class, sdk = [32])
class ListenTogetherNotificationTransportRegressionTest {
    private lateinit var application: Application
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        notificationManager = application.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
        Settings.replaceSharedForTest(
            Settings(MapSettings()).apply {
                listenTogetherEnabled = true
                listenTogetherAutoApproveJoinRequests = false
                listenTogetherUsername = "Host"
            },
        )
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
        setAndroidNotifications(null)
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun disconnectedDecisionKeepsPendingRequestVisibleUntilItIsResolved() =
        runTest(UnconfinedTestDispatcher()) {
            val manager = manager(this)
            val notificationJob = SupervisorJob()
            try {
                seedPendingJoin(manager)
                val notifications = ListenTogetherNotifications(application, manager, CoroutineScope(coroutineContext + notificationJob))
                setAndroidNotifications(notifications)
                runCurrent()
                assertTrue(activeTags().contains(TAG))

                ListenTogetherNotificationReceiver().onReceive(application, decisionIntent())
                runCurrent()

                assertTrue(manager.pendingJoinRequests.value.any { it.userId == GUEST_ID })
                assertTrue(activeTags().contains(TAG))

                pendingJoins(manager).value = emptyList()
                runCurrent()
                assertFalse(activeTags().contains(TAG))
            } finally {
                notificationJob.cancel()
                manager.close()
            }
        }

    private fun manager(scope: CoroutineScope) =
        ListenTogetherManager(
            callerScope = scope,
            httpClient = HttpClient(MockEngine { error("Unexpected HTTP request") }),
            playback = FakePlayback(),
            resolver = { null },
            monotonicClockMs = { 0L },
            wallClockMs = { 0L },
            timestampFormatter = { "" },
            sessionPersistenceEnabled = false,
        )

    private fun seedPendingJoin(manager: ListenTogetherManager) {
        stateFlow<ListenTogetherRoomRole>(manager, "_role").value = ListenTogetherRoomRole.HOST
        stateFlow<Boolean>(manager, "_isInRoom").value = true
        stateFlow<RoomState?>(manager, "_roomState").value =
            RoomState(
                roomCode = ROOM_CODE,
                hostId = "host",
                users = emptyList(),
                isPlaying = false,
                position = 0L,
                lastUpdate = 0L,
            )
        pendingJoins(manager).value = listOf(ListenTogetherJoinRequest(GUEST_ID, "Guest"))
    }

    private fun pendingJoins(manager: ListenTogetherManager) = stateFlow<List<ListenTogetherJoinRequest>>(manager, "_pendingJoinRequests")

    @Suppress("UNCHECKED_CAST")
    private fun <T> stateFlow(
        manager: ListenTogetherManager,
        name: String,
    ): MutableStateFlow<T> =
        ListenTogetherManager::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(manager) as MutableStateFlow<T>

    private fun setAndroidNotifications(value: ListenTogetherNotifications?) {
        AndroidListenTogetherManager::class.java
            .getDeclaredField("notifications")
            .apply { isAccessible = true }
            .set(AndroidListenTogetherManager, value)
    }

    private fun activeTags() = notificationManager.activeNotifications.mapNotNull { it.tag }.toSet()

    private fun decisionIntent() =
        Intent(application, ListenTogetherNotificationReceiver::class.java)
            .setAction(ACTION_APPROVE)
            .setData(Uri.parse(TAG))

    private class FakePlayback : ListenTogetherPlayback {
        override val connected = MutableStateFlow(false)
        override val currentSong = MutableStateFlow<SongItem?>(null)
        override val playing = MutableStateFlow(false)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val volume = MutableStateFlow(1f)
        override val queue = MutableStateFlow<List<SongItem>>(emptyList())
        override val readiness: StateFlow<PlaybackReadiness> = MutableStateFlow(PlaybackReadiness.IDLE)

        override fun currentQueueTitle(): String? = null

        override fun currentQueueIndex(): Int? = null

        override fun playbackSpeed(): Float = 1f

        override suspend fun playQueueFromRemote(
            songs: List<SongItem>,
            startIndex: Int,
            title: String?,
        ) = Unit

        override fun setQueueTitleFromRemote(title: String?) = Unit

        override suspend fun replaceUpcomingQueueFromRemote(songs: List<SongItem>) = Unit

        override suspend fun addToQueueFromRemote(song: SongItem) = Unit

        override suspend fun skipToNextFromRemote() = Unit

        override suspend fun skipToPreviousFromRemote() = Unit

        override suspend fun setPlayingFromRemote(playing: Boolean) = Unit

        override suspend fun seekToFromRemote(positionMs: Long) = Unit

        override suspend fun setPlaybackSpeedFromRemote(speed: Float) = Unit

        override suspend fun setVolumeFromRemote(volume: Float) = Unit
    }

    private companion object {
        const val ROOM_CODE = "ROOM1234"
        const val GUEST_ID = "guest"
        const val TAG = "metrolist-approval://$ROOM_CODE/join/$GUEST_ID"
        const val ACTION_APPROVE = "com.metrolist.android.action.APPROVE_LISTEN_TOGETHER"
    }
}

class ListenTogetherNotificationTestApplication : Application() {
    private val testResources by lazy {
        object : Resources(super.getAssets(), super.getResources().displayMetrics, super.getResources().configuration) {
            override fun getString(id: Int): String = "resource-$id"
        }
    }

    override fun getResources(): Resources = testResources
}
