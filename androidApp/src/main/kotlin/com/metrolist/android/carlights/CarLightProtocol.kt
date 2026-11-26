package com.metrolist.android.carlights

import java.util.UUID

// wire protocol of magic carlight ble controllers (service fff0, write characteristic fff3)
// zoned frame: 8E <len> <cmd> <payload...> <zoneMask> | plain frame: 7E <len> <cmd> <p0..p4> EF
internal object CarLightProtocol {
    // >> constants
    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val WRITE_UUID: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")

    const val ZONE_CENTRAL = 0x10
    const val ZONE_DOORS = 0x20
    const val ZONE_REAR = 0x40
    const val ZONE_ALL = ZONE_CENTRAL or ZONE_DOORS or ZONE_REAR

    private const val CMD_COLOR = 0x05
    private const val COLOR_STATIC = 0x03
    private const val COLOR_TARGET_LIGHTS = 0x10

    // >> types
    enum class Family {
        // zoned frames only
        ZONED,

        // device has a zoned and a plain controller, write to both
        ZONED_AND_PLAIN,

        // plain frames only
        PLAIN,
    }

    // >> tables
    private val namePrefixes =
        listOf(
            "HRS-MBLEM" to Family.ZONED,
            "MELK-QA" to Family.ZONED_AND_PLAIN,
            "MHRS-QA" to Family.ZONED_AND_PLAIN,
            "MELK-O" to Family.PLAIN,
        )

    // >> functions
    // picks the frame family from the advertised name, null if it is not a supported light
    // @params: name String? -- advertised ble device name.
    fun familyFor(name: String?): Family? {
        if (name.isNullOrEmpty()) return null
        if (name.startsWith("MELK-O") && name.contains('W')) return null
        return namePrefixes.firstOrNull { name.startsWith(it.first) }?.second
    }

    // builds one zoned color frame
    // @params: rgb Int -- 0xRRGGBB color.
    // @params: zoneMask Int -- or of the ZONE_ constants this color applies to.
    fun zonedColor(
        rgb: Int,
        zoneMask: Int,
    ): ByteArray =
        byteArrayOf(
            0x8E.toByte(),
            0x07,
            CMD_COLOR.toByte(),
            COLOR_STATIC.toByte(),
            (rgb shr 16).toByte(),
            (rgb shr 8).toByte(),
            rgb.toByte(),
            COLOR_TARGET_LIGHTS.toByte(),
            zoneMask.toByte(),
        )

    // builds one plain color frame, plain controllers have no zones
    // @params: rgb Int -- 0xRRGGBB color.
    fun plainColor(rgb: Int): ByteArray =
        byteArrayOf(
            0x7E,
            0x07,
            CMD_COLOR.toByte(),
            COLOR_STATIC.toByte(),
            (rgb shr 16).toByte(),
            (rgb shr 8).toByte(),
            rgb.toByte(),
            COLOR_TARGET_LIGHTS.toByte(),
            0xEF.toByte(),
        )

    // builds every frame needed to show the zone colors on a device, zones with the same color share one frame
    // @params: family Family -- frame family of the connected device.
    // @params: zoneColors Map<Int, Int> -- zone mask to 0xRRGGBB color.
    fun colorFrames(
        family: Family,
        zoneColors: Map<Int, Int>,
    ): List<ByteArray> {
        val byColor = LinkedHashMap<Int, Int>()
        zoneColors.forEach { (zone, rgb) -> byColor[rgb and 0xFFFFFF] = (byColor[rgb and 0xFFFFFF] ?: 0) or zone }
        val frames = mutableListOf<ByteArray>()
        if (family != Family.PLAIN) {
            byColor.forEach { (rgb, mask) -> frames += zonedColor(rgb, mask) }
        }
        if (family != Family.ZONED) {
            // plain controllers have no zones. a plain-only device follows the central role, a device that also has
            // a zoned controller mirrors the first mapped color
            val plain = if (family == Family.PLAIN) zoneColors[ZONE_CENTRAL]?.let { it and 0xFFFFFF } else byColor.keys.firstOrNull()
            plain?.let { frames += plainColor(it) }
        }
        return frames
    }
}

// >> end << //
