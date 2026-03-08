package com.metrolist.shared.deviceconnect

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceConnectPlaybackChunksTest {
    @Test
    fun chunksRoundTripAtomicallyAndFitEncryptedWireLimit() {
        val bytes = ByteArray(DEVICE_CONNECT_MAX_PLAYBACK_BYTES) { (it % 251).toByte() }
        val assembler = DeviceConnectPlaybackAssembler()
        val key = DeviceCrypto.secureRandomBytes(32)
        val chunks = chunkDeviceConnectPlayback(bytes).toList()
        chunks.forEachIndexed { index, chunk ->
            val seq = index + 1L
            val encrypted =
                DeviceCrypto.aesGcmEncrypt(
                    key,
                    DcJson.encodeToString(chunk).encodeToByteArray(),
                    deviceConnectAad(DcMessageTypes.PLAYBACK, seq),
                )
            val wire = DcWire(DcMessageTypes.PLAYBACK, encrypted.toBase64(), DEVICE_CONNECT_PROTOCOL_VERSION, true, seq)
            assertTrue(DcJson.encodeToString(wire).encodeToByteArray().size <= DEVICE_CONNECT_MAX_FRAME_BYTES)
            val result = assembler.accept(chunk, index.toLong())
            if (index == chunks.lastIndex) assertContentEquals(bytes, result) else assertNull(result)
        }
        assertFalse(assembler.pending)
        assertFailsWith<IllegalArgumentException> { chunkDeviceConnectPlayback(ByteArray(DEVICE_CONNECT_MAX_PLAYBACK_BYTES + 1)) }
    }

    @Test
    fun invalidOrExpiredAssembliesAreDiscarded() {
        val chunks = chunkDeviceConnectPlayback(ByteArray(DEVICE_CONNECT_PLAYBACK_CHUNK_BYTES + 1)).toList()
        val malformed =
            listOf(
                chunks.first().copy(totalBytes = DEVICE_CONNECT_MAX_PLAYBACK_BYTES + 1),
                chunks.first().copy(totalBytes = 0),
                chunks.first().copy(count = Int.MAX_VALUE),
                chunks.first().copy(index = -1),
                chunks.first().copy(messageId = "invalid"),
                chunks.first().copy(data = "a"),
                chunks.last(),
            )
        malformed.forEach { chunk ->
            val assembler = DeviceConnectPlaybackAssembler()
            assertFailsWith<IllegalArgumentException> { assembler.accept(chunk, 0) }
            assertFalse(assembler.pending)
        }
        val invalidContinuations =
            listOf(
                chunks.first(),
                chunks.last().copy(messageId = DeviceCrypto.secureRandomBytes(16).toBase64()),
                chunks.last().copy(totalBytes = chunks.last().totalBytes + 1, data = ByteArray(2).toBase64()),
            )
        invalidContinuations.forEach { chunk ->
            val assembler = DeviceConnectPlaybackAssembler()
            assertNull(assembler.accept(chunks.first(), 0))
            assertFailsWith<IllegalArgumentException> { assembler.accept(chunk, 1) }
            assertFalse(assembler.pending)
        }
        val assembler = DeviceConnectPlaybackAssembler()
        assertNull(assembler.accept(chunks.first(), 0))
        assertFailsWith<IllegalArgumentException> { assembler.accept(chunks.last(), DEVICE_CONNECT_PLAYBACK_TIMEOUT_MS) }
        assertFalse(assembler.pending)
    }
}
