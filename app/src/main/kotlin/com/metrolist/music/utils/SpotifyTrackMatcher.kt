package com.metrolist.music.utils

import com.metrolist.innertube.models.SongItem
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class SpotifyLikedTrack(val id: String, val title: String, val artists: List<String>, val duration: Int, val explicit: Boolean?)

object SpotifyTrackMatcher {
    private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(Regex("(?<=\\p{IsLatin})\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

    fun match(track: SpotifyLikedTrack, candidates: List<SongItem>): SongItem? = candidates
        .filter { candidate ->
            val duration = candidate.duration
            normalized(candidate.title) == normalized(track.title) &&
                track.artists.isNotEmpty() && candidate.artists.any { normalized(it.name) == normalized(track.artists.first()) } &&
                (track.explicit == null || candidate.explicit == track.explicit) &&
                (duration == null || track.duration <= 0 || abs(duration - track.duration) <= 12)
        }
        .minByOrNull { abs((it.duration ?: track.duration) - track.duration) }
}
