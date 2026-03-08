package com.metrolist.shared.youtube.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.potoken.PoTokenException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** All state is confined to Dispatchers.Main by AndroidTokenProvider. */
internal class RemotePoTokenMinter(
    context: Context,
) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var connection: Connection? = null
    private var pending: CompletableDeferred<Bundle>? = null
    private var requestId = 0
    private val idleUnbind = Runnable { close() }
    private val replies =
        Messenger(
            Handler(Looper.getMainLooper()) { message ->
                if (message.what == PoTokenIpc.MINT && message.arg1 == requestId) {
                    pending?.complete(message.data)
                }
                true
            },
        )

    suspend fun mint(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): MintedPoToken {
        handler.removeCallbacks(idleUnbind)
        var succeeded = false
        try {
            val minted =
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    val active = connect()
                    val service = active.ready.await()
                    val response = CompletableDeferred<Bundle>()
                    pending = response
                    service.send(
                        Message.obtain(null, PoTokenIpc.MINT, ++requestId, 0).apply {
                            replyTo = replies
                            data =
                                Bundle().apply {
                                    putString(PoTokenIpc.VIDEO_ID, videoId)
                                    putString(PoTokenIpc.VISITOR_DATA, visitorData)
                                    putString(PoTokenIpc.COOKIE, cookie)
                                }
                        },
                    )
                    val data = response.await()
                    MintedPoToken(
                        result =
                            PoTokenResult(
                                playerRequestToken = requireToken(data, PoTokenIpc.PLAYER_TOKEN),
                                streamingDataToken = requireToken(data, PoTokenIpc.VIDEO_TOKEN),
                                visitorData = requireToken(data, PoTokenIpc.VISITOR_DATA),
                            ),
                        expiresAtMs = data.getLong(PoTokenIpc.EXPIRES_AT),
                    )
                } ?: throw PoTokenException("PoToken service timed out")
            handler.postDelayed(idleUnbind, minOf(IDLE_TIMEOUT_MS, (minted.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0)))
            succeeded = true
            return minted
        } finally {
            pending = null
            if (!succeeded) close()
        }
    }

    fun prewarm() {
        if (connection != null) return
        connect()
        handler.removeCallbacks(idleUnbind)
        handler.postDelayed(idleUnbind, IDLE_TIMEOUT_MS)
    }

    private fun connect(): Connection =
        connection ?: Connection().also {
            connection = it
            val intent = Intent(context, PoTokenService::class.java).setAction(UUID.randomUUID().toString())
            it.bound = true
            if (!context.bindService(intent, it, Context.BIND_AUTO_CREATE)) {
                close()
                throw PoTokenException("PoToken service binding failed")
            }
        }

    private fun requireToken(
        data: Bundle,
        key: String,
    ): String = data.getString(key)?.takeIf { it.isNotBlank() } ?: throw PoTokenException("PoToken mint failed")

    fun close() {
        handler.removeCallbacks(idleUnbind)
        val old = connection
        connection = null
        val error = PoTokenException("PoToken service disconnected")
        pending?.completeExceptionally(error)
        old?.ready?.completeExceptionally(error)
        old?.binder?.let { runCatching { it.unlinkToDeath(old, 0) } }
        if (old?.bound == true) {
            old.bound = false
            try {
                context.unbindService(old)
            } catch (_: IllegalArgumentException) {
                // bindService can fail before Android registers the connection.
            }
        }
    }

    private inner class Connection :
        ServiceConnection,
        IBinder.DeathRecipient {
        val ready = CompletableDeferred<Messenger>()
        var bound = false
        var binder: IBinder? = null

        override fun onServiceConnected(
            name: ComponentName,
            service: IBinder,
        ) {
            if (connection !== this) return
            try {
                service.linkToDeath(this, 0)
                binder = service
                ready.complete(Messenger(service))
            } catch (_: Exception) {
                disconnected()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = disconnected()

        override fun onBindingDied(name: ComponentName) = disconnected()

        override fun onNullBinding(name: ComponentName) = disconnected()

        override fun binderDied() {
            handler.post { disconnected() }
        }

        private fun disconnected() {
            if (connection === this) close()
        }
    }

    companion object {
        // Includes process startup, the 17s attestation timeout and two 5s token requests.
        const val REQUEST_TIMEOUT_MS = 35_000L

        const val IDLE_TIMEOUT_MS = 6L * 60_000L
    }
}

internal data class MintedPoToken(
    val result: PoTokenResult,
    val expiresAtMs: Long,
)
