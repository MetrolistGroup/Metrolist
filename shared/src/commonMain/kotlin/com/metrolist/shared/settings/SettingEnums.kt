/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.settings

import kotlinx.serialization.Serializable

enum class ThemeMode {
    LIGHT,
    DARK,
    AUTO,
    PURE_BLACK,
}

enum class KeepScreenOnMode {
    NEVER,
    PLAYER,
    LYRICS,
    PLAYER_AND_LYRICS,
    ;

    fun keepsAwake(
        isPlaying: Boolean,
        lyricsVisible: Boolean,
    ): Boolean =
        isPlaying &&
            when (this) {
                NEVER -> false
                PLAYER -> !lyricsVisible
                LYRICS -> lyricsVisible
                PLAYER_AND_LYRICS -> true
            }
}

enum class AppLayout {
    EXPRESS,
    COMFORTABLE,
    FOCUSED,
    DYNAMIC,
}

enum class SliderStyle {
    DEFAULT,
    WAVY,
    SLIM,
    SQUIGGLY,
    LINEAR,
    LINEAR_WAVY,
}

enum class PlayerThumbnailStyle {
    COMPACT,
    FULLSCREEN,
    CANVAS,
}

enum class PlayerBackgroundStyle {
    BLURRED_ARTWORK,
    SOLID,
    GRADIENT,
    ANIMATED_GRADIENT,
    LIQUID,
}

enum class ThemePaletteStyle {
    TONAL_SPOT,
    VIBRANT,
    EXPRESSIVE,
    RAINBOW,
    FRUIT_SALAD,
    MONOCHROME,
    NEUTRAL,
    CONTENT,
}

enum class LibraryViewType {
    LIST,
    GRID,
}

@Serializable
enum class LibraryFilterType(
    val storedValue: Int,
) {
    DEFAULT(0),
    ALL(1),
    TRACKS(2),
    PLAYLISTS(3),
    ARTISTS(5),
    ALBUMS(6),
    PODCASTS(7),
    ;

    companion object {
        // Keep stored IDs stable and map the removed Owned filter to Playlists.
        fun fromStoredValue(value: Int): LibraryFilterType? = if (value == 4) PLAYLISTS else entries.firstOrNull { it.storedValue == value }
    }
}

enum class LibraryContentSort {
    RECENTLY_ADDED,
    NAME,
    LAST_UPDATED,
    MANUAL,
    ARTIST,
    YEAR,
    SONG_COUNT,
    LENGTH,
    PLAYTIME,
}

enum class FontOption {
    SYSTEM,
    INTER,
    GOOGLE_SANS,
    MAPLE_MONO,
}

enum class AnimatedCoverResolution {
    LOW_360,
    MEDIUM_720,
    HIGH_1080,
    ORIGINAL,
}

enum class YouTubeCanvasMode {
    SHORT_CLIPS,
    FULL_VIDEO,
}

enum class LoudnessLevel(
    val targetLufs: Float,
) {
    AGGRESSIVE(-7f),
    LOUD(-11f),
    BALANCED(-14f),
    QUIET(-19f),
}

enum class MusicVideoQuality {
    AUTO,
    P2160,
    P1080,
    P720,
    P480,
}

val MusicVideoQuality.targetHeight: Int?
    get() =
        when (this) {
            MusicVideoQuality.AUTO -> null
            MusicVideoQuality.P2160 -> 2160
            MusicVideoQuality.P1080 -> 1080
            MusicVideoQuality.P720 -> 720
            MusicVideoQuality.P480 -> 480
        }

enum class PlayerType {
    MODERN,
    VINTAGE,
    CLASSIC,
}

enum class MiniPlayerType {
    MODERN,
    VINTAGE,
    EXPRESSIVE,
}

enum class DensityScale(
    val value: Float,
) {
    LARGE(1.25f),
    SLIGHTLY_LARGE(1.15f),
    NATIVE(1f),
    SLIGHTLY_COMPACT(0.85f),
    COMPACT(0.75f),
    VERY_COMPACT(0.65f),
    ULTRA_COMPACT(0.55f),
    ;

    companion object {
        fun fromValue(value: Float): DensityScale = entries.find { it.value == value } ?: NATIVE
    }
}

enum class GridItemSize {
    BIG,
    SMALL,
}
