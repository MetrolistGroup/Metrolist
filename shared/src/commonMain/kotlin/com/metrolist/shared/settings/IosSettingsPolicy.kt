package com.metrolist.shared.settings

import androidx.compose.ui.text.intl.Locale
import com.metrolist.shared.state.GlobalYouTube

internal fun resolveAppLanguageTag(
    configuredLanguage: String,
    systemLanguageTags: List<String>,
): String {
    val selectedLanguage = configuredLanguage.trim()
    val requested =
        if (selectedLanguage.isBlank() || selectedLanguage.equals(GlobalYouTube.SYSTEM, ignoreCase = true)) {
            systemLanguageTags.firstOrNull().orEmpty()
        } else {
            selectedLanguage
        }
    return requested
        .trim()
        .replace('_', '-')
        .takeIf(String::isNotBlank)
        ?.let { Locale(canonicalAppLanguageTag(it)).toLanguageTag() }
        .orEmpty()
}

internal fun localeRegionQualifier(languageTag: String): String =
    languageTag
        .split('-')
        .drop(1)
        .takeWhile { it.length != 1 }
        .firstOrNull { part ->
            (part.length == 2 && part.all { it in 'A'..'Z' || it in 'a'..'z' }) ||
                (part.length == 3 && part.all { it in '0'..'9' })
        }?.uppercase()
        .orEmpty()

internal fun sanitizeIosDiagnosticMessage(message: String): String =
    message
        .replace(URL_PATTERN, "<redacted-url>")
        .replace(BEARER_PATTERN, "Bearer <redacted>")
        .replace(SECRET_PATTERN, "\$1=<redacted>")

internal data class IosDeviceSettings(
    val keepScreenAwake: Boolean,
    val hideStatusBar: Boolean,
)

internal fun mapIosDeviceSettings(
    keepScreenOnMode: KeepScreenOnMode,
    expandedPlayerVisible: Boolean,
    lyricsVisible: Boolean,
    lyricsFullscreenVisible: Boolean,
    isPlaying: Boolean,
    immersivePlayer: Boolean,
    hideStatusBarInLyricsFullscreen: Boolean,
): IosDeviceSettings =
    IosDeviceSettings(
        keepScreenAwake = expandedPlayerVisible && keepScreenOnMode.keepsAwake(isPlaying, lyricsVisible),
        hideStatusBar =
            (immersivePlayer && expandedPlayerVisible) ||
                (hideStatusBarInLyricsFullscreen && lyricsFullscreenVisible),
    )

internal fun isValidFontImport(
    fileName: String,
    size: Long,
    header: ByteArray,
): Boolean {
    if (fileName.isBlank() || fileName.any { it == '/' || it == '\\' || it.code < 0x20 }) return false
    if (size !in 1..MAX_FONT_IMPORT_BYTES || header.size < 4) return false
    if (fileName.substringAfterLast('.', "").lowercase() !in setOf("ttf", "otf")) return false

    val signature = header.copyOfRange(0, 4)
    return FONT_MAGICS.any(signature::contentEquals)
}

internal const val MAX_FONT_IMPORT_BYTES = 20L * 1024L * 1024L
internal const val IOS_FILE_COPY_CHUNK_BYTES = 64 * 1024

internal fun nextIosFileCopyChunkSize(
    copiedBytes: Long,
    maximumBytes: Long,
): Int {
    require(maximumBytes > 0 && copiedBytes in 0..maximumBytes)
    val remaining = (maximumBytes - copiedBytes).coerceAtMost(IOS_FILE_COPY_CHUNK_BYTES - 1L)
    return (remaining + 1).toInt().coerceAtMost(IOS_FILE_COPY_CHUNK_BYTES)
}

private val URL_PATTERN = Regex("(?i)https?://\\S+")
private val BEARER_PATTERN = Regex("(?i)\\bBearer\\s+\\S+")
private val SECRET_PATTERN =
    Regex(
        "(?i)\\b(access[_-]?token|refresh[_-]?token|token|cookie|authorization|password|secret|api[_-]?key|session)\\b[\\\"']?\\s*[:=]\\s*[\\\"']?[^\\s,;\\\"']+",
    )
private val FONT_MAGICS =
    listOf(
        byteArrayOf(0x00, 0x01, 0x00, 0x00),
        "OTTO".encodeToByteArray(),
        "true".encodeToByteArray(),
        "typ1".encodeToByteArray(),
    )
