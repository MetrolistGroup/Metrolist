/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Transport abstraction and length-prefixed stream framing for DeviceConnect
 * off-router fallback transports (such as Bluetooth RFCOMM).
 */

package com.metrolist.shared.deviceconnect

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DeviceTransportKind {
    LAN_WEBSOCKET,
    BLUETOOTH_RFCOMM,
}

interface LocalDeviceTransport {
    val kind: DeviceTransportKind
    val localAddress: String?

    fun startServer(
        localInfo: DeviceInfoPayload,
        onChannel: suspend (DeviceChannel) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean

    fun stopServer()

    fun startDiscovery(
        localInfo: DeviceInfoPayload,
        onFound: (DiscoveredDevice) -> Unit,
        onLost: (DiscoveredDevice) -> Unit,
    )

    suspend fun stopDiscovery()

    suspend fun connect(
        host: String,
        port: Int,
    ): DeviceChannel

    suspend fun close()
}

internal class LengthPrefixedStreamDeviceChannel(
    override val remoteHost: String?,
    private val readBytes: suspend (Int) -> ByteArray?,
    private val writeBytes: suspend (ByteArray) -> Unit,
    private val closeUnderlying: suspend () -> Unit,
) : DeviceChannel {
    private val sendMutex = Mutex()
    private val closed = CompletableDeferred<Unit>()

    override val incoming: Flow<String> =
        flow {
            try {
                while (!closed.isCompleted) {
                    val header = readBytes(FRAME_HEADER_BYTES) ?: break
                    if (header.size != FRAME_HEADER_BYTES) {
                        throw IllegalStateException("Truncated DeviceConnect stream frame header")
                    }
                    val length =
                        ((header[0].toInt() and 0xff) shl 24) or
                            ((header[1].toInt() and 0xff) shl 16) or
                            ((header[2].toInt() and 0xff) shl 8) or
                            (header[3].toInt() and 0xff)
                    require(length in 1..DEVICE_CONNECT_MAX_FRAME_BYTES.toInt()) {
                        "Invalid DeviceConnect stream frame size: $length"
                    }
                    val payload =
                        readBytes(length)
                            ?: throw IllegalStateException("Truncated DeviceConnect stream frame body")
                    if (payload.size != length) {
                        throw IllegalStateException("Incomplete DeviceConnect stream frame body")
                    }
                    emit(payload.decodeToString(throwOnInvalidSequence = true))
                }
            } finally {
                withContext(NonCancellable) {
                    close()
                }
            }
        }

    override suspend fun send(text: String) {
        val payload = text.encodeToByteArray()
        require(payload.size in 1..DEVICE_CONNECT_MAX_FRAME_BYTES.toInt()) {
            "DeviceConnect stream frame exceeds limit: ${payload.size}"
        }
        check(!closed.isCompleted) { "DeviceConnect stream channel is closed" }
        val frame = ByteArray(FRAME_HEADER_BYTES + payload.size)
        val length = payload.size
        frame[0] = (length ushr 24).toByte()
        frame[1] = (length ushr 16).toByte()
        frame[2] = (length ushr 8).toByte()
        frame[3] = length.toByte()
        payload.copyInto(frame, destinationOffset = FRAME_HEADER_BYTES)
        sendMutex.withLock {
            check(!closed.isCompleted) { "DeviceConnect stream channel is closed" }
            writeBytes(frame)
        }
    }

    override suspend fun close() {
        if (!closed.complete(Unit)) return
        runCatching { closeUnderlying() }
    }

    private companion object {
        const val FRAME_HEADER_BYTES = 4
    }
}

internal expect fun createBluetoothFallbackTransport(
    context: Any?,
    onError: (message: String, throwable: Throwable?) -> Unit,
): LocalDeviceTransport?
