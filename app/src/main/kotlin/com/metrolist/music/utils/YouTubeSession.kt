/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.innertube.utils.parseCookieString

/**
 * Whether [cookie] carries a YouTube session this device can write with.
 *
 * A stored cookie can be present and still be worthless: `SAPISID` is the value every request
 * signature is built from, and a session without it is refused for anything that writes. Asking
 * only whether the cookie is non-empty is what lets a playlist be marked for syncing by a session
 * that can never upload it.
 */
fun isSignedInToYouTube(cookie: String?): Boolean {
    if (cookie.isNullOrEmpty()) return false
    return runCatching { "SAPISID" in parseCookieString(cookie) }.getOrDefault(false)
}
