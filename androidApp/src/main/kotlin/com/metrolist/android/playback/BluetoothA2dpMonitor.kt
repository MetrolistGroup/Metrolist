package com.metrolist.android.playback

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

internal class BluetoothA2dpMonitor(
    private val context: Context,
    private val onChange: () -> Unit,
) {
    private var registered = false
    private var address: String? = null
    private var playingState: Boolean? = null

    // A missing broadcast or address is unknown, even when permission was granted.
    val playing: Boolean? get() = playingState.takeIf { registered && hasPermission() }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (!registered || intent.action != BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED || !hasPermission()) return
                val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                if (address == null || !address.equals(device.address, ignoreCase = true)) return
                playingState =
                    when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothA2dp.STATE_PLAYING -> true
                        BluetoothA2dp.STATE_NOT_PLAYING -> false
                        else -> return
                    }
                onChange()
            }
        }

    init {
        // AudioDeviceInfo exposes the routed address only on API 28+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasPermission()) {
            try {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                registered = true
            } catch (_: SecurityException) {
                registered = false
            }
        }
    }

    fun setRoute(a2dpAddress: String?) {
        val next = a2dpAddress?.takeIf { it.isNotBlank() }
        if (address.equals(next, ignoreCase = true)) return
        address = next
        playingState = null
    }

    fun close() {
        if (registered) context.unregisterReceiver(receiver)
        registered = false
        playingState = null
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH,
        ) == PackageManager.PERMISSION_GRANTED
}
