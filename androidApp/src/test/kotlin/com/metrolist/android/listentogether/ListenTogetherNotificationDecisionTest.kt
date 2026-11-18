package com.metrolist.android.listentogether

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.metrolist.android.R
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [32])
class ListenTogetherNotificationDecisionTest {
    private lateinit var application: Application
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        notificationManager = application.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
        Settings.replaceSharedForTest(Settings(MapSettings()).apply { listenTogetherEnabled = true })
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun coldReceiverKeepsApprovalWhenManagerCannotHandleIt() {
        notificationManager.notify(TAG, 0, notification())
        assertTrue(activeTags().contains(TAG))

        ListenTogetherNotificationReceiver().onReceive(application, decisionIntent(TAG))

        assertTrue(activeTags().contains(TAG))
    }

    @Test
    fun unrelatedDecisionDoesNotRemoveAnotherApproval() {
        notificationManager.notify(TAG, 0, notification())

        ListenTogetherNotificationReceiver().onReceive(application, decisionIntent("metrolist-approval://ROOM1234/join/other"))

        assertTrue(activeTags().contains(TAG))
    }

    private fun notification() =
        NotificationCompat
            .Builder(application, "listen_together_approvals")
            .setSmallIcon(R.drawable.ic_notification)
            .build()

    private fun activeTags() = notificationManager.activeNotifications.mapNotNull { it.tag }.toSet()

    private fun decisionIntent(tag: String) =
        Intent(application, ListenTogetherNotificationReceiver::class.java)
            .setAction("com.metrolist.android.action.APPROVE_LISTEN_TOGETHER")
            .setData(Uri.parse(tag))

    private companion object {
        const val TAG = "metrolist-approval://ROOM1234/join/guest"
    }
}
