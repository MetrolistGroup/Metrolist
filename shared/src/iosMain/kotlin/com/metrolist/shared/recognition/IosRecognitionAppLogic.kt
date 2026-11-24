package com.metrolist.shared.recognition

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL

@OptIn(ExperimentalForeignApi::class)
object IosRecognitionAppLogic {
    fun isRecognizeUrl(urlString: String): Boolean {
        val url = NSURL.URLWithString(urlString) ?: return false
        if (url.scheme?.lowercase() != "metrolist") return false
        val host = url.host?.lowercase().orEmpty()
        val path = url.path?.lowercase().orEmpty()
        return host == "recognize" || path == "/recognize"
    }
}
