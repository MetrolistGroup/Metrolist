package com.metrolist.shared.deviceconnect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceConnectPlaybackOutboxTest {
    @Test
    fun cancelledSocketSendDisconnectsThePeerInsteadOfSilentlyKillingTheWorker() =
        runTest {
            var disconnected = false
            val outbox =
                DeviceConnectPlaybackOutbox(
                    this,
                    { throw CancellationException("socket closed") },
                    { disconnected = true },
                )
            try {
                assertTrue(outbox.offer(DcSyncMessage(DcSyncMessageType.STATE)))
                runCurrent()
                assertTrue(disconnected)
                assertFalse(outbox.offer(DcSyncMessage(DcSyncMessageType.STATE, revision = 1)))
            } finally {
                outbox.close()
            }
        }

    @Test
    fun blockedPeerDoesNotBlockOtherPeersAndTimesOut() =
        runTest {
            var disconnected = false
            val blocked = CompletableDeferred<Unit>()
            val slow =
                DeviceConnectPlaybackOutbox(this, {
                    blocked.await()
                    true
                }, { disconnected = true }, timeoutMs = 100)
            val sent = mutableListOf<DcSyncMessage>()
            val fast =
                DeviceConnectPlaybackOutbox(this, {
                    sent += it
                    true
                }, {})
            try {
                val message = DcSyncMessage(DcSyncMessageType.STATE)
                assertTrue(slow.offer(message))
                assertTrue(fast.offer(message))
                runCurrent()
                assertEquals(listOf(message), sent)
                assertFalse(disconnected)
                advanceTimeBy(101)
                runCurrent()
                assertTrue(disconnected)
            } finally {
                slow.close()
                fast.close()
            }
        }

    @Test
    fun queueOverflowDisconnectsWithoutDroppingCommandsSilently() =
        runTest {
            var disconnected = false
            val gate = CompletableDeferred<Unit>()
            val outbox =
                DeviceConnectPlaybackOutbox(this, {
                    gate.await()
                    true
                }, { disconnected = true })
            try {
                repeat(16) { assertTrue(outbox.offer(DcSyncMessage(DcSyncMessageType.COMMAND, revision = it.toLong()))) }
                assertFalse(outbox.offer(DcSyncMessage(DcSyncMessageType.COMMAND, revision = 17)))
                assertTrue(disconnected)
            } finally {
                outbox.close()
            }
        }

    @Test
    fun conflatesOnlyAdjacentProgressAndPreservesSeekAndStateOrdering() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val sent = mutableListOf<DcSyncMessage>()
            val outbox =
                DeviceConnectPlaybackOutbox(this, {
                    gate.await()
                    sent += it
                    true
                }, {})
            try {
                val state = DcSyncMessage(DcSyncMessageType.STATE)
                outbox.offer(state)
                runCurrent()
                outbox.offer(DcSyncMessage(DcSyncMessageType.UPDATE, positionMs = 1))
                outbox.offer(DcSyncMessage(DcSyncMessageType.UPDATE, positionMs = 2))
                outbox.offer(DcSyncMessage(DcSyncMessageType.UPDATE, positionMs = 3, seek = true))
                outbox.offer(DcSyncMessage(DcSyncMessageType.UPDATE, positionMs = 4))
                outbox.offer(DcSyncMessage(DcSyncMessageType.UPDATE, positionMs = 5, volume = 0.3f))
                outbox.offer(DcSyncMessage(DcSyncMessageType.UPDATE, positionMs = 6, volume = 0.3f, pitch = 0.8f))
                outbox.offer(DcSyncMessage(DcSyncMessageType.STATE, revision = 2))
                gate.complete(Unit)
                runCurrent()
                assertEquals(listOf(0L, 2L, 3L, 4L, 5L, 6L, 0L), sent.map { it.positionMs })
                assertEquals(2L, sent.last().revision)
            } finally {
                outbox.close()
            }
        }
}
