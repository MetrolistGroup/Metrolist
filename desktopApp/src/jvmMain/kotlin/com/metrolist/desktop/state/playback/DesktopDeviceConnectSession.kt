package com.metrolist.desktop.state.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.metrolist.shared.deviceconnect.DcLocalPlaybackState
import com.metrolist.shared.deviceconnect.DcPlaybackAction
import com.metrolist.shared.deviceconnect.DcPlaybackCommand
import com.metrolist.shared.deviceconnect.DcPlaybackSnapshot
import com.metrolist.shared.deviceconnect.DcSyncError
import com.metrolist.shared.deviceconnect.DeviceConnectRemote
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.QueueRemoval
import com.metrolist.shared.settings.Settings
import java.lang.ref.WeakReference

internal fun isConnectedQueueRemoval(
    removal: QueueRemoval,
    origins: List<WeakReference<QueueRemoval>>,
): Boolean = origins.any { it.get() === removal }

internal fun canRestoreConnectedQueue(
    expectedSession: String,
    activeSession: String?,
    expectedQueue: MusicQueueSnapshot,
    actualQueue: MusicQueueSnapshot,
): Boolean = expectedSession == activeSession && expectedQueue == actualQueue

internal class DesktopDeviceConnectSession(
    private val queue: MusicQueue,
    private val coordinator: () -> DeviceConnectRemote,
) {
    var sessionId: String? = null
        private set
    var trackRevision = -1L
        private set
    var desiredPlaying = false
        private set
    var baseSpeed = Settings.shared.playbackSpeed
        private set
    var ready = false
        private set
    var audioOutputEnabled = true
        private set
    var title: String? = null
        private set
    var finite = false
        private set

    private data class QueueUndo(
        val removal: QueueRemoval,
        val original: MusicQueueSnapshot,
        val removed: MusicQueueSnapshot,
        val sessionId: String,
        val revision: Long,
    )

    private var undo: QueueUndo? = null

    // UI undo can outlive the session; weak identity keeps old remote removals from becoming local undos.
    private val connectedRemovals = mutableListOf<WeakReference<QueueRemoval>>()

    fun acknowledge(snapshot: DcPlaybackSnapshot) {
        sessionId = snapshot.sessionId
        trackRevision = snapshot.trackRevision
        baseSpeed = snapshot.playback.speed
    }

    fun apply(snapshot: DcPlaybackSnapshot) {
        acknowledge(snapshot)
        applyPlayback(snapshot.playback)
    }

    fun applyPlayback(playback: DcLocalPlaybackState) {
        desiredPlaying = playback.playing
        title = playback.title
        finite = playback.finite
        baseSpeed = playback.speed
    }

    fun resetQueueMetadata() {
        title = null
        finite = false
        undo = null
    }

    fun disconnected() {
        sessionId = null
        trackRevision = -1L
        undo = null
    }

    fun setPlaying(playing: Boolean) {
        desiredPlaying = playing
    }

    fun setReady(value: Boolean) {
        ready = value
    }

    fun setTitle(value: String?) {
        title = value
    }

    fun setAudioOutputEnabled(value: Boolean) {
        audioOutputEnabled = value
    }

    fun remove(
        indices: Set<Int>,
        expectedQueue: MusicQueueSnapshot,
    ): QueueRemoval? {
        val sync = coordinator()
        val activeSession = sync.state.value.sessionId ?: return null
        val revision = sync.queueRevision ?: return null
        val local = MusicQueue().apply { restore(expectedQueue) }
        val removal = local.removeAt(indices) ?: return null
        undo = QueueUndo(removal, expectedQueue, local.snapshot(), activeSession, revision + 1L)
        connectedRemovals.removeAll { it.get() == null }
        connectedRemovals += WeakReference(removal)
        sync.intercept(
            DcPlaybackCommand(DcPlaybackAction.REMOVE, indices = indices),
            expectedQueueRevision = revision,
            expectedQueue = expectedQueue,
        )
        return removal
    }

    fun restore(removal: QueueRemoval): Boolean {
        val sync = coordinator()
        val pending = undo
        if (!sync.isActive && !isConnectedQueueRemoval(removal, connectedRemovals)) return false
        if (pending == null || pending.removal !== removal ||
            !canRestoreConnectedQueue(pending.sessionId, sync.state.value.sessionId, pending.removed, queue.snapshot())
        ) {
            sync.reportError(DcSyncError.STALE_QUEUE)
            return true
        }
        undo = null
        sync.intercept(
            DcPlaybackCommand(
                DcPlaybackAction.RESTORE_QUEUE,
                queue = pending.original,
                indices = removal.items.mapTo(mutableSetOf()) { it.index },
            ),
            expectedQueueRevision = pending.revision,
            expectedQueue = pending.removed,
        )
        return true
    }
}
