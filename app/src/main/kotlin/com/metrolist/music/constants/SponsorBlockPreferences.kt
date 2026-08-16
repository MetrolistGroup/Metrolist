/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.constants

import androidx.datastore.preferences.core.Preferences
import com.metrolist.sponsorblock.models.SponsorBlockCategory

/**
 * Binds a SponsorBlock category to the preference that controls it.
 *
 * Keeping one list means the player and the settings screen can never disagree
 * about which categories are active.
 */
data class SponsorBlockCategoryPreference(
    val category: SponsorBlockCategory,
    val key: Preferences.Key<Boolean>,
    val enabledByDefault: Boolean,
)

/**
 * Defaults lean conservative: only the categories that are unambiguously not
 * music are on out of the box. Everything else is opt-in, because on a music
 * video an aggressive skip is more disruptive than a few seconds of filler.
 */
val SponsorBlockCategoryPreferences = listOf(
    SponsorBlockCategoryPreference(
        category = SponsorBlockCategory.MUSIC_OFFTOPIC,
        key = SponsorBlockCategoryMusicOfftopicKey,
        enabledByDefault = true,
    ),
    SponsorBlockCategoryPreference(
        category = SponsorBlockCategory.SPONSOR,
        key = SponsorBlockCategorySponsorKey,
        enabledByDefault = true,
    ),
    SponsorBlockCategoryPreference(
        category = SponsorBlockCategory.SELF_PROMO,
        key = SponsorBlockCategorySelfPromoKey,
        enabledByDefault = true,
    ),
    SponsorBlockCategoryPreference(
        category = SponsorBlockCategory.INTERACTION,
        key = SponsorBlockCategoryInteractionKey,
        enabledByDefault = false,
    ),
    SponsorBlockCategoryPreference(
        category = SponsorBlockCategory.INTRO,
        key = SponsorBlockCategoryIntroKey,
        enabledByDefault = false,
    ),
    SponsorBlockCategoryPreference(
        category = SponsorBlockCategory.OUTRO,
        key = SponsorBlockCategoryOutroKey,
        enabledByDefault = false,
    ),
    SponsorBlockCategoryPreference(
        category = SponsorBlockCategory.PREVIEW,
        key = SponsorBlockCategoryPreviewKey,
        enabledByDefault = false,
    ),
    SponsorBlockCategoryPreference(
        category = SponsorBlockCategory.FILLER,
        key = SponsorBlockCategoryFillerKey,
        enabledByDefault = false,
    ),
)

/** Reads the categories the user currently has switched on. */
fun Preferences.enabledSponsorBlockCategories(): Set<SponsorBlockCategory> =
    SponsorBlockCategoryPreferences
        .filter { this[it.key] ?: it.enabledByDefault }
        .map { it.category }
        .toSet()
