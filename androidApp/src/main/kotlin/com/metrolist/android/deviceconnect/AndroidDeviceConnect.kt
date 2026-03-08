/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.deviceconnect

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.metrolist.android.listentogether.AndroidListenTogetherManager
import com.metrolist.shared.deviceconnect.DcPlaybackAction
import com.metrolist.shared.deviceconnect.DcPlaybackCommand
import com.metrolist.shared.deviceconnect.DcPlaybackSnapshot
import com.metrolist.shared.deviceconnect.DcRepeatMode
import com.metrolist.shared.deviceconnect.DeviceConnectDeviceType
import com.metrolist.shared.deviceconnect.DeviceConnectManager
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueSnapshot

object AndroidDeviceConnect {
    val manager = DeviceConnectManager(localDeviceType = DeviceConnectDeviceType.ANDROID_PHONE)

    fun intercept(
        command: DcPlaybackCommand,
        expectedQueue: MusicQueueSnapshot? = null,
    ): Boolean =
        manager.remote.intercept(command, expectedQueue = expectedQueue) ||
            (AndroidListenTogetherManager.isGuestPlaybackLocked && command.action != DcPlaybackAction.VOLUME)

    internal fun requiredLocalNetworkPermission(): String? = deviceConnectLocalNetworkPermission(Build.VERSION.SDK_INT)

    internal fun canUseLocalNetwork(context: Context): Boolean =
        requiredLocalNetworkPermission()?.let { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } ?: true
}

@SuppressLint("InlinedApi")
internal fun deviceConnectLocalNetworkPermission(sdkInt: Int): String? =
    if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN) Manifest.permission.ACCESS_LOCAL_NETWORK else null

internal fun Int.toDeviceConnectRepeatMode(): DcRepeatMode =
    when (this) {
        Player.REPEAT_MODE_ONE -> DcRepeatMode.ONE
        Player.REPEAT_MODE_ALL -> DcRepeatMode.ALL
        else -> DcRepeatMode.OFF
    }

internal fun DcRepeatMode.toPlayerRepeatMode(): Int =
    when (this) {
        DcRepeatMode.ONE -> Player.REPEAT_MODE_ONE
        DcRepeatMode.ALL -> Player.REPEAT_MODE_ALL
        DcRepeatMode.OFF -> Player.REPEAT_MODE_OFF
    }

internal fun requiresDeviceConnectTrackReload(
    currentSongId: String?,
    currentResourceId: String?,
    previousQueue: MusicQueueSnapshot,
    snapshot: DcPlaybackSnapshot,
    appliedSessionId: String?,
    appliedTrackRevision: Long?,
    targetLoaded: Boolean = true,
): Boolean {
    val targetId =
        snapshot.playback.queue.displayedItems
            .getOrNull(snapshot.playback.queue.currentIndex)
            ?.id
    return !targetLoaded || currentResourceId == null || currentResourceId != targetId || currentSongId != targetId ||
        (appliedSessionId == snapshot.sessionId && appliedTrackRevision != snapshot.trackRevision) ||
        (
            appliedSessionId == null && previousQueue.displayedItems == snapshot.playback.queue.displayedItems &&
                previousQueue.currentIndex != snapshot.playback.queue.currentIndex
        )
}

internal fun canUseDeviceConnectTrack(
    song: SongItem,
    isFollowing: Boolean,
    requiresReload: Boolean,
): Boolean = !(song.isLocal || song.id.startsWith("local_")) || (!isFollowing && !requiresReload)
