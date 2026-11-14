package com.metrolist.android.playback

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class AndroidAutoConnectionTest {
    @Test
    fun onlyProjectionDisconnectPausesAndEachConnectionCanPauseOnce() {
        val state = AndroidAutoDisconnectState()
        assertFalse(state.onConnectionTypeChanged(CAR_CONNECTION_NOT_CONNECTED, false))
        assertFalse(state.onConnectionTypeChanged(CAR_CONNECTION_NATIVE, false))
        assertFalse(state.onConnectionTypeChanged(CAR_CONNECTION_NOT_CONNECTED, false))
        repeat(2) {
            assertFalse(state.onConnectionTypeChanged(CAR_CONNECTION_PROJECTION, false))
            assertFalse(state.onConnectionTypeChanged(CAR_CONNECTION_PROJECTION, false))
            assertTrue(state.onConnectionTypeChanged(CAR_CONNECTION_NOT_CONNECTED, false))
            assertFalse(state.onConnectionTypeChanged(CAR_CONNECTION_NOT_CONNECTED, false))
        }
    }

    @Test
    fun unknownOrFailedQueriesDoNotInventOrForgetAConnection() {
        val state = AndroidAutoDisconnectState()
        assertFalse(state.onConnectionTypeChanged(null, false))
        assertFalse(state.onConnectionTypeChanged(CAR_CONNECTION_NOT_CONNECTED, false))
        assertFalse(state.onConnectionTypeChanged(CAR_CONNECTION_PROJECTION, false))
        assertFalse(state.onConnectionTypeChanged(null, false))
        assertFalse(state.onConnectionTypeChanged(-1, false))
        assertFalse(state.onConnectionTypeChanged(3, false))
        assertTrue(state.onConnectionTypeChanged(CAR_CONNECTION_NOT_CONNECTED, false))
    }

    @Test
    fun remotePlaybackDisconnectIsConsumedWithoutPausingLaterLocalPlayback() {
        val state = AndroidAutoDisconnectState()
        assertFalse(state.onConnectionTypeChanged(CAR_CONNECTION_PROJECTION, true))
        assertFalse(state.onConnectionTypeChanged(CAR_CONNECTION_NOT_CONNECTED, true))
        assertFalse(state.onConnectionTypeChanged(CAR_CONNECTION_NOT_CONNECTED, false))
        assertFalse(state.onConnectionTypeChanged(CAR_CONNECTION_PROJECTION, false))
        assertTrue(state.onConnectionTypeChanged(CAR_CONNECTION_NOT_CONNECTED, false))
    }

    @Test
    fun disconnectInvalidatesBothResolvingAndResolvedAutomotiveRequests() {
        val gate = AutomotivePlaybackRequestGate()
        val items = listOf(MediaItem.Builder().setMediaId("track").build())
        val resolving = gate.start(false)
        gate.cancelPendingRequests()
        assertThrows(CancellationException::class.java) {
            gate.resolve(resolving, items, 0, 0)
        }
        val resolved = gate.resolve(gate.start(false), items, 0, 0)
        gate.cancelPendingRequests()
        assertEquals(AutomotiveMediaItemsApplication.Reject, gate.consume(resolved.mediaItems, 0, 0))
        val next = gate.resolve(gate.start(false), items, 0, 0)
        assertTrue(gate.consume(next.mediaItems, 0, 0) is AutomotiveMediaItemsApplication.Apply)
    }

    @Test
    fun observesProviderWithoutNoisyBroadcastAndUnregistersOnCancellation() =
        runBlocking {
            val application = RuntimeEnvironment.getApplication()
            val context = ReceiverContext(application)
            val serviceContext =
                object : ContextWrapper(application) {
                    override fun getApplicationContext(): Context = context
                }
            val provider = Robolectric.buildContentProvider(CarConnectionProvider::class.java).create(CAR_CONNECTION_AUTHORITY).get()
            val states = Channel<Int?>(Channel.UNLIMITED)
            val receiverCount = shadowOf(application).registeredReceivers.size
            val observer = launch { androidAutoConnectionTypes(serviceContext).collect { states.send(it) } }
            try {
                assertEquals(CAR_CONNECTION_PROJECTION, withTimeout(5_000) { states.receive() })
                assertEquals(1, context.registrations)
                assertEquals(receiverCount + 1, shadowOf(application).registeredReceivers.size)
                provider.type = CAR_CONNECTION_NOT_CONNECTED
                context.sendBroadcast(Intent(ACTION_CAR_CONNECTION_UPDATED))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(CAR_CONNECTION_NOT_CONNECTED, withTimeout(5_000) { states.receive() })
                provider.failQuery = true
                context.sendBroadcast(Intent(ACTION_CAR_CONNECTION_UPDATED))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(null, withTimeout(5_000) { states.receive() })
            } finally {
                observer.cancelAndJoin()
            }
            observer.cancelAndJoin()
            assertEquals(1, context.unregistrations)
            assertEquals(receiverCount, shadowOf(application).registeredReceivers.size)
        }

    @Test
    fun registrationFailureCompletesWithoutUnregisteringOrEmittingAConnection() =
        runBlocking {
            val context = ReceiverContext(RuntimeEnvironment.getApplication())
            context.registrationFailure = SecurityException("Registration denied")

            assertTrue(androidAutoConnectionTypes(context).toList().isEmpty())
            assertEquals(1, context.registrations)
            assertEquals(0, context.unregistrations)
        }

    @Test
    fun cancellationDuringRegistrationStillUnregistersExactlyOnce() =
        runBlocking {
            val context = ReceiverContext(RuntimeEnvironment.getApplication())
            val observer =
                launch {
                    context.onRegistered = { cancel() }
                    androidAutoConnectionTypes(context).collect {}
                }

            observer.join()
            observer.cancelAndJoin()
            assertEquals(1, context.registrations)
            assertEquals(1, context.unregistrations)
        }

    @Test
    fun receiverAlreadyRemovedDoesNotCrashCancellation() =
        runBlocking {
            val application = RuntimeEnvironment.getApplication()
            val context = ReceiverContext(application)
            val states = Channel<Int?>(Channel.UNLIMITED)
            val observer = launch { androidAutoConnectionTypes(context).collect { states.send(it) } }
            try {
                withTimeout(5_000) { states.receive() }
                application.unregisterReceiver(context.receiver)
            } finally {
                observer.cancelAndJoin()
            }
            assertEquals(1, context.registrations)
            assertEquals(1, context.unregistrations)
        }

    private class ReceiverContext(
        base: Context,
    ) : ContextWrapper(base) {
        var registrations = 0
        var unregistrations = 0
        var registrationFailure: RuntimeException? = null
        var onRegistered: () -> Unit = {}
        var receiver: BroadcastReceiver? = null

        override fun getApplicationContext(): Context = this

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter,
            broadcastPermission: String?,
            scheduler: Handler?,
            flags: Int,
        ): Intent? {
            registrations++
            registrationFailure?.let { throw it }
            this.receiver = receiver
            return super.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags).also { onRegistered() }
        }

        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            unregistrations++
            super.unregisterReceiver(receiver)
        }
    }

    class CarConnectionProvider : ContentProvider() {
        var type = CAR_CONNECTION_PROJECTION
        var failQuery = false

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            check(!failQuery)
            return MatrixCursor(arrayOf(CAR_CONNECTION_STATE)).apply { addRow(arrayOf(type)) }
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}
