package com.metrolist.sponsorblock.models

/**
 * The SponsorBlock segment categories that make sense for a music player.
 *
 * [apiName] is the identifier the SponsorBlock API expects; it is also what gets
 * persisted, so these strings must not change.
 *
 * See https://wiki.sponsor.ajay.app/w/Guidelines for what each category covers.
 */
enum class SponsorBlockCategory(val apiName: String) {
    /** Paid promotion, paid referrals and direct advertisements. */
    SPONSOR("sponsor"),

    /** Unpaid or self promotion: merch, donations, other creators. */
    SELF_PROMO("selfpromo"),

    /** A brief reminder to like, subscribe or follow. */
    INTERACTION("interaction"),

    /** Intro animation or pause with no content. */
    INTRO("intro"),

    /** Credits or an endcard, after the content has finished. */
    OUTRO("outro"),

    /** Recap or a preview of what is coming later. */
    PREVIEW("preview"),

    /** Tangential scenes added only as filler or humour. */
    FILLER("filler"),

    /**
     * Sections of a music video that are not music. Per the SponsorBlock
     * guidelines this exists independently of the other categories, which makes
     * it the single most useful one for a music app.
     */
    MUSIC_OFFTOPIC("music_offtopic"),
    ;

    companion object {
        fun fromApiName(apiName: String): SponsorBlockCategory? =
            entries.firstOrNull { it.apiName == apiName }
    }
}
