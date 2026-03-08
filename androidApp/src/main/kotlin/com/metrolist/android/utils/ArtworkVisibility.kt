package com.metrolist.android.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

fun artworkUiVisible(context: Context): Flow<Boolean> {
    val application = context.applicationContext
    val interactive =
        callbackFlow {
            val powerManager = requireNotNull(application.getSystemService(PowerManager::class.java))
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        trySend(powerManager.isInteractive)
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
            ContextCompat.registerReceiver(application, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            trySend(powerManager.isInteractive)
            awaitClose { application.unregisterReceiver(receiver) }
        }
    return combine(ProcessLifecycleOwner.get().lifecycle.currentStateFlow, interactive) { state, screenOn ->
        state.isAtLeast(Lifecycle.State.STARTED) && screenOn
    }.distinctUntilChanged()
}
