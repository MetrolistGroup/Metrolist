package com.metrolist.shared.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LegacyPreferencesProtoTest {
    @Test
    fun readsAndroidPreferenceValues() {
        val opaqueValue = byteArrayOf(1, 2, 3)
        val bytes =
            combine(
                entry("appLanguage", bytesField(5, "fr".encodeToByteArray())),
                entry("themeMode", varintField(3, 2)),
                entry("dynamicTheme", varintField(1, 0)),
                entry("longValue", longField(4, 1_234_567_890_123L)),
                entry("floatValue", fixed32Field(2, 1.5f.toBits())),
                entry(
                    "stringSet",
                    bytesField(6, combine(bytesField(1, "one".encodeToByteArray()), bytesField(1, "two".encodeToByteArray()))),
                ),
                entry("doubleValue", fixed64Field(7, 1.25.toBits())),
                entry("opaque", bytesField(8, opaqueValue)),
            )
        val values = decodeLegacyPreferences(bytes)

        assertEquals(
            mapOf(
                "appLanguage" to "fr",
                "themeMode" to 2,
                "dynamicTheme" to false,
                "longValue" to 1_234_567_890_123L,
                "floatValue" to 1.5f,
                "stringSet" to setOf("one", "two"),
                "doubleValue" to 1.25,
            ),
            values - "opaque",
        )
        assertContentEquals(opaqueValue, values["opaque"] as ByteArray)
    }

    @Test
    fun rejectsTruncatedLegacyPreferences() {
        assertFailsWith<IllegalStateException> {
            decodeLegacyPreferences(byteArrayOf(0x0a, 0x02, 0x0a))
        }
    }

    private fun entry(
        key: String,
        value: ByteArray,
    ): ByteArray = bytesField(1, combine(bytesField(1, key.encodeToByteArray()), bytesField(2, value)))

    private fun bytesField(
        field: Int,
        value: ByteArray,
    ): ByteArray = combine(varint((field shl 3) or 2), varint(value.size), value)

    private fun varintField(
        field: Int,
        value: Int,
    ): ByteArray = combine(varint(field shl 3), varint(value))

    private fun longField(
        field: Int,
        value: Long,
    ): ByteArray = combine(varint((field shl 3).toLong()), varint(value))

    private fun fixed32Field(
        field: Int,
        value: Int,
    ): ByteArray =
        combine(
            varint((field shl 3) or 5),
            ByteArray(4) { shift -> (value ushr (shift * 8)).toByte() },
        )

    private fun fixed64Field(
        field: Int,
        value: Long,
    ): ByteArray =
        combine(
            varint((field shl 3) or 1),
            ByteArray(8) { shift -> (value ushr (shift * 8)).toByte() },
        )

    private fun varint(value: Int): ByteArray = varint(value.toLong())

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val bytes = mutableListOf<Byte>()
        do {
            val next = (remaining and 0x7f).toInt()
            remaining = remaining ushr 7
            bytes += (if (remaining == 0L) next else next or 0x80).toByte()
        } while (remaining != 0L)
        return bytes.toByteArray()
    }

    private fun combine(vararg arrays: ByteArray): ByteArray {
        val result = ByteArray(arrays.sumOf(ByteArray::size))
        var offset = 0
        arrays.forEach { bytes ->
            bytes.copyInto(result, offset)
            offset += bytes.size
        }
        return result
    }
}
