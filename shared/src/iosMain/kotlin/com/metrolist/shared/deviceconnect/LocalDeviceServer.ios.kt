@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.deviceconnect

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Network.NW_PARAMETERS_DEFAULT_CONFIGURATION
import platform.Network.NW_PARAMETERS_DISABLE_PROTOCOL
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_copy_endpoint
import platform.Network.nw_connection_receive_message
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_start
import platform.Network.nw_connection_t
import platform.Network.nw_content_context_copy_protocol_metadata
import platform.Network.nw_content_context_create
import platform.Network.nw_content_context_get_is_final
import platform.Network.nw_content_context_set_metadata_for_protocol
import platform.Network.nw_endpoint_copy_address_string
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create_with_port
import platform.Network.nw_listener_get_port
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_listener_state_failed
import platform.Network.nw_listener_state_ready
import platform.Network.nw_listener_t
import platform.Network.nw_parameters_copy_default_protocol_stack
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_protocol_copy_ws_definition
import platform.Network.nw_protocol_stack_prepend_application_protocol
import platform.Network.nw_ws_create_metadata
import platform.Network.nw_ws_create_options
import platform.Network.nw_ws_metadata_get_opcode
import platform.Network.nw_ws_opcode_close
import platform.Network.nw_ws_opcode_text
import platform.Network.nw_ws_options_set_auto_reply_ping
import platform.Network.nw_ws_options_set_maximum_message_size
import platform.Network.nw_ws_version_13
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_get_size
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.posix.free
import platform.posix.memcpy
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual class LocalDeviceServer {
    private val lock = SynchronizedObject()
    private var listener: nw_listener_t = null
    private var serverScope: CoroutineScope? = null

    @Volatile
    private var generation = 0L

    actual fun start(
        port: Int,
        onFailure: (Throwable) -> Unit,
        onChannel: suspend (DeviceChannel) -> Unit,
    ): Int {
        stop()
        val currentGeneration = synchronized(lock) { ++generation }
        val queue = dispatch_queue_create("com.metrolist.deviceconnect.server", null)
        val readySignal = dispatch_semaphore_create(0L)
        val scope =
            CoroutineScope(
                SupervisorJob() +
                    Dispatchers.Default +
                    CoroutineExceptionHandler { _, error ->
                        if (generation == currentGeneration) onFailure(error)
                    },
            )
        val params = nw_parameters_create_secure_tcp(NW_PARAMETERS_DISABLE_PROTOCOL, NW_PARAMETERS_DEFAULT_CONFIGURATION)
        val ws = nw_ws_create_options(nw_ws_version_13)
        nw_ws_options_set_auto_reply_ping(ws, true)
        nw_ws_options_set_maximum_message_size(ws, DEVICE_CONNECT_MAX_FRAME_BYTES.toULong())
        nw_protocol_stack_prepend_application_protocol(nw_parameters_copy_default_protocol_stack(params), ws)
        val createdListener =
            nw_listener_create_with_port(port.toString(), params) ?: run {
                scope.cancel()
                return 0
            }

        nw_listener_set_queue(createdListener, queue)
        nw_listener_set_state_changed_handler(createdListener) { state, _ ->
            if (state == nw_listener_state_ready || state == nw_listener_state_failed) {
                dispatch_semaphore_signal(readySignal)
            }
            if (state == nw_listener_state_failed && generation == currentGeneration) {
                onFailure(IllegalStateException("iOS Device Connect listener failed"))
            }
        }
        nw_listener_set_new_connection_handler(createdListener) { connection ->
            if (connection == null) return@nw_listener_set_new_connection_handler
            val channel = IosDeviceChannel(connection)
            if (generation != currentGeneration || !isDeviceConnectAllowedClientAddress(channel.remoteHost)) {
                nw_connection_cancel(connection)
                return@nw_listener_set_new_connection_handler
            }
            nw_connection_set_queue(connection, queue)
            nw_connection_start(connection)
            // Like the Ktor route, the socket closes once the manager's handler returns.
            scope.launch {
                try {
                    onChannel(channel)
                } finally {
                    channel.close()
                }
            }
        }

        nw_listener_start(createdListener)
        val ready = dispatch_semaphore_wait(readySignal, dispatch_time(DISPATCH_TIME_NOW, 5_000_000_000L)) == 0L
        val actualPort = if (ready) nw_listener_get_port(createdListener).toInt() else 0
        synchronized(lock) {
            if (actualPort != 0 && generation == currentGeneration) {
                listener = createdListener
                serverScope = scope
                return actualPort
            }
        }
        nw_listener_cancel(createdListener)
        scope.cancel()
        return 0
    }

    actual fun stop() {
        val (stoppingListener, stoppingScope) =
            synchronized(lock) {
                generation++
                (listener to serverScope).also {
                    listener = null
                    serverScope = null
                }
            }
        stoppingListener?.let { nw_listener_cancel(it) }
        stoppingScope?.cancel()
    }
}

