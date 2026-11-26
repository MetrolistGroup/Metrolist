package com.metrolist.android.carlights

import java.util.UUID

// Wire protocol of Magic CarLight BLE controllers (service FFF0, write characteristic FFF3).
// Zoned frame: 8E 07 05 03 RR GG BB 10 <zoneMask>. Plain frame: 7E 07 05 03 RR GG BB 10 EF.
internal object CarLightProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val WRITE_UUID: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")

    const val ZONE_CENTRAL = 0x10
    const val ZONE_DOORS = 0x20
    const val ZONE_REAR = 0x40

    private const val ZONED_HEADER = 0x8E
    private const val PLAIN_HEADER = 0x7E
    private const val PLAIN_END = 0xEF
    private const val CMD_COLOR = 0x05
    private const val COLOR_STATIC = 0x03
    private const val COLOR_TARGET_LIGHTS = 0x10

    enum class Family {
        ZONED,

        // The device has a zoned and a plain controller, write to both.
        ZONED_AND_PLAIN,
        PLAIN,
    }

    private val namePrefixes =
        listOf(
            "HRS-MBLEM" to Family.ZONED,
            "MELK-QA" to Family.ZONED_AND_PLAIN,
            "MHRS-QA" to Family.ZONED_AND_PLAIN,
            "MELK-O" to Family.PLAIN,
        )

    // Null if the advertised name is not a supported light.
    fun familyFor(name: String?): Family? {
        if (name.isNullOrEmpty()) return null
        if (name.startsWith("MELK-O") && name.contains('W')) return null
        return namePrefixes.firstOrNull { name.startsWith(it.first) }?.second
    }

    private fun colorFrame(
        header: Int,
        rgb: Int,
        last: Int,
    ): ByteArray =
        byteArrayOf(
            header.toByte(),
            0x07,
            CMD_COLOR.toByte(),
            COLOR_STATIC.toByte(),
            (rgb shr 16).toByte(),
            (rgb shr 8).toByte(),
            rgb.toByte(),
            COLOR_TARGET_LIGHTS.toByte(),
            last.toByte(),
        )

    // zoneColors maps a zone mask to a 0xRRGGBB color. Zones with the same color share one frame.
    fun colorFrames(
        family: Family,
        zoneColors: Map<Int, Int>,
    ): List<ByteArray> {
        val byColor = LinkedHashMap<Int, Int>()
        zoneColors.forEach { (zone, rgb) -> byColor.merge(rgb and 0xFFFFFF, zone, Int::or) }
        val frames = mutableListOf<ByteArray>()
        if (family != Family.PLAIN) {
            byColor.forEach { (rgb, mask) -> frames += colorFrame(ZONED_HEADER, rgb, mask) }
        }
        if (family != Family.ZONED) {
            // Plain controllers have no zones. A plain-only device follows the central role, a device that also has
            // a zoned controller mirrors the first mapped color.
            val plain = if (family == Family.PLAIN) zoneColors[ZONE_CENTRAL]?.let { it and 0xFFFFFF } else byColor.keys.firstOrNull()
            plain?.let { frames += colorFrame(PLAIN_HEADER, it, PLAIN_END) }
        }
        return frames
    }
}
