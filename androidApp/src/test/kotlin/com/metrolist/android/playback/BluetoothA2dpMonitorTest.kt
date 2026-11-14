package com.metrolist.android.playback

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class BluetoothA2dpMonitorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val activeAddress = "00:11:22:33:44:55"

    @Test
    fun deniedPermissionSkipsSignalWithoutRegisteringOrRequestingPermission() {
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val receivers = shadowOf(context).registeredReceivers.size
        val monitor = BluetoothA2dpMonitor(context) { error("Permission is unavailable") }
        monitor.setRoute(activeAddress)
        assertNull(monitor.playing)
        assertEquals(receivers, shadowOf(context).registeredReceivers.size)
        monitor.close()
    }

    @Test
    fun onlyTheRoutedDeviceChangesStateAndReleaseRemovesTheReceiver() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        var changes = 0
        val monitor = BluetoothA2dpMonitor(context) { changes++ }
        monitor.setRoute(activeAddress)
        assertNull(monitor.playing)
        broadcast("00:11:22:33:44:66", BluetoothA2dp.STATE_NOT_PLAYING)
        assertEquals(0, changes)
        broadcast(activeAddress, BluetoothA2dp.STATE_PLAYING)
        assertEquals(true, monitor.playing)
        broadcast(activeAddress, BluetoothA2dp.STATE_NOT_PLAYING)
        assertEquals(false, monitor.playing)
        broadcast(activeAddress, -1)
        assertEquals(2, changes)
        monitor.setRoute(null)
        assertNull(monitor.playing)
        broadcast(activeAddress, BluetoothA2dp.STATE_NOT_PLAYING)
        assertEquals(2, changes)
        monitor.setRoute(activeAddress)
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        broadcast(activeAddress, BluetoothA2dp.STATE_NOT_PLAYING)
        assertNull(monitor.playing)
        monitor.close()
        monitor.close()
        broadcast(activeAddress, BluetoothA2dp.STATE_PLAYING)
        assertEquals(2, changes)
    }

    @Test
    fun receiverRegistrationDenialLeavesSignalUnavailable() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val denied =
            object : ContextWrapper(context) {
                override fun registerReceiver(
                    receiver: BroadcastReceiver?,
                    filter: IntentFilter,
                    broadcastPermission: String?,
                    scheduler: Handler?,
                    flags: Int,
                ): Intent? = throw SecurityException("denied")
            }
        val monitor = BluetoothA2dpMonitor(denied) { error("Registration was denied") }
        assertNull(monitor.playing)
        monitor.close()
    }

    @Suppress("DEPRECATION")
    private fun broadcast(
        address: String,
        state: Int,
    ) {
        context.sendBroadcast(
            Intent(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address))
                .putExtra(BluetoothProfile.EXTRA_STATE, state),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }
}
