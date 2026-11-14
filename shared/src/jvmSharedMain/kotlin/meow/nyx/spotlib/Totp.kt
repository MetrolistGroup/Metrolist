package meow.nyx.spotlib

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object Totp {
    private const val SECRET = ",7/*F(\"rLJ2oxaKL^f+E1xvP@N"
    private const val VERSION = 61
    private const val PERIOD_SECONDS = 30L

    fun version(): Int = VERSION

    fun code(nowEpochMillis: Long = System.currentTimeMillis()): String {
        val bytes = SECRET.mapIndexed { index, character -> character.code xor (index % 33 + 9) }
        val raw = bytes.joinToString("").toByteArray(Charsets.UTF_8)
        val key = base32Decode(base32Encode(raw))
        return hotp(key, nowEpochMillis / 1000 / PERIOD_SECONDS)
    }

    private fun hotp(
        key: ByteArray,
        counter: Long,
    ): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val data = ByteArray(8)
        for (index in 0 until 8) data[7 - index] = ((counter shr (8 * index)) and 0xFF).toByte()
        val digest = mac.doFinal(data)
        val offset = digest[digest.size - 1].toInt() and 0x0F
        val binary =
            ((digest[offset].toInt() and 0x7F) shl 24) or
                ((digest[offset + 1].toInt() and 0xFF) shl 16) or
                ((digest[offset + 2].toInt() and 0xFF) shl 8) or
                (digest[offset + 3].toInt() and 0xFF)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun base32Encode(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val output = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                output.append(alphabet[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) output.append(alphabet[(buffer shl (5 - bits)) and 0x1F])
        return output.toString()
    }

    private fun base32Decode(base32: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val output = ArrayList<Byte>()
        for (character in base32) {
            val value = alphabet.indexOf(character)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                output.add(((buffer shr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
        }
        return output.toByteArray()
    }
}
