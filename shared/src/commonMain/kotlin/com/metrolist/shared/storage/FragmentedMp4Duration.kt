package com.metrolist.shared.storage

internal const val FRAGMENTED_MP4_HEADER_BYTES = 64 * 1024

internal fun clearFragmentedMp4MovieDurations(header: ByteArray): Boolean {
    val moov = mp4Children(header, 0, header.size).firstOrNull { it.type == "moov" } ?: return false
    val moovChildren = mp4Children(header, moov.body, moov.end)
    if (moovChildren.none { it.type == "mvex" }) return false
    val durations = mutableListOf<IntRange>()
    moovChildren.firstOrNull { it.type == "mvhd" }?.let { durations += it.headerDuration(header, 16, 24) }
    for (trak in moovChildren.filter { it.type == "trak" }) {
        val trakChildren = mp4Children(header, trak.body, trak.end)
        val mdia = trakChildren.firstOrNull { it.type == "mdia" } ?: return false
        val mdiaChildren = mp4Children(header, mdia.body, mdia.end)
        val stts =
            mdiaChildren
                .firstOrNull { it.type == "minf" }
                ?.let { minf -> mp4Children(header, minf.body, minf.end).firstOrNull { it.type == "stbl" } }
                ?.let { stbl -> mp4Children(header, stbl.body, stbl.end).firstOrNull { it.type == "stts" } }
                ?: return false
        if (stts.end - stts.body < 8 || header.readUInt(stts.body + 4, 4) != 0L) return false
        trakChildren.firstOrNull { it.type == "tkhd" }?.let { durations += it.headerDuration(header, 20, 28) }
        mdiaChildren.firstOrNull { it.type == "mdhd" }?.let { durations += it.headerDuration(header, 16, 24) }
    }
    var changed = false
    for (range in durations) {
        for (index in range) {
            if (header[index] != 0.toByte()) {
                header[index] = 0
                changed = true
            }
        }
    }
    return changed
}

private class Mp4Box(
    val type: String,
    val body: Int,
    val end: Int,
)

private fun mp4Children(
    data: ByteArray,
    start: Int,
    end: Int,
): List<Mp4Box> {
    val boxes = mutableListOf<Mp4Box>()
    var offset = start
    while (offset + 8 <= end) {
        var size = data.readUInt(offset, 4)
        var headerSize = 8
        if (size == 1L) {
            if (offset + 16 > end) break
            size = data.readUInt(offset + 8, 8)
            headerSize = 16
        } else if (size == 0L) {
            size = (end - offset).toLong()
        }
        if (size < headerSize || size > end - offset) break
        val type = (4 until 8).map { data[offset + it].toInt().toChar() }.joinToString("")
        boxes += Mp4Box(type, offset + headerSize, offset + size.toInt())
        offset += size.toInt()
    }
    return boxes
}

private fun Mp4Box.headerDuration(
    data: ByteArray,
    v0Offset: Int,
    v1Offset: Int,
): IntRange {
    val (offset, width) = if (data[body] == 1.toByte()) v1Offset to 8 else v0Offset to 4
    val start = body + offset
    return if (start + width <= end) start until start + width else IntRange.EMPTY
}

private fun ByteArray.readUInt(
    offset: Int,
    width: Int,
): Long = (0 until width).fold(0L) { value, index -> (value shl 8) or (this[offset + index].toLong() and 0xFF) }
