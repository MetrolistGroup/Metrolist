/**
 * Legacy 7xTune Speed Dial implementation kept isolated while the personal engine is used.
 */
package com.metrolist.music.viewmodels

import com.metrolist.innertube.models.YTItem

@Deprecated("Legacy Speed Dial composition. Use buildPersonalSpeedDialItems instead.")
internal fun buildLegacySpeedDialItems(
    pinned: List<YTItem>,
    recent: List<YTItem>,
    frequent: List<YTItem>,
    resume: List<YTItem>,
    favorites: List<YTItem>,
): List<YTItem> =
    (pinned + recent.take(8) + frequent.take(7) + resume.take(5) + favorites.take(6))
        .distinctBy { it.id }
        .take(27)