private class IosDeviceChannel(
    private val connection: nw_connection_t,
) : DeviceChannel {
    override val remoteHost: String? =
        nw_endpoint_copy_address_string(nw_connection_copy_endpoint(connection))?.let { address ->
            try {
                address.toKString().substringBefore('%')
            } finally {
                free(address)
            }
        }

    override val incoming: Flow<String> =
        callbackFlow {
            receiveLoop(
                // The receive callback cannot suspend, so a peer that outruns the buffer is disconnected.
                onText = { text ->
                    trySend(text).isSuccess.also { if (!it) close(IllegalStateException("Device Connect incoming buffer overflow")) }
                },
                onClosed = { close() },
            )
            awaitClose { nw_connection_cancel(connection) }
        }

    override suspend fun send(text: String) {
        val context = nw_content_context_create("dc")
        nw_content_context_set_metadata_for_protocol(context, nw_ws_create_metadata(nw_ws_opcode_text))
        suspendCancellableCoroutine<Unit> { continuation ->
            nw_connection_send(connection, text.encodeToByteArray().toDispatchData(), context, true) { error ->
                if (!continuation.isActive) return@nw_connection_send
                if (error == null) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(IllegalStateException("Device Connect send failed"))
                }
            }
        }
    }

    override suspend fun close() {
        nw_connection_cancel(connection)
    }

    private fun receiveLoop(
        onText: (String) -> Boolean,
        onClosed: () -> Unit,
    ) {
        nw_connection_receive_message(connection) { data, context, isComplete, error ->
            if (error != null) return@nw_connection_receive_message onClosed()
            // A TCP EOF without a close frame arrives with no context.
            if (context == null) return@nw_connection_receive_message onClosed()
            val metadata = nw_content_context_copy_protocol_metadata(context, nw_protocol_copy_ws_definition())
            when (metadata?.let { nw_ws_metadata_get_opcode(it) }) {
                nw_ws_opcode_close -> return@nw_connection_receive_message onClosed()
                nw_ws_opcode_text -> if (!onText(data.toByteArray().decodeToString())) return@nw_connection_receive_message
            }
            if (isComplete && nw_content_context_get_is_final(context)) {
                return@nw_connection_receive_message onClosed()
            }
            receiveLoop(onText, onClosed)
        }
    }
}

// A null destructor makes dispatch copy the bytes before usePinned releases them.
private fun ByteArray.toDispatchData(): dispatch_data_t =
    if (isEmpty()) {
        dispatch_data_create(null, 0u, null, null)
    } else {
        usePinned { dispatch_data_create(it.addressOf(0), size.toULong(), null, null) }
    }

private fun dispatch_data_t.toByteArray(): ByteArray {
    val out = ByteArray(this?.let { dispatch_data_get_size(it).toInt() } ?: 0)
    if (out.isEmpty()) return out
    out.usePinned { pinned ->
        dispatch_data_apply(this) { _, offset, buffer, size ->
            memcpy(pinned.addressOf(offset.toInt()), buffer, size)
            true
        }
    }
    return out
}
