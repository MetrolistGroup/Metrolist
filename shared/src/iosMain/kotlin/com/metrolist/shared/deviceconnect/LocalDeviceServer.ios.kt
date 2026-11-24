@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.metrolist.shared.deviceconnect

import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_copy_endpoint
import platform.Network.nw_connection_receive_message
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_t
import platform.Network.nw_content_context_create
import platform.Network.nw_endpoint_get_hostname
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
import platform.Network.nw_protocol_stack_prepend_application_protocol
import platform.Network.nw_ws_create_metadata
import platform.Network.nw_ws_create_options
import platform.Network.nw_ws_metadata_get_opcode
import platform.Network.nw_ws_opcode_text
import platform.Network.nw_ws_options_set_auto_reply_ping
import platform.Network.nw_ws_options_set_client_request_handler
import platform.Network.nw_ws_options_set_maximum_message_size
import platform.Network.nw_ws_response_create
import platform.Network.nw_ws_response_status_accept
import platform.Network.nw_ws_version_13
import platform.Network.nw_protocol_copy_ws_definition
import platform.Network.nw_content_context_set_metadata_for_protocol
import platform.Network.nw_content_context_copy_protocol_metadata
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_get_size
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_t
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.posix.uint8_tVar

actual class LocalDeviceServer {
    private val lock = object {}
    private var listener: nw_listener_t? = null
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
        val queue = dispatch_get_main_queue()
        val scope =
            CoroutineScope(
                SupervisorJob() +
                    Dispatchers.Default +
                    CoroutineExceptionHandler { _, error ->
                        if (generation == currentGeneration) onFailure(error)
                    },
            )
        val params =
            nw_parameters_create_secure_tcp(
                { _ -> },
                { tcpOptions ->
                    val ws = nw_ws_create_options(nw_ws_version_13)
                    nw_ws_options_set_auto_reply_ping(ws, true)
                    nw_ws_options_set_maximum_message_size(ws, DEVICE_CONNECT_MAX_FRAME_BYTES.toULong())
                    nw_ws_options_set_client_request_handler(ws, queue) { _ ->
                        nw_ws_response_create(nw_ws_response_status_accept, null)
                    }
                    nw_protocol_stack_prepend_application_protocol(
                        nw_parameters_copy_default_protocol_stack(tcpOptions),
                        ws,
                    )
                },
            )
        val createdListener =
            nw_listener_create_with_port(port.coerceIn(0, 65535).toString(), params)
                ?: run {
                    scope.cancel()
                    return 0
                }

        nw_listener_set_queue(createdListener, queue)
        nw_listener_set_state_changed_handler(createdListener) { state, _ ->
            if (state == nw_listener_state_failed && generation == currentGeneration) {
                onFailure(IllegalStateException("iOS Device Connect listener failed"))
            }
        }
        nw_listener_set_new_connection_handler(createdListener) { connection ->
            if (connection == null || generation != currentGeneration) return@nw_listener_set_new_connection_handler
            val channel = IosDeviceChannel(connection, queue)
            if (!isDeviceConnectAllowedClientAddress(channel.remoteHost)) {
                scope.launch { channel.close() }
                return@nw_listener_set_new_connection_handler
            }
            scope.launch {
                runCatching { onChannel(channel) }.onFailure { error ->
                    if (generation == currentGeneration) onFailure(error)
                }
            }
        }

        nw_listener_start(createdListener)
        val actualPort = nw_listener_get_port(createdListener).toInt()
        synchronized(lock) {
            if (generation != currentGeneration) {
                nw_listener_cancel(createdListener)
                scope.cancel()
                return 0
            }
            listener = createdListener
            serverScope = scope
        }
        return actualPort.takeIf { it in 1..65535 } ?: 0
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
    private val queue: dispatch_queue_t,
) : DeviceChannel {
    override val remoteHost: String? =
        nw_connection_copy_endpoint(connection)
            ?.let { nw_endpoint_get_hostname(it)?.toKString() }
            ?.substringBefore('%')

    @Volatile
    private var started = false

    override val incoming: Flow<String> =
        callbackFlow {
            ensureStarted()
            receiveLoop(
                onText = { trySend(it).isSuccess },
                onClosed = { close() },
            )
            awaitClose { nw_connection_cancel(connection) }
        }

    override suspend fun send(text: String) {
        ensureStarted()
        val bytes = text.encodeToByteArray()
        val data = bytes.toDispatchData()
        val context = nw_content_context_create("dc")
        val metadata = nw_ws_create_metadata(nw_ws_opcode_text)
        nw_content_context_set_metadata_for_protocol(context, metadata)
        nw_connection_send(connection, data, context, true) { _ -> }
    }

    override suspend fun close() {
        nw_connection_cancel(connection)
    }

    private fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            nw_connection_set_queue(connection, queue)
            nw_connection_set_state_changed_handler(connection) { state, _ ->
                if (state == nw_connection_state_failed || state == nw_connection_state_cancelled) {
                    nw_connection_cancel(connection)
                }
            }
            nw_connection_start(connection)
        }
    }

    private fun receiveLoop(
        onText: (String) -> Unit,
        onClosed: () -> Unit,
    ) {
        nw_connection_receive_message(connection) { data, context, _, error ->
            if (error != null) {
                onClosed()
                return@nw_connection_receive_message
            }
            if (data != null) {
                val protocolMetadata =
                    nw_content_context_copy_protocol_metadata(context, nw_protocol_copy_ws_definition())
                val opcode = protocolMetadata?.let { nw_ws_metadata_get_opcode(it).toInt() }
                if (opcode == nw_ws_opcode_text.toInt()) {
                    data.toByteArray().decodeToString().let(onText)
                }
            }
            receiveLoop(onText, onClosed)
        }
    }
}

private fun ByteArray.toDispatchData(): dispatch_data_t? {
    if (isEmpty()) {
        return dispatch_data_create(null, 0u, dispatch_get_main_queue(), null)
    }
    val buffer = nativeHeap.allocArray<uint8_tVar>(size)
    for (i in indices) buffer[i] = this[i].toUByte()
    return dispatch_data_create(
        buffer,
        size.toULong(),
        dispatch_get_main_queue(),
    ) { nativeHeap.free(buffer) }
}

private fun dispatch_data_t?.toByteArray(): ByteArray {
    val dispatchData = this ?: return ByteArray(0)
    val size = dispatch_data_get_size(dispatchData).toInt()
    if (size <= 0) return ByteArray(0)
    val out = ByteArray(size)
    var offset = 0
    dispatch_data_apply(dispatchData) { _, _, fragment, fragmentSize ->
        val bytes = fragment?.reinterpret<uint8_tVar>() ?: return@dispatch_data_apply true
        val count = fragmentSize.toInt()
        for (i in 0 until count) {
            if (offset >= out.size) return@dispatch_data_apply false
            out[offset++] = bytes[i].toByte()
        }
        true
    }
    return if (offset == out.size) out else out.copyOf(offset)
}
