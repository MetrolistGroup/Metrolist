package com.metrolist.shared.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

suspend fun HttpResponse.bodyAsTextLimited(maxBytes: Int): String? = bodyAsBytesLimited(maxBytes)?.decodeToString()

suspend fun HttpResponse.bodyAsBytesLimited(maxBytes: Int): ByteArray? {
    require(maxBytes > 0)
    val channel = bodyAsChannel()
    try {
        if (headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { it > maxBytes } == true) return null
        // Read at most one byte past the limit without preallocating maxBytes (up to 16 MiB per request).
        val bytes = channel.readBuffer(maxBytes + 1L).readByteArray()
        if (bytes.size > maxBytes) return null
        return bytes
    } finally {
        channel.cancel(null)
    }
}

internal data class TextResponse(
    val status: HttpStatusCode,
    val headers: Headers,
    val body: String?,
)

// Reading within execute is essential: Ktor's ordinary get/post eagerly buffer the entire body.
internal suspend fun HttpStatement.readTextLimited(maxBytes: Int): TextResponse =
    execute { response -> TextResponse(response.status, response.headers, response.bodyAsTextLimited(maxBytes)) }
