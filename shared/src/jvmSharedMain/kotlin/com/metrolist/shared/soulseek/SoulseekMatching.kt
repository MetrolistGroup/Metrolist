package com.metrolist.shared.soulseek

import java.text.Normalizer
import kotlin.math.abs

/** A downloadable file offered by a peer, described for the version picker. */
data class SoulseekCandidate(
    val username: String,
    val filename: String,
    val size: Long,
    val extension: String,
    /** One of [SoulseekMatching.FORMAT_ORDER]. */
    val format: String,
    val bitrate: Int?,
    val bitDepth: Int?,
    val sampleRate: Int?,
    val durationSeconds: Int?,
    val freeSlot: Boolean,
    val queueLength: Long,
    val averageSpeed: Long,
)

/** Matches search results to a song and groups them by format; mirrors scripts/soulseek/metrolist_slsk.py. */
object SoulseekMatching {
    /** The order the picker lists formats in: 320 kbps or V0, CD-quality lossless, then 24-bit or >48 kHz lossless. */
    val FORMAT_ORDER = listOf("320", "lossless", "hires")

    private val LOSSLESS = setOf("flac", "wav", "aiff", "aif", "ape", "wv", "alac")
    private val LOSSY = setOf("mp3", "m4a", "aac", "ogg", "opus")

    // Words that mark a different recording; skipped unless the requested title has them too.
    private val VARIANT_WORDS =
        setOf("remix", "live", "instrumental", "karaoke", "acapella", "cover", "edit", "demo", "sped", "slowed", "nightcore")
    private val wordPattern = Regex("[a-z0-9]+")
    private val bracketSuffix =
        Regex("""[(\[][^)\]]*(feat|ft\.|official|video|audio|lyric|remaster|explicit)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val remasterSuffix = Regex("""\s+-\s+.*remaster.*$""", RegexOption.IGNORE_CASE)

    fun words(text: String): List<String> {
        val ascii = Normalizer.normalize(text, Normalizer.Form.NFKD).filter { it.code < 128 }.lowercase()
        return wordPattern.findAll(ascii).map { it.value }.toList()
    }

    /** Drops "(feat. x)", "[Official Video]" and " - Remastered 2011" style suffixes. */
    fun cleanTitle(title: String): String = title.replace(bracketSuffix, "").replace(remasterSuffix, "").trim()

    fun searchQuery(
        artist: String,
        title: String,
    ): String = (words(artist) + words(cleanTitle(title))).joinToString(" ")

    private fun extensionOf(filename: String): String = filename.substringAfterLast('.', "").lowercase()

    private fun baseName(filename: String): String = filename.replace('\\', '/').substringAfterLast('/')

    /** The picker format of a file, or null when it is below 320 kbps or not audio. */
    fun formatOf(file: SoulseekFileResult): String? {
        var extension = extensionOf(file.filename)
        val bitrate = file.attributes[Codes.ATTR_BITRATE] ?: 0
        if (extension == "m4a" && bitrate >= 700) extension = "alac"
        if (extension in LOSSLESS) {
            val hires =
                (file.attributes[Codes.ATTR_BIT_DEPTH] ?: 16) > 16 || (file.attributes[Codes.ATTR_SAMPLE_RATE] ?: 44_100) > 48_000
            return if (hires) "hires" else "lossless"
        }
        val vbr = (file.attributes[Codes.ATTR_VBR] ?: 0) != 0
        return if (extension in LOSSY && (bitrate >= 320 || (vbr && bitrate >= 245))) "320" else null
    }

    fun matches(
        file: SoulseekFileResult,
        artist: String,
        title: String,
        durationSeconds: Int?,
    ): Boolean {
        val pathWords = words(file.filename).toSet()
        val baseWords = words(baseName(file.filename)).toSet()
        val titleWords = words(cleanTitle(title))
        if (titleWords.isEmpty() || !baseWords.containsAll(titleWords)) return false
        // The artist often lives in the folder name, so check the whole path.
        if (artist.isNotBlank() && !pathWords.containsAll(words(artist))) return false
        if ((baseWords intersect VARIANT_WORDS).any { it !in titleWords }) return false
        val fileDuration = file.attributes[Codes.ATTR_DURATION]
        return durationSeconds == null || fileDuration == null || abs(fileDuration - durationSeconds) <= 3
    }

    /** The best [perFormat] matching files for each format, in [FORMAT_ORDER]. */
    fun options(
        replies: List<SoulseekSearchReply>,
        artist: String,
        title: String,
        durationSeconds: Int?,
        perFormat: Int = 3,
    ): List<SoulseekCandidate> {
        val candidates =
            replies.flatMap { reply ->
                reply.files.mapNotNull { file ->
                    val format = formatOf(file) ?: return@mapNotNull null
                    if (!matches(file, artist, title, durationSeconds)) return@mapNotNull null
                    SoulseekCandidate(
                        username = reply.username,
                        filename = file.filename,
                        size = file.size,
                        extension = extensionOf(file.filename),
                        format = format,
                        bitrate = file.attributes[Codes.ATTR_BITRATE],
                        bitDepth = file.attributes[Codes.ATTR_BIT_DEPTH],
                        sampleRate = file.attributes[Codes.ATTR_SAMPLE_RATE],
                        durationSeconds = file.attributes[Codes.ATTR_DURATION],
                        freeSlot = reply.hasFreeSlots,
                        queueLength = reply.queueLength,
                        averageSpeed = reply.averageSpeed,
                    )
                }
            }
        // Within a format prefer a free slot, a known duration, a short queue, then a fast peer.
        val preference =
            compareByDescending<SoulseekCandidate> { it.freeSlot }
                .thenByDescending { it.durationSeconds != null }
                .thenBy { it.queueLength }
                .thenByDescending { it.averageSpeed }
        return FORMAT_ORDER.flatMap { format -> candidates.filter { it.format == format }.sortedWith(preference).take(perFormat) }
    }

    /** A safe local file name for a peer's path: its last component without separators or leading dots. */
    fun localFileName(remotePath: String): String {
        val name = baseName(remotePath).replace(Regex("""[/\\:*?"<>|\u0000-\u001f]"""), "_").trimStart('.').trim()
        return name.ifEmpty { "soulseek-download" }.take(200)
    }
}
