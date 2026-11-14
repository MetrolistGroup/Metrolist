package com.metrolist.shared.backup

internal fun decodeLegacyPreferences(bytes: ByteArray): Map<String, Any> = LegacyPreferencesProto(bytes).read()

private class LegacyPreferencesProto(
    private val bytes: ByteArray,
) {
    private var offset = 0

    fun read(): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        while (offset < bytes.size) {
            val tag = varint().toInt()
            check(tag ushr 3 == 1 && tag and 7 == 2) { "Invalid legacy preferences backup" }
            val size = length()
            val end = offset + size
            var key: String? = null
            var value: Any? = null
            while (offset < end) {
                val field = varint().toInt()
                when (field ushr 3) {
                    1 -> key = string()
                    2 -> value = readValue(end)
                    else -> skip(field and 7)
                }
            }
            check(offset == end) { "Invalid legacy preferences backup" }
            check(key != null && value != null && key !in result) { "Invalid legacy preferences entry" }
            result[key] = value
        }
        return result
    }

    private fun readValue(end: Int): Any? {
        val size = length()
        val valueEnd = offset + size
        check(valueEnd <= end) { "Invalid legacy preferences value" }
        var value: Any? = null
        while (offset < valueEnd) {
            val field = varint().toInt()
            value =
                when (field ushr 3) {
                    1 -> {
                        varint() != 0L
                    }

                    2 -> {
                        Float.fromBits(fixed32())
                    }

                    3 -> {
                        varint().toInt()
                    }

                    4 -> {
                        varint()
                    }

                    5 -> {
                        string()
                    }

                    6 -> {
                        readStringSet(valueEnd)
                    }

                    7 -> {
                        Double.fromBits(fixed64())
                    }

                    8 -> {
                        byteArray()
                    }

                    else -> {
                        skip(field and 7)
                        value
                    }
                }
        }
        check(offset == valueEnd && valueEnd <= end) { "Invalid legacy preferences value" }
        return value
    }

    private fun readStringSet(end: Int): Set<String> {
        val size = length()
        val setEnd = offset + size
        check(setEnd <= end) { "Invalid legacy preferences set" }
        val values = linkedSetOf<String>()
        while (offset < setEnd) {
            val field = varint().toInt()
            if (field ushr 3 == 1) values += string() else skip(field and 7)
        }
        check(offset == setEnd && setEnd <= end) { "Invalid legacy preferences set" }
        return values
    }

    private fun string(): String {
        val size = length()
        val value = bytes.copyOfRange(offset, offset + size).decodeToString(throwOnInvalidSequence = true)
        offset += size
        return value
    }

    private fun byteArray(): ByteArray {
        val size = length()
        val value = bytes.copyOfRange(offset, offset + size)
        offset += size
        return value
    }

    private fun length(): Int =
        varint().toInt().also { check(it >= 0 && it <= bytes.size - offset) { "Invalid legacy preferences length" } }

    private fun varint(): Long {
        var result = 0L
        repeat(10) { index ->
            check(offset < bytes.size) { "Truncated legacy preferences backup" }
            val byte = bytes[offset++].toInt() and 0xff
            check(index < 9 || byte and 0xfe == 0) { "Invalid legacy preferences varint" }
            result = result or ((byte and 0x7f).toLong() shl (index * 7))
            if (byte and 0x80 == 0) return result
        }
        error("Invalid legacy preferences varint")
    }

    private fun fixed32(): Int {
        check(offset + 4 <= bytes.size) { "Truncated legacy preferences backup" }
        return (0..3).fold(0) { value, shift -> value or ((bytes[offset++].toInt() and 0xff) shl (shift * 8)) }
    }

    private fun fixed64(): Long {
        check(offset + 8 <= bytes.size) { "Truncated legacy preferences backup" }
        return (0..7).fold(0L) { value, shift -> value or ((bytes[offset++].toLong() and 0xff) shl (shift * 8)) }
    }

    private fun skip(wire: Int) {
        when (wire) {
            0 -> {
                varint()
            }

            1 -> {
                offset += 8
            }

            2 -> {
                val size = length()
                offset += size
            }

            5 -> {
                offset += 4
            }

            else -> {
                error("Invalid legacy preferences field")
            }
        }
        check(offset <= bytes.size) { "Truncated legacy preferences backup" }
    }
}
