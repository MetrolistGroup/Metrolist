package com.metrolist.android.widget

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal fun widgetScreenInteractive(context: Context): Flow<Boolean> =
    callbackFlow {
        val power = requireNotNull(context.getSystemService(PowerManager::class.java))
        val keyguard = requireNotNull(context.getSystemService(KeyguardManager::class.java))

        fun interactive() = power.isInteractive && !keyguard.isKeyguardLocked
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    trySend(interactive())
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(interactive())
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()
