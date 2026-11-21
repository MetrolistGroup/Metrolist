package com.metrolist.android.playback

import android.content.ContentResolver
import android.net.Uri

internal class CurrentSessionArtwork(
    private val authority: String?,
    private var connected: Boolean,
) {
    var revision = 0L
        private set

    fun updateConnectivity(connected: Boolean) {
        if (connected && !this.connected) revision++
        this.connected = connected
    }

    fun uriForMetadata(uri: Uri): Uri =
        if (revision > 0 && authority != null && uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == authority) {
            // Media3 caches a failed bitmap request until its URI changes.
            uri.buildUpon().appendQueryParameter("reconnect", revision.toString()).build()
        } else {
            uri
        }
}
