package com.metrolist.shared.playback.ffmpeg

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

internal object DirectMediaRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        repeat(6) {
            val url = request.url
            if (!isTrustedDirectMediaEndpoint(url)) {
                throw IOException("Direct media redirect is not a trusted HTTPS endpoint")
            }
            val response = chain.proceed(request)
            if (!response.isRedirect) return response
            val target = response.header("Location")?.let(url::resolve) ?: return response
            response.close()
            request = request.newBuilder().url(target).build()
        }
        throw IOException("Direct media exceeded the redirect limit")
    }
}

internal fun isTrustedDirectMediaEndpoint(url: HttpUrl): Boolean =
    url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        (
            url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com") ||
                url.host == "youtube.com" || url.host.endsWith(".youtube.com")
        ) &&
        url.encodedPath == "/videoplayback"
