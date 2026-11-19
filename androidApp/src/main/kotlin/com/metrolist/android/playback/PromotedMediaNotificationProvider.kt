/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.playback

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.metrolist.android.R
import kotlin.math.abs

@UnstableApi
internal class PromotedMediaNotificationProvider(
    private val context: Context,
    private val delegate: DefaultMediaNotificationProvider = DefaultMediaNotificationProvider(context),
) : MediaNotification.Provider {
    // Session preferences in these slots remove the standard skip actions used by watches and lock screens.
    private fun transportButtons(): List<CommandButton> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            listOf(
                CommandButton
                    .Builder(CommandButton.ICON_PREVIOUS)
                    .setCustomIconResId(R.drawable.ic_notification_skip_previous)
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .setDisplayName(context.getString(R.string.previous))
                    .setSlots(CommandButton.SLOT_BACK)
                    .build(),
                CommandButton
                    .Builder(CommandButton.ICON_NEXT)
                    .setCustomIconResId(R.drawable.ic_notification_skip_next)
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                    .setDisplayName(context.getString(R.string.next))
                    .setSlots(CommandButton.SLOT_FORWARD)
                    .build(),
            )
        } else {
            emptyList()
        }

    private var cachedKey: List<Any?>? = null
    private var cachedNotification: MediaNotification? = null
    internal val latestNotification: MediaNotification? get() = cachedNotification
    private var cachedStartTimeMs: Long? = null
    private var generation = 0L
    private var latestCallback: MediaNotification.Provider.Callback? = null

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val player = mediaSession.player
        val chronometer =
            player.isPlaying && !player.isPlayingAd && !player.isCurrentMediaItemDynamic && player.playbackParameters.speed == 1f
        val startTimeMs = if (chronometer) System.currentTimeMillis() - player.contentPosition else null
        val key =
            listOf(
                mediaSession,
                mediaButtonPreferences,
                actionFactory,
                mediaSession.bitmapLoader,
                mediaSession.sessionActivity,
                player.mediaMetadata,
                player.availableCommands,
                Util.shouldShowPlayButton(player, mediaSession.showPlayButtonIfPlaybackIsSuppressed),
                player.isPlaying,
                chronometer,
            )
        latestCallback = onNotificationChangedCallback
        if (cachedKey == key && notificationClockUnchanged(cachedStartTimeMs, startTimeMs)) {
            cachedNotification?.let { return it }
        }
        cachedKey = key
        cachedStartTimeMs = startTimeMs
        val requestGeneration = ++generation
        val notificationButtons = transportButtons().filter { player.isCommandAvailable(it.playerCommand) } + mediaButtonPreferences
        return delegate
            .createNotification(mediaSession, ImmutableList.copyOf(notificationButtons), actionFactory) { updated ->
                if (generation == requestGeneration) {
                    val promoted = updated.promoteIfPlaying(mediaSession)
                    cachedNotification = promoted
                    latestCallback?.onNotificationChanged(promoted)
                }
            }.promoteIfPlaying(mediaSession)
            .also { cachedNotification = it }
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo = delegate.notificationChannelInfo

    private fun MediaNotification.promoteIfPlaying(mediaSession: MediaSession): MediaNotification {
        if (!mediaSession.player.isPlaying) return this
        // Now-playing integrations such as Diffuse only track media notifications marked ongoing.
        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT
        if (Build.VERSION.SDK_INT >= 36) notification.extras.putBoolean(NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING, true)
        return this
    }
}

internal fun notificationClockUnchanged(
    previousStartMs: Long?,
    nextStartMs: Long?,
): Boolean =
    if (previousStartMs == null || nextStartMs == null) {
        previousStartMs == nextStartMs
    } else {
        abs(previousStartMs - nextStartMs) < 1_000L
    }
