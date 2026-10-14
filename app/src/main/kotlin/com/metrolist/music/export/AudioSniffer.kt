/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import java.io.File
import java.io.IOException

/** Identifies the downloaded bytes by magic numbers, so the file extension always matches the content. */
object AudioSniffer {
    fun detect(file: File): SourceContainer {
        val header = ByteArray(12)
        val read =
            try {
                file.inputStream().use { it.read(header) }
            } catch (_: IOException) {
                -1
            }
        if (read < 8) return SourceContainer.UNKNOWN
        return when {
            header[4] == 'f'.code.toByte() &&
                header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() &&
                header[7] == 'p'.code.toByte() -> SourceContainer.MP4
            header[0] == 0x1A.toByte() &&
                header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() &&
                header[3] == 0xA3.toByte() -> SourceContainer.WEBM
            else -> SourceContainer.UNKNOWN
        }
    }

    /** Container the stream is expected to have, from the mime type stored in `FormatEntity`. */
    fun fromMimeType(mimeType: String?): SourceContainer =
        when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> SourceContainer.MP4
            "audio/webm" -> SourceContainer.WEBM
            else -> SourceContainer.UNKNOWN
        }

    /** Extension of the file produced for [container] in [ExportFormat.ORIGINAL] mode. */
    fun originalExtension(
        container: SourceContainer,
        codecs: String?,
    ): String =
        when (container) {
            SourceContainer.MP4 -> "m4a"
            SourceContainer.WEBM -> if (codecs.isNullOrBlank() || codecs.contains("opus", ignoreCase = true)) "opus" else "webm"
            SourceContainer.UNKNOWN -> "bin"
        }
}
