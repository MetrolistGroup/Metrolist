package com.metrolist.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class WordTimestamp(
    val text: String,
    val startTime: Double,
    val endTime: Double,
    val hasTrailingSpace: Boolean = true,
    val isBackground: Boolean = false,
    val isLineTimed: Boolean = false,
)

@Serializable
data class LyricsEntry(
    val time: Long,
    val text: String,
    val words: List<WordTimestamp>? = null,
    val agent: String? = null,
    val endTime: Long? = null,
    val isBackground: Boolean = false,
    val translatedText: String? = null,
    val romanizedText: String? = null,
    val romanizedWords: List<WordTimestamp>? = null,
    val songPart: String? = null,
    val backgroundText: String? = null,
    val backgroundTranslatedText: String? = null,
) : Comparable<LyricsEntry> {
    override fun compareTo(other: LyricsEntry): Int = (time - other.time).toInt()

    companion object {
        val HEAD_LYRICS_ENTRY = LyricsEntry(0L, "")
    }
}

enum class AgentType {
    PERSON,
    GROUP,
    OTHER,
    ;

    companion object {
        fun fromString(value: String): AgentType =
            when (value.trim().lowercase()) {
                "group" -> GROUP
                "other" -> OTHER
                else -> PERSON
            }
    }
}

@Serializable
data class AgentInfo(
    val id: String,
    val type: AgentType,
    val name: String? = null,
    val color: String? = null,
)

@Serializable
data class MlxMeta(
    val version: String = "1.0",
    val lyricSource: String? = null,
    val lastFetched: String? = null,
    val ignoreRetiming: Boolean = false,
    val provider: String? = null,
    val subprovider: String? = null,
    val sourceFormat: String? = null,
    val syncType: String? = null,
    val sourceId: String? = null,
    val curator: String? = null,
    val lyricsCurator: String? = null,
    val lyricsCuratorId: String? = null,
    val copyright: String? = null,
)

@Serializable
data class MlxSong(
    val title: String? = null,
    val artist: String? = null,
    val youtubeId: String? = null,
    val songwriters: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
    val platformId: String? = null,
    val language: String? = null,
    val romanizationLanguage: String? = null,
    val translationLanguage: String? = null,
)

@Serializable
data class MlxColors(
    val background: String? = null,
    val text: String? = null,
    val highlight: String? = null,
)

@Serializable
data class MlxSongPart(
    val name: String,
    val startMs: Long,
    val endMs: Long? = null,
)

data class MlxDocument(
    val meta: MlxMeta = MlxMeta(),
    val song: MlxSong = MlxSong(),
    val agents: Map<String, AgentInfo> = emptyMap(),
    val lines: List<LyricsEntry> = emptyList(),
    val songParts: List<MlxSongPart> = emptyList(),
    val colors: MlxColors? = null,
)

fun lyricsProviderLabel(
    provider: String?,
    document: MlxDocument?,
): String? =
    if (provider.equals("Lyprio", ignoreCase = true)) {
        document
            ?.meta
            ?.subprovider
            ?.takeIf(String::isNotBlank)
            ?.let { "$provider/$it" }
            ?: provider
    } else {
        provider
    }

fun lyricsProviderUrl(
    provider: String?,
    sourceId: String?,
): String? {
    val id = sourceId?.takeIf { it.isNotBlank() } ?: return null
    return when {
        provider.equals("Unison", ignoreCase = true) && UNISON_VIDEO_ID.matches(id) -> {
            "https://unison.betterlyrics.org/song/$id"
        }

        provider.equals("lrc.red", ignoreCase = true) && ISRC.matches(id) -> {
            "https://lrc.red/s/$id"
        }

        provider.equals("LrcLib", ignoreCase = true) && id.toLongOrNull() != null -> {
            "https://lrclib.net/tracks/$id"
        }

        else -> {
            null
        }
    }
}

fun lyricsCuratorUrl(
    provider: String?,
    curatorId: String?,
): String? {
    if (!provider.equals("Unison", ignoreCase = true)) return null
    val id = curatorId?.takeIf { UNISON_CURATOR_ID.matches(it) } ?: return null
    return "https://unison.betterlyrics.org/curator/$id"
}

private val UNISON_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
private val UNISON_CURATOR_ID = Regex("[A-Za-z0-9][A-Za-z0-9._~-]*")
private val ISRC = Regex("[A-Za-z]{2}[A-Za-z0-9]{3}[0-9]{7}")

data class LyricsWithProvider(
    val lyrics: String,
    val provider: String,
    val isComplete: Boolean = true,
    val isUserSelected: Boolean = false,
)

enum class LyricsTimingKind {
    UNSYNCED,
    LINE_SYNCED,
    WORD_SYNCED,
}

data class LyricsProviderResult(
    val provider: LyricsProvider,
    val providerName: String,
    val lyrics: String,
    val timingKind: LyricsTimingKind,
    val song: MlxSong = MlxSong(),
    val preview: String = "",
)

val LyricsProvider.supportedTimingKinds: Set<LyricsTimingKind>
    get() =
        when (this) {
            LyricsProvider.AUTO -> {
                emptySet()
            }

            LyricsProvider.KUGOU -> {
                setOf(LyricsTimingKind.LINE_SYNCED)
            }

            LyricsProvider.LRCLIB, LyricsProvider.YOUTUBE -> {
                setOf(LyricsTimingKind.LINE_SYNCED, LyricsTimingKind.UNSYNCED)
            }

            LyricsProvider.USER, LyricsProvider.LYPRIO, LyricsProvider.UNISON -> {
                setOf(LyricsTimingKind.LINE_SYNCED, LyricsTimingKind.WORD_SYNCED, LyricsTimingKind.UNSYNCED)
            }

            LyricsProvider.BETTERLYRICS, LyricsProvider.LRCRED, LyricsProvider.PAXSENIX,
            LyricsProvider.YOUTUBESUBTITLE, LyricsProvider.SIMPMUSIC,
            -> {
                setOf(LyricsTimingKind.LINE_SYNCED, LyricsTimingKind.WORD_SYNCED)
            }
        }
