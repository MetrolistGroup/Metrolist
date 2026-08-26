/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.metrolist.music.constants.AppLanguageKey
import com.metrolist.music.constants.SYSTEM_DEFAULT
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.listentogether.ListenTogetherManager
import com.metrolist.music.playback.DownloadUtil
import com.metrolist.music.playback.MusicService
import com.metrolist.music.playback.MusicService.MusicBinder
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.ui.WearApp
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.setAppLocale
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class WearMainActivity : ComponentActivity() {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var listenTogetherManager: ListenTogetherManager

    private var playerConnection: PlayerConnection? = null
    private var playerConnectionSnapshot by mutableStateOf<PlayerConnection?>(null)
    private var isServiceBound = false

    private val quitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MusicService.ACTION_QUIT) {
                finish()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service is MusicBinder) {
                playerConnection = PlayerConnection(this@WearMainActivity, service, database, lifecycleScope)
                playerConnectionSnapshot = playerConnection
                listenTogetherManager.setPlayerConnection(playerConnection)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            listenTogetherManager.setPlayerConnection(null)
            playerConnection?.dispose()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(quitReceiver, IntentFilter(MusicService.ACTION_QUIT), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(quitReceiver, IntentFilter(MusicService.ACTION_QUIT))
        }

        lifecycleScope.launch {
            dataStore.data
                .map { it[AppLanguageKey] ?: SYSTEM_DEFAULT }
                .distinctUntilChanged()
                .collectLatest { appLanguage ->
                    val locale = appLanguage.takeUnless { it == SYSTEM_DEFAULT }?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
                    setAppLocale(this@WearMainActivity, locale)
                }
        }

        // Initialize Listen Together manager
        listenTogetherManager.initialize()

        setContent {
            CompositionLocalProvider(
                LocalDatabase provides database,
                LocalPlayerConnection provides playerConnectionSnapshot,
                LocalDownloadUtil provides downloadUtil,
                LocalSyncUtils provides syncUtils,
                LocalListenTogetherManager provides listenTogetherManager
            ) {
                WearApp()
            }
        }
    }

    @SuppressLint("NewApi")
    override fun onStart() {
        super.onStart()

        // Start the playback service explicitly once so it can outlive binding.
        if (!MusicService.isRunning) {
            val serviceIntent = Intent(this, MusicService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ContextCompat.startForegroundService(this, serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    Timber.tag("WearMainActivity").w(e, "Cannot start foreground service from background")
                } else {
                    Timber.tag("WearMainActivity").w(e, "Failed to start service")
                }
            }
        }

        // Bind to service
        if (!isServiceBound) {
            bindService(
                Intent(this, MusicService::class.java),
                serviceConnection,
                BIND_AUTO_CREATE
            )
            isServiceBound = true
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            listenTogetherManager.disconnect()
        }
        try {
            unregisterReceiver(quitReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()

        playerConnection?.dispose()
        playerConnection = null
        playerConnectionSnapshot = null

        safeUnbindService()
    }

    private fun safeUnbindService() {
        if (!isServiceBound) return
        try {
            unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Timber.tag("WearMainActivity").w(e, "Service was not bound when attempting to unbind")
        } finally {
            isServiceBound = false
            listenTogetherManager.setPlayerConnection(null)
            playerConnection?.dispose()
        }
    }
}
