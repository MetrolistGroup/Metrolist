package com.metrolist.shared.deviceconnect

import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class DeviceConnectPlaybackOutbox(
    scope: CoroutineScope,
    private val send: suspend (DcSyncMessage) -> Boolean,
    private val disconnect: () -> Unit,
    private val timeoutMs: Long = 5_000L,
) {
    private val mutex = Mutex()
    private val pending = ArrayDeque<DcSyncMessage>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val worker: Job =
        scope.launch {
            for (signal in wake) {
                while (true) {
                    val message = mutex.withLock { pending.removeFirstOrNull() } ?: break
                    val delivered =
                        try {
                            withTimeoutOrNull(timeoutMs) { send(message) } == true
                        } catch (cancelled: CancellationException) {
                            if (!worker.isActive) throw cancelled
                            false
                        } catch (_: Exception) {
                            false
                        }
                    if (!delivered) {
                        PlaybackEventLog.error(
                            PlaybackEventKind.SYNC,
                            "DeviceConnect",
                            "Playback message delivery failed",
                            IllegalStateException("Peer delivery failed or timed out"),
                        )
                        disconnect()
                        return@launch
                    }
                }
            }
        }

    suspend fun offer(message: DcSyncMessage): Boolean {
        val accepted =
            mutex.withLock {
                if (!worker.isActive) return@withLock false
                val last = pending.lastOrNull()
                if (last != null && message.type == DcSyncMessageType.UPDATE && last.type == message.type &&
                    !message.seek && !last.seek && message.sessionId == last.sessionId &&
                    message.revision == last.revision && message.queueRevision == last.queueRevision &&
                    message.trackRevision == last.trackRevision && message.index == last.index &&
                    message.playing == last.playing && message.ready == last.ready && message.speed == last.speed &&
                    message.pitch == last.pitch &&
                    message.volume == last.volume && message.repeatMode == last.repeatMode && message.queueStateId == last.queueStateId
                ) {
                    pending.removeLast()
                } else if (pending.size >= 16) {
                    return@withLock false
                }
                pending.addLast(message)
                true
            }
        if (accepted) wake.trySend(Unit) else disconnect()
        return accepted
    }

    fun close() {
        wake.close()
        worker.cancel()
    }
}
