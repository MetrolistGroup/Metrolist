package com.metrolist.android.listentogether

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.metrolist.android.MainActivity
import com.metrolist.android.R
import com.metrolist.shared.listentogether.ListenTogetherApprovalNotification
import com.metrolist.shared.listentogether.ListenTogetherEvent
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.listentogether.ListenTogetherSuggestion
import com.metrolist.shared.listentogether.listenTogetherApprovalNotifications
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal class ListenTogetherNotifications(
    context: Context,
    private val manager: ListenTogetherManager,
    scope: CoroutineScope,
) {
    private val application = context.applicationContext as Application
    val screenVisible = MutableStateFlow(false)
    private val notifications = NotificationManagerCompat.from(application)
    private val alerts = ApprovalNotificationAlerts()
    private var posted = emptyMap<String, ListenTogetherApprovalNotification>()

    init {
        notifications.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(application.getString(R.string.listen_together))
                .build(),
        )
        notifications.activeNotifications.filter { it.tag?.startsWith("metrolist-approval://") == true }.forEach {
            notifications.cancel(it.tag, it.id)
        }
        scope.launch {
            snapshotFlow { Settings.shared.listenTogetherEnabled }.collect { enabled ->
                if (!enabled) {
                    notifications.cancel(AUTO_APPROVED_TAG, 0)
                    notifications.cancel(SUMMARY_TAG, 0)
                    posted.keys.forEach { notifications.cancel(it, 0) }
                }
            }
        }
        scope.launch {
            manager.events.collect { event ->
                if (event is ListenTogetherEvent.SuggestionAutoApproved && !screenVisible.value) {
                    postAutoApproved(event.suggestion)
                }
            }
        }
        scope.launch {
            combine(
                combine(manager.role, manager.isInRoom) { role, inRoom -> if (inRoom) role else ListenTogetherRoomRole.NONE },
                manager.roomState,
                manager.pendingJoinRequests,
                manager.pendingSuggestions,
                screenVisible,
            ) { role, room, joins, suggestions, visible ->
                listenTogetherApprovalNotifications(role, room?.roomCode, false, joins, suggestions) to visible
            }.collect { (requests, visible) ->
                val pending = requests.associateBy { it.notificationUri().toString() }
                val alert = alerts.shouldAlert(pending.keys, visible, SystemClock.elapsedRealtime())
                val current = if (visible) emptyMap() else pending
                (posted.keys - current.keys).forEach { notifications.cancel(it, 0) }
                val delivered = current.filter { (tag, request) -> posted[tag] == request || post(tag, request) }
                if (delivered.isEmpty()) {
                    notifications.cancel(SUMMARY_TAG, 0)
                } else if (delivered != posted) {
                    val summary =
                        NotificationCompat
                            .Builder(application, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle(application.getString(R.string.listen_together))
                            .setContentText(application.getString(R.string.listen_together_pending_approvals))
                            .setNumber(delivered.size)
                            .setGroup(GROUP_KEY)
                            .setGroupSummary(true)
                            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                            .setSilent(!alert)
                            .setContentIntent(
                                PendingIntent.getActivity(
                                    application,
                                    0,
                                    Intent(application, MainActivity::class.java).setAction(MainActivity.ACTION_LISTEN_TOGETHER),
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                                ),
                            ).build()
                    try {
                        notifications.notify(SUMMARY_TAG, 0, summary)
                    } catch (_: SecurityException) {
                    }
                }
                posted = delivered
            }
        }
    }

    fun respond(intent: Intent) {
        if (!Settings.shared.listenTogetherEnabled) return
        if (intent.action != ACTION_APPROVE && intent.action != ACTION_REJECT) return
        val request =
            listenTogetherApprovalNotifications(
                manager.role.value,
                manager.roomState.value
                    ?.roomCode
                    .takeIf { manager.isInRoom.value },
                screenVisible = false,
                manager.pendingJoinRequests.value,
                manager.pendingSuggestions.value,
            ).firstOrNull { it.notificationUri() == intent.data } ?: return
        val approve = intent.action == ACTION_APPROVE
        if (request.isSuggestion) {
            if (approve) manager.approveSuggestion(request.requestId) else manager.rejectSuggestion(request.requestId)
        } else {
            if (approve) manager.approveJoin(request.requestId) else manager.rejectJoin(request.requestId)
        }
        notifications.cancel(request.notificationUri().toString(), 0)
    }

    private fun postAutoApproved(suggestion: ListenTogetherSuggestion) {
        if (!Settings.shared.listenTogetherEnabled) return
        val text = listOf(suggestion.fromUsername, suggestion.trackInfo.title).joinToString("\n")
        val notification =
            NotificationCompat
                .Builder(application, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setSubText(application.getString(R.string.listen_together))
                .setContentTitle(application.getString(R.string.listen_together_suggestion_auto_approved))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(
                    PendingIntent.getActivity(
                        application,
                        0,
                        Intent(application, MainActivity::class.java).setAction(MainActivity.ACTION_LISTEN_TOGETHER),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
        try {
            notifications.notify(AUTO_APPROVED_TAG, 0, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun post(
        tag: String,
        request: ListenTogetherApprovalNotification,
    ): Boolean {
        if (!Settings.shared.listenTogetherEnabled) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(application, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val text = listOfNotNull(request.username, request.trackTitle).joinToString("\n")
        val notification =
            NotificationCompat
                .Builder(application, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setSubText(application.getString(R.string.listen_together))
                .setContentTitle(
                    application.getString(
                        if (request.isSuggestion) R.string.listen_together_pending_suggestions else R.string.listen_together_pending_users,
                    ),
                ).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(
                    PendingIntent.getActivity(
                        application,
                        0,
                        Intent(application, MainActivity::class.java).setAction(MainActivity.ACTION_LISTEN_TOGETHER),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setGroup(GROUP_KEY)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .addAction(0, application.getString(R.string.approve), action(request, ACTION_APPROVE))
                .addAction(0, application.getString(R.string.reject), action(request, ACTION_REJECT))
                .build()
        return try {
            notifications.notify(tag, 0, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun action(
        request: ListenTogetherApprovalNotification,
        action: String,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            application,
            0,
            Intent(application, ListenTogetherNotificationReceiver::class.java).setAction(action).setData(request.notificationUri()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun ListenTogetherApprovalNotification.notificationUri(): Uri =
        Uri
            .Builder()
            .scheme("metrolist-approval")
            .authority(roomCode)
            .appendPath(if (isSuggestion) "suggestion" else "join")
            .appendPath(requestId)
            .build()

    companion object {
        private const val AUTO_APPROVED_TAG = "listen_together_auto_approved"
        private const val GROUP_KEY = "listen_together_approvals"
        private const val SUMMARY_TAG = "metrolist-approval://summary"
        private const val CHANNEL_ID = "listen_together_approvals"
        private const val ACTION_APPROVE = "com.metrolist.android.action.APPROVE_LISTEN_TOGETHER"
        private const val ACTION_REJECT = "com.metrolist.android.action.REJECT_LISTEN_TOGETHER"
    }
}

class ListenTogetherNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        AndroidListenTogetherManager.respondToNotification(intent)
        intent.dataString?.let { NotificationManagerCompat.from(context).cancel(it, 0) }
    }
}
