package com.metrolist.android.ui

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.metrolist.android.utils.startActivitySafely
import com.metrolist.shared.playlistimport.AmazonMusicPlaylistImporter
import io.ktor.http.Url

internal fun isTrustedLoginUrl(
    value: String,
    loginUrl: String,
): Boolean {
    val url = runCatching { Url(value) }.getOrNull() ?: return false
    if (url.protocol.name != "https" || url.port != 443 || url.user != null || url.password != null) return false
    val provider = runCatching { Url(loginUrl).host.lowercase() }.getOrNull() ?: return false
    val host = url.host.lowercase()
    val providerRoots = listOf("youtube.com", "spotify.com", "deezer.com", "tidal.com", "apple.com")
    val root =
        providerRoots.firstOrNull { provider == it || provider.endsWith(".$it") }
            ?: AmazonMusicPlaylistImporter.marketplaceHosts.firstOrNull { it == provider }?.removePrefix("music.")
            ?: return false
    if (host == root || host.endsWith(".$root")) return true
    return when (root) {
        "youtube.com" -> {
            host in setOf("accounts.google.com", "myaccount.google.com", "consent.google.com", "www.google.com")
        }

        "spotify.com", "deezer.com", "tidal.com" -> {
            host in setOf("accounts.google.com", "appleid.apple.com", "account.apple.com", "www.facebook.com", "m.facebook.com")
        }

        else -> {
            false
        }
    }
}

internal fun openExternalLoginUrl(
    context: Context,
    url: String,
) {
    if (runCatching { Url(url).protocol.name }.getOrNull() != "https") return
    context.startActivitySafely(Intent(Intent.ACTION_VIEW, url.toUri()))
}
