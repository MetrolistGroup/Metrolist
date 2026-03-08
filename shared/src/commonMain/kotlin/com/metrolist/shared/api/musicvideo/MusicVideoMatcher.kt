package com.metrolist.shared.api.musicvideo

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.MusicVideoType
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isOfficialMusicVideo
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.model.parseMusicVideoType
import kotlin.math.abs

object MusicVideoMatcher {
    private val TITLE_SUFFIX_PATTERN = Regex("""^(?:19|20)\d{2}|pt\s*\d+|part\s*\d+|vol\s*\d+$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SEPARATOR_PATTERN = Regex("""^(?:\s*:\s*|\s+[-–—~|/•]\s*|\s*[/|•]\s*)""")

    fun findBestTrackMatch(
        video: SongItem,
        candidates: List<YTItem>,
    ): SongItem? {
        val tracks = candidates.filterIsInstance<SongItem>().filterNot { it.isVideoSong }.filter { it.id != video.id }
        val artistIds = video.artists.mapNotNull { it.id }.toSet()
        val artistNames =
            video.artists
                .map { normalizeName(it.name) }
                .filter { it.isNotBlank() }
                .toSet()
        val titleMatches = tracks.filter { candidate -> titleMatches(video.title, candidate.title, artistNames) }
        val eligibleTracks = titleMatches.ifEmpty { return null }
        val artistMatches =
            if (artistIds.isNotEmpty()) {
                eligibleTracks.filter { it.sharesArtistId(artistIds) }.ifEmpty { nameMatching(eligibleTracks, artistNames) }
            } else if (artistNames.isNotEmpty()) {
                nameMatching(eligibleTracks, artistNames)
            } else {
                eligibleTracks
            }
        val pool = artistMatches.ifEmpty { if (artistNames.isEmpty()) eligibleTracks else emptyList() }
        if (pool.isEmpty()) return null

        return pool
            .mapNotNull { candidate ->
                val score =
                    scoreCandidate(video, candidate, artistNames) +
                        if (parseMusicVideoType(candidate.musicVideoType) == MusicVideoType.ATV) SCORE_AUDIO_TRACK else 0
                if (score > 0) candidate to score else null
            }.maxByOrNull { it.second }
            ?.first
    }

    fun findBestMatch(
        track: SongItem,
        candidates: List<YTItem>,
    ): SongItem? {
        val videos =
            candidates
                .filterIsInstance<SongItem>()
                .filter { it.isVideoSong }
                .filter { durationCompatible(track.duration, it.duration) }

        return findBestInPool(track, videos)
    }

    private fun findBestInPool(
        track: SongItem,
        candidates: List<SongItem>,
    ): SongItem? {
        if (candidates.isEmpty()) return null

        val trackArtistIds = track.artists.mapNotNull { it.id }.toSet()
        val trackArtistNames =
            track.artists
                .map { normalizeName(it.name) }
                .filter { it.isNotBlank() }
                .toSet()

        val eligible =
            candidates.filter { candidate ->
                if (isDisqualifiedVariant(track.title, candidate.title)) {
                    return@filter false
                }

                val titleMatches = titleMatches(candidate.title, track.title, trackArtistNames)
                if (!titleMatches) return@filter false

                val artistMetadataMatches =
                    if (trackArtistIds.isNotEmpty()) {
                        candidate.sharesArtistId(trackArtistIds)
                    } else if (trackArtistNames.isNotEmpty()) {
                        candidateMatchesTrackArtist(candidate.artists, trackArtistNames)
                    } else {
                        true
                    }

                val artistInTitle =
                    trackArtistNames.any { artist ->
                        artist.length >= MIN_ARTIST_NAME_LENGTH && normalizeTitle(candidate.title).contains(artist)
                    }
                val isOfficial = candidate.isOfficialMusicVideo
                val coreTrack = cleanCoreTitle(track.title)
                val coreCandidate = cleanCoreTitle(candidate.title)
                val isCloseTitle =
                    coreCandidate == coreTrack ||
                        normalizeTitle(candidate.title) == normalizeTitle(track.title) ||
                        levenshteinSimilarity(coreCandidate, coreTrack) >= LEVENSHTEIN_TITLE_THRESHOLD

                val highConfidence =
                    titleMatches &&
                        (
                            (artistMetadataMatches && (isOfficial || isCloseTitle)) ||
                                artistInTitle
                        )

                highConfidence
            }

        return eligible
            .mapNotNull { candidate ->
                val score = scoreCandidate(track, candidate, trackArtistNames, applyArtistBonus = false)
                if (score > 0) candidate to score else null
            }.maxByOrNull { it.second }
            ?.first
    }

    private val BRACKETED_DECORATOR_PATTERN =
        Regex(
            """[\(\[\{]\s*(?:official\s+music\s+video|official\s+video|official\s+lyric\s+video|official\s+audio|official\s+4k\s+video|official\s+hd\s+video|official\s+visualizer|official\s+visualiser|music\s+video|lyric\s+video|lyrics\s+video|full\s+video|clip\s+officiel|video\s+oficial|video\s+clip|videoclip|visualizer|visualiser|sing[- ]?along|singalong|audio\s+only|official|audio|video|mv|m/v|hd|hq|4k|1080p)\s*[\)\]\}]""",
            RegexOption.IGNORE_CASE,
        )
    private val DECORATOR_PATTERN =
        Regex(
            """\b(official\s+music\s+video|official\s+video|official\s+lyric\s+video|official\s+audio|official\s+4k\s+video|official\s+hd\s+video|official\s+visualizer|official\s+visualiser|music\s+video|lyric\s+video|lyrics\s+video|full\s+video|clip\s+officiel|video\s+oficial|video\s+clip|videoclip|visualizer|visualiser|sing[- ]?along|singalong|audio\s+only|official|mv|m/v|hd|hq|4k|1080p)\b""",
            RegexOption.IGNORE_CASE,
        )
    private val DELIMITER_REGEX = Regex("""\s+[-–—~]\s+|\s*[:|/•]\s*|\s*//\s*""")

    internal fun cleanCoreTitle(title: String): String {
        val withoutBracketDecorators = title.replace(BRACKETED_DECORATOR_PATTERN, " ").trim()
        val withoutDecorators = withoutBracketDecorators.replace(DECORATOR_PATTERN, " ").trim()
        val cleaned = withoutDecorators.ifBlank { withoutBracketDecorators.ifBlank { title } }
        return normalizeTitle(cleaned)
    }

    internal fun stripArtistPrefix(
        title: String,
        artistNames: Set<String>,
    ): String {
        for (artist in artistNames) {
            if (artist.isBlank()) continue
            val artistPattern = Regex("""^${Regex.escape(artist)}(?:\s*:\s*|\s+[-–—~|/•]\s*|\s*[/|•]\s*)""", RegexOption.IGNORE_CASE)
            val match = artistPattern.find(title)
            if (match != null) {
                return title.substring(match.range.last + 1).trim()
            }
            val normTitle = normalizeTitle(title)
            val normArtist = normalizeName(artist)
            if (normTitle.startsWith(normArtist)) {
                val after = title.drop(artist.length).trimStart()
                val sepPattern = ARTIST_SEPARATOR_PATTERN
                val sepMatch = sepPattern.find(after)
                if (sepMatch != null) {
                    return after.substring(sepMatch.range.last + 1).trim()
                }
            }
        }
        return title
    }

    internal fun titleMatches(
        videoTitle: String,
        trackTitle: String,
        artistNames: Set<String> = emptySet(),
    ): Boolean {
        val normVideo = normalizeTitle(videoTitle)
        val normTrack = normalizeTitle(trackTitle)
        if (normVideo.isBlank() || normTrack.length < MIN_TITLE_COMPARE_LENGTH) return false
        if (normVideo == normTrack) return true

        val strippedVideo = if (artistNames.isNotEmpty()) stripArtistPrefix(videoTitle, artistNames) else videoTitle
        val coreTrack = cleanCoreTitle(trackTitle)
        val coreVideo = cleanCoreTitle(strippedVideo)

        if (coreTrack.isNotBlank() && coreVideo.isNotBlank()) {
            if (coreTrack == coreVideo) return true
            if (normVideo == coreTrack || normalizeTitle(strippedVideo) == coreTrack) return true

            if (coreVideo.startsWith(coreTrack)) {
                val remainder = coreVideo.removePrefix(coreTrack).trim()
                if (remainder.isBlank() ||
                    remainder.all { it.isDigit() || it.isWhitespace() } ||
                    remainder.matches(TITLE_SUFFIX_PATTERN)
                ) {
                    return true
                }
            }

            val sim = levenshteinSimilarity(coreTrack, coreVideo)
            if (sim >= LEVENSHTEIN_TITLE_THRESHOLD) {
                return true
            }

            val segments = splitTitleSegments(videoTitle)
            for (segment in segments) {
                val coreSegment = cleanCoreTitle(segment)
                if (coreSegment.isNotBlank()) {
                    if (coreSegment == coreTrack) return true
                    if (coreSegment.startsWith(coreTrack)) {
                        val remainder = coreSegment.removePrefix(coreTrack).trim()
                        if (remainder.isBlank() ||
                            remainder.all { it.isDigit() || it.isWhitespace() } ||
                            remainder.matches(TITLE_SUFFIX_PATTERN)
                        ) {
                            return true
                        }
                    }
                    if (levenshteinSimilarity(coreSegment, coreTrack) >= LEVENSHTEIN_TITLE_THRESHOLD) {
                        return true
                    }
                }
            }

            if (coreTrack.startsWith(coreVideo) &&
                coreVideo.length >= 6 &&
                coreVideo.length.toDouble() / coreTrack.length >= 0.6
            ) {
                return true
            }
        }

        return false
    }

    private fun splitTitleSegments(title: String): List<String> = title.split(DELIMITER_REGEX).map { it.trim() }.filter { it.isNotBlank() }

    internal fun isDisqualifiedVariant(
        trackTitle: String,
        candidateTitle: String,
    ): Boolean {
        val normTrack = normalizeTitle(trackTitle)
        val normCandidate = normalizeTitle(candidateTitle)
        for (pattern in DISQUALIFYING_VARIANT_PATTERNS) {
            if (!pattern.containsMatchIn(normTrack) && pattern.containsMatchIn(normCandidate)) return true
        }
        return false
    }

    private fun nameMatching(
        videos: List<SongItem>,
        trackArtistNames: Set<String>,
    ): List<SongItem> =
        videos.filter { candidate ->
            candidateMatchesTrackArtist(candidate.artists, trackArtistNames) ||
                trackArtistNames.any { artist ->
                    artist.length >= MIN_ARTIST_NAME_LENGTH && normalizeTitle(candidate.title).contains(artist)
                }
        }

    internal fun candidateMatchesTrackArtist(
        candidateArtists: List<ArtistTiny>,
        trackArtistNames: Set<String>,
    ): Boolean {
        if (candidateArtists.isEmpty() || trackArtistNames.isEmpty()) return false
        return candidateArtists.any { artist ->
            val normalized = normalizeName(artist.name)
            if (normalized.isBlank()) return@any false
            trackArtistNames.any { trackName ->
                if (trackName.isBlank()) return@any false
                normalized == trackName ||
                    (
                        normalized.length >= MIN_ARTIST_NAME_LENGTH &&
                            trackName.length >= MIN_ARTIST_NAME_LENGTH &&
                            (
                                normalized.contains(trackName) ||
                                    trackName.contains(normalized) ||
                                    levenshteinSimilarity(normalized, trackName) >= LEVENSHTEIN_ARTIST_THRESHOLD
                            )
                    )
            }
        }
    }

    private fun SongItem.sharesArtistId(trackArtistIds: Set<String>): Boolean =
        artists.any { artist -> artist.id != null && artist.id in trackArtistIds }

    internal fun normalizeTitle(title: String): String = title.lowercase().replace(NON_WORD_PATTERN, " ").trim()

    internal fun normalizeName(name: String): String = normalizeTitle(name)

    internal fun levenshteinDistance(
        s1: String,
        s2: String,
    ): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[j] =
                    minOf(
                        dp[j] + 1,
                        dp[j - 1] + 1,
                        prev + cost,
                    )
                prev = temp
            }
        }
        return dp[s2.length]
    }

    internal fun levenshteinSimilarity(
        s1: String,
        s2: String,
    ): Double {
        if (s1 == s2) return 1.0
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val distance = levenshteinDistance(s1, s2)
        return 1.0 - (distance.toDouble() / maxLen)
    }

    private fun scoreCandidate(
        track: SongItem,
        candidate: SongItem,
        trackArtistNames: Set<String>,
        applyArtistBonus: Boolean = true,
    ): Int {
        var score = 0

        val isLookingForVideo = track.isVideoSong == false && candidate.isVideoSong

        when (parseMusicVideoType(candidate.musicVideoType)) {
            MusicVideoType.OMV -> {
                score += SCORE_OFFICIAL_VIDEO
            }

            MusicVideoType.ATV -> {
                score += if (isLookingForVideo) SCORE_STATIC_ART_TRACK_PENALTY else SCORE_GENERIC_VIDEO
            }

            MusicVideoType.VIDEO, MusicVideoType.UGC -> {
                score += SCORE_GENERIC_VIDEO
            }

            else -> {}
        }

        val trackTitle = normalizeTitle(track.title)
        val candidateTitle = normalizeTitle(candidate.title)
        val strippedCandidateTitle = cleanCoreTitle(stripArtistPrefix(candidate.title, trackArtistNames))
        val coreTrackTitle = cleanCoreTitle(track.title)

        if (trackTitle.isNotBlank()) {
            val titleSim =
                maxOf(
                    levenshteinSimilarity(coreTrackTitle, cleanCoreTitle(candidate.title)),
                    levenshteinSimilarity(coreTrackTitle, strippedCandidateTitle),
                    levenshteinSimilarity(trackTitle, candidateTitle),
                )

            when {
                candidateTitle == trackTitle ||
                    cleanCoreTitle(candidate.title) == coreTrackTitle ||
                    strippedCandidateTitle == coreTrackTitle ||
                    titleSim >= 0.95 -> {
                    score += SCORE_EXACT_TITLE
                }

                candidateTitle.contains(trackTitle) || titleSim >= 0.80 -> {
                    score += SCORE_TITLE_CONTAINS_TRACK
                }

                (candidateTitle.length >= MIN_TITLE_COMPARE_LENGTH && trackTitle.contains(candidateTitle)) ||
                    titleSim >= 0.70 -> {
                    score += SCORE_TRACK_CONTAINS_TITLE
                }
            }
        }

        if (durationCloseEnough(track.duration, candidate.duration)) {
            score += SCORE_DURATION_CLOSE
        }

        if (applyArtistBonus && candidateMatchesTrackArtist(candidate.artists, trackArtistNames)) {
            score += SCORE_CANDIDATE_ARTIST_MATCHES_TRACK
        }

        if (NON_OFFICIAL_VARIANT_PATTERN.containsMatchIn(candidate.title) &&
            !NON_OFFICIAL_VARIANT_PATTERN.containsMatchIn(track.title)
        ) {
            score += SCORE_NON_OFFICIAL_PENALTY
        }

        if (candidate.id == track.id) {
            score += SCORE_EXACT_ID_MATCH
        }

        return score
    }

    private fun durationCloseEnough(
        trackDuration: Long?,
        candidateDuration: Long?,
    ): Boolean {
        if (trackDuration == null || candidateDuration == null || trackDuration <= 0L || candidateDuration <= 0L) {
            return false
        }
        return abs(trackDuration - candidateDuration) <= DURATION_TOLERANCE_SECONDS
    }

    private const val SCORE_EXACT_ID_MATCH = 10
    private const val SCORE_OFFICIAL_VIDEO = 4
    private const val SCORE_AUDIO_TRACK = 4
    private const val SCORE_GENERIC_VIDEO = 1
    private const val SCORE_STATIC_ART_TRACK_PENALTY = -5
    private const val SCORE_EXACT_TITLE = 3
    private const val SCORE_TITLE_CONTAINS_TRACK = 2
    private const val SCORE_TRACK_CONTAINS_TITLE = 1
    private const val SCORE_DURATION_CLOSE = 2
    private const val SCORE_CANDIDATE_ARTIST_MATCHES_TRACK = 3
    private const val SCORE_NON_OFFICIAL_PENALTY = -10
    private const val DURATION_TOLERANCE_SECONDS = 30L

    private const val MAX_VIDEO_DURATION_RATIO = 2L
    private const val MAX_VIDEO_DURATION_DELTA_SECONDS = 90L

    private fun durationCompatible(
        trackDuration: Long?,
        candidateDuration: Long?,
    ): Boolean {
        if (trackDuration == null || candidateDuration == null || trackDuration <= 0L || candidateDuration <= 0L) return true
        return candidateDuration <= trackDuration * MAX_VIDEO_DURATION_RATIO ||
            candidateDuration - trackDuration <= MAX_VIDEO_DURATION_DELTA_SECONDS
    }

    private const val MIN_TITLE_COMPARE_LENGTH = 3
    private const val MIN_ARTIST_NAME_LENGTH = 3
    private const val LEVENSHTEIN_TITLE_THRESHOLD = 0.75
    private const val LEVENSHTEIN_ARTIST_THRESHOLD = 0.80

    private val NON_WORD_PATTERN = Regex("[^\\p{L}\\p{N}]+")

    private val DISQUALIFYING_VARIANTS =
        listOf(
            "cover",
            "remix",
            "mashup",
            "medley",
            "karaoke",
            "instrumental",
            "tribute",
            "parody",
            "reaction",
            "tutorial",
            "piano tutorial",
            "guitar tutorial",
            "how to play",
            "lesson",
            "ai cover",
            "fan made",
            "fan-made",
            "fanmade",
            "slowed",
            "sped up",
            "sped-up",
            "speed up",
            "nightcore",
            "8d audio",
            "8d",
            "gameplay",
            "walkthrough",
            "teaser",
            "trailer",
            "behind the scenes",
            "synthesia",
        )

    private val DISQUALIFYING_VARIANT_PATTERNS =
        DISQUALIFYING_VARIANTS.map { variant ->
            val word = normalizeTitle(variant)
            Regex("""(?:\b|\s|^)${Regex.escape(word)}(?:\b|\s|$)""", RegexOption.IGNORE_CASE)
        }

    private val NON_OFFICIAL_VARIANT_PATTERN =
        Regex(
            """\b(remix|remaster|cover|karaoke|instrumental|acapella|a cappella|reverb|slowed|sped[ -_]?up|bass[ -_]?boosted|nightcore|tutorial|lyrics?|lyric[ -]?video|lyrics[ -]?video|loop|8d|8d audio|extended|extended[ -]?version|new[ -]?version|alternate[ -]?version|re[ -]?recorded|rerecorded|remake|re[ -]?mastered|\d{4}[ -]?version|deluxe[ -]?version|ai|live|live[ -]?performance|live[ -]?session|acoustic|visuali[sz]er|audio[ -]?only|fan[ -]?made|reaction|behind[ -]?the[ -]?scenes|trailer|teaser|from[ -]?the[ -]?album|10[ -]?hours|1[ -]?hour|compilation|mashup|mega[ -]?mix|medley)\b""",
            RegexOption.IGNORE_CASE,
        )
}
