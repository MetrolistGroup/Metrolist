package com.metrolist.shared.model

enum class MusicVideoType {
    OMV,
    UGC,
    VIDEO,
    LIVE,
    ATV,
    PODCAST_EPISODE,
    UNKNOWN,
}

private val OMV_MARKERS = listOf("OMV", "OFFICIAL_SOURCE_MUSIC_VIDEO", "OFFICIAL_SOURCE")
private val UGC_MARKERS = listOf("UGC", "USER_GENERATED")
private val VIDEO_MARKERS = listOf("VIDEO", "VIDEOS")
private val LIVE_MARKERS = listOf("LIVE")
private val ATV_MARKERS = listOf("ATV")
private val PODCAST_MARKERS = listOf("PODCAST_EPISODE", "PODCAST")

fun parseMusicVideoType(raw: String?): MusicVideoType {
    val type = raw?.uppercase()?.trim().orEmpty()
    if (type.isBlank()) return MusicVideoType.UNKNOWN
    val normalized = type.removePrefix("MUSIC_VIDEO_TYPE_")
    if (OMV_MARKERS.any { it in normalized }) return MusicVideoType.OMV
    if (UGC_MARKERS.any { it in normalized }) return MusicVideoType.UGC
    if (ATV_MARKERS.any { it in normalized }) return MusicVideoType.ATV
    if (PODCAST_MARKERS.any { it in normalized }) return MusicVideoType.PODCAST_EPISODE
    if (LIVE_MARKERS.any { it in normalized }) return MusicVideoType.LIVE
    if (VIDEO_MARKERS.any { it == normalized || it in normalized }) return MusicVideoType.VIDEO
    return MusicVideoType.UNKNOWN
}

fun isVideoType(raw: String?): Boolean =
    when (parseMusicVideoType(raw)) {
        MusicVideoType.OMV, MusicVideoType.UGC, MusicVideoType.VIDEO, MusicVideoType.LIVE -> true
        else -> false
    }

fun isLiveType(raw: String?): Boolean = parseMusicVideoType(raw) == MusicVideoType.LIVE

fun isOfficialVideoType(raw: String?): Boolean = parseMusicVideoType(raw) == MusicVideoType.OMV

fun isUploadedType(raw: String?): Boolean = raw?.uppercase()?.trim() == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"
