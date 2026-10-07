/**
 * 7xTune Project (C) 2026
 * Personal Speed Dial engine.
 *
 * The order intentionally mirrors the observed YouTube Music behaviour:
 * pinned first, then frequently played, liked, saved playlists, recent/resume.
 */
package com.metrolist.music.viewmodels

import com.metrolist.innertube.models.YTItem

internal fun buildPersonalSpeedDialItems(
    pinned: List<YTItem>,
    mostPlayed: List<YTItem>,
    liked: List<YTItem>,
    savedPlaylists: List<YTItem>,
    recent: List<YTItem>,
    resume: List<YTItem>,
    limit: Int = 27,
): List<YTItem> =
    (pinned + mostPlayed + liked + savedPlaylists + recent + resume)
        .distinctBy { it.id }
        .take(limit)
