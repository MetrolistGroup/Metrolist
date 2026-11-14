package com.metrolist.shared.utils

import kotlin.math.absoluteValue
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

fun formatPlaybackSpeed(speed: Float): String = formatPlaybackSpeed(speed.toDouble())

fun formatPlaybackSpeed(speed: Double): String = speed.formatFixed(2)

fun formatPlaybackPitch(semitones: Float): String {
    val rounded = (semitones * 100).roundToInt() / 100f
    val value = rounded.toDouble().formatFixed(2)
    return if (rounded > 0) "+$value" else value
}

fun playbackPitchToSemitones(pitch: Float): Float = (12 * log2(pitch.coerceIn(0.5f, 2f))).coerceIn(-12f, 12f)

fun semitonesToPlaybackPitch(semitones: Float): Float = 2f.pow(semitones.coerceIn(-12f, 12f) / 12f)

fun normalizePlaybackTempo(value: Float): Float? {
    if (!value.isFinite()) return null
    return value.coerceIn(0.5f, 2f)
}

fun formatLoudness(loudness: Float): String = formatLoudness(loudness.toDouble())

fun formatLoudness(loudness: Double): String = loudness.formatFixed(2)

data class FileSizeUnits(
    val byte: String,
    val kilobyte: String,
    val megabyte: String,
    val gigabyte: String,
    val terabyte: String,
)

fun formatFileSize(
    bytes: Long,
    units: FileSizeUnits,
): String {
    val magnitude = bytes.toDouble().absoluteValue
    val (value, unit) =
        when {
            magnitude < 1024 -> magnitude.toLong().toString() to units.byte
            magnitude < 1024 * 1024 -> (magnitude / 1024.0).format(1) to units.kilobyte
            magnitude < 1024L * 1024 * 1024 -> (magnitude / (1024.0 * 1024.0)).format(1) to units.megabyte
            magnitude < 1024L * 1024 * 1024 * 1024 -> (magnitude / (1024.0 * 1024.0 * 1024.0)).format(1) to units.gigabyte
            else -> (magnitude / (1024.0 * 1024.0 * 1024.0 * 1024.0)).format(1) to units.terabyte
        }
    val sign = if (bytes < 0) "-" else ""
    return "$sign$value\u00a0$unit"
}

private fun Double.formatFixed(digits: Int): String {
    if (!isFinite()) return toString()
    if (digits <= 0) return roundToLong().toString()
    val factor = (1..digits).fold(1L) { acc, _ -> acc * 10L }
    val scaled = (this * factor).roundToLong()
    val absolute = scaled.absoluteValue
    return buildString {
        if (scaled < 0) append('-')
        append(absolute / factor)
        append(localeDecimalSeparator())
        append((absolute % factor).toString().padStart(digits, '0'))
    }
}

private val MONTHLY_LISTENERS_NUMBER_REGEX = Regex("^\\p{Nd}+(?:[\\s\\p{Zs}.,٬٫]\\p{Nd}+)*")

private val MONTHLY_LISTENERS_UNIT_REGEX =
    Regex("^(?:[kmbt]|mln|mio|mil|mrd|тыс|млн|млрд|трлн|ألف|مليون|مليار)(?:\\.)?(?![\\p{L}\\p{M}])|^[万萬亿億兆]", RegexOption.IGNORE_CASE)

private data class MonthlyListenersValue(
    val text: String,
)

fun monthlyListenersCount(raw: String?): String? = parseMonthlyListeners(raw)?.text

fun monthlyAudienceText(
    value: String?,
    audienceLabel: String,
): String? = monthlyListenersCount(value)?.let { "$it $audienceLabel" }

private fun parseMonthlyListeners(raw: String?): MonthlyListenersValue? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val numberMatch = MONTHLY_LISTENERS_NUMBER_REGEX.find(text) ?: return null
    if (!numberMatch.value.any(Char::isDigit)) return null
    val numberEnd = numberMatch.range.last + 1
    val rest = text.substring(numberEnd)
    val unit = MONTHLY_LISTENERS_UNIT_REGEX.find(rest.trimStart())?.value
    return MonthlyListenersValue(numberMatch.value + if (unit != null) rest.takeWhile(Char::isWhitespace) + unit else "")
}

private fun Double.format(digits: Int): String {
    if (!isFinite()) return toString()
    if (digits <= 0) return roundToLong().toString()

    val factor = (1..digits).fold(1L) { acc, _ -> acc * 10L }
    val scaled = (this * factor).roundToLong()
    val whole = scaled / factor
    val fraction =
        (scaled % factor)
            .absoluteValue
            .toString()
            .padStart(digits, '0')
            .trimEnd('0')
    return if (fraction.isEmpty()) whole.toString() else "$whole${localeDecimalSeparator()}$fraction"
}
