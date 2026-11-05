/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Range: bytes=0-` to stream requests that carry no range at all.
 *
 * googlevideo paces requests without a Range header to roughly the stream bitrate, while any ranged
 * request is served at full speed. media3 omits the header for reads that start at 0 with an
 * unknown length, which is how every uncached song starts. This runs on the outgoing request, after
 * the cache layers have picked the real position, so it never adds a second range.
 */
internal object OpenRangeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET" ||
            request.header("Range") != null ||
            request.url.queryParameter("range") != null
        ) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("Range", "bytes=0-").build())
    }
}
