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

private val AUDIENCE_NUMBER_REGEX = Regex("""\p{Nd}+(?:[\p{Zs}.,٬٫'’]\p{Nd}+)*""")
private val AUDIENCE_FORMAT_CONTROLS_REGEX = Regex("""\p{Cf}""")

// Unknown magnitude units keep YouTube's full text.
private val AUDIENCE_UNIT_REGEX =
    Regex(
        """(?:[kmbt]|mln|mio|mil|mrd|mi|mn|milj|jt|rb|tr|тыс|млн|млрд|трлн|ألف|مليون|مليار|هزار|میلیون|میلیارد|কো|লাখ|কোটি|क॰|लाख|करोड़|ล้าน|พัน)(?:\.)?(?![\p{L}\p{M}])|[万萬亿億兆만억조](?=[人位명]|$|[^\p{L}\p{M}])""",
        RegexOption.IGNORE_CASE,
    )

data class AudienceCount(
    val text: String,
    val isCountOnly: Boolean,
) {
    inline fun withLabel(format: (String) -> String): String = if (isCountOnly) format(text) else text
}

fun parseAudienceCount(raw: String?): AudienceCount? {
    if (raw.isNullOrBlank()) return null
    val text = raw.replace(AUDIENCE_FORMAT_CONTROLS_REGEX, "").trim()
    val original = AudienceCount(raw, isCountOnly = false)
    val number = AUDIENCE_NUMBER_REGEX.findAll(text).singleOrNull() ?: return original
    val before = text.substring(0, number.range.first).trimEnd()
    val trailing = text.substring(number.range.last + 1)
    val after = trailing.trimStart()
    val suffix = AUDIENCE_UNIT_REGEX.matchAt(after, 0)
    val prefix =
        AUDIENCE_UNIT_REGEX.findAll(before).lastOrNull {
            it.range.last == before.lastIndex &&
                (it.range.first == 0 || !before[it.range.first - 1].isLetter())
        }
    if (suffix != null && prefix != null) return original
    val count =
        when {
            suffix != null -> number.value + trailing.takeWhile(Char::isWhitespace) + suffix.value
            prefix != null -> text.substring(prefix.range.first, number.range.last + 1)
            before.isEmpty() && after.isEmpty() -> number.value
            else -> return original
        }
    return AudienceCount(count, isCountOnly = true)
}

fun monthlyListenersCount(raw: String?): String? = parseAudienceCount(raw)?.text

fun monthlyAudienceText(
    value: String?,
    audienceLabel: String,
): String? = parseAudienceCount(value)?.withLabel { "$it $audienceLabel" }

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
