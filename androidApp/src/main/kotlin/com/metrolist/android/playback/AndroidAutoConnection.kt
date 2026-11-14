package com.metrolist.android.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import timber.log.Timber

// Public CarConnection provider contract: https://developer.android.com/reference/androidx/car/app/connection/CarConnection
internal const val ACTION_CAR_CONNECTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
internal const val CAR_CONNECTION_STATE = "CarConnectionState"
internal const val CAR_CONNECTION_AUTHORITY = "androidx.car.app.connection"
internal const val CAR_CONNECTION_NOT_CONNECTED = 0
internal const val CAR_CONNECTION_NATIVE = 1
internal const val CAR_CONNECTION_PROJECTION = 2

internal class AndroidAutoDisconnectState {
    private var previousType: Int? = null

    fun onConnectionTypeChanged(
        type: Int?,
        isRemotePlayback: Boolean,
    ): Boolean {
        if (type !in CAR_CONNECTION_NOT_CONNECTED..CAR_CONNECTION_PROJECTION) return false
        val disconnected = previousType == CAR_CONNECTION_PROJECTION && type == CAR_CONNECTION_NOT_CONNECTED
        previousType = type
        return disconnected && !isRemotePlayback
    }
}

internal fun androidAutoConnectionTypes(context: Context): Flow<Int?> =
    flow {
        // Service teardown can remove its receivers before coroutine cleanup resumes.
        val applicationContext = context.applicationContext
        val updates = Channel<Unit>(Channel.CONFLATED)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (intent.action == ACTION_CAR_CONNECTION_UPDATED) updates.trySend(Unit)
                }
            }
        var registered = false
        try {
            try {
                ContextCompat.registerReceiver(
                    applicationContext,
                    receiver,
                    IntentFilter(ACTION_CAR_CONNECTION_UPDATED),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                registered = true
            } catch (error: RuntimeException) {
                Timber.tag("AndroidAutoConnection").w(error, "Car connection receiver registration failed")
                return@flow
            }
            updates.trySend(Unit)
            for (update in updates) {
                val type = withContext(Dispatchers.IO) { readCarConnectionType(applicationContext) }
                Timber.tag("AndroidAutoConnection").d("Car connection type=%s", type)
                emit(type)
            }
        } finally {
            updates.close()
            if (registered) {
                try {
                    applicationContext.unregisterReceiver(receiver)
                } catch (error: IllegalArgumentException) {
                    Timber.tag("AndroidAutoConnection").w(error, "Car connection receiver already unregistered")
                }
            }
        }
    }

private fun readCarConnectionType(context: Context): Int? =
    try {
        context.contentResolver
            .query("content://$CAR_CONNECTION_AUTHORITY".toUri(), arrayOf(CAR_CONNECTION_STATE), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(CAR_CONNECTION_STATE)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getInt(column) else null
            }
    } catch (error: RuntimeException) {
        Timber.tag("AndroidAutoConnection").w(error, "Car connection query failed")
        null
    }
