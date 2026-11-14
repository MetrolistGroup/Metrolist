package com.metrolist.shared.cast

import com.metrolist.shared.network.PublicArtworkDns
import com.metrolist.shared.network.hasHttpTransportFailureInCauseChain
import com.metrolist.shared.network.sharedHttpTransportHandlesFailures
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import okhttp3.Call
import okhttp3.Request
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class ByteRange(
    val start: Long,
    val endInclusive: Long,
) {
    val length: Long
        get() = endInclusive - start + 1L
}

internal fun parseByteRange(
    value: String?,
    contentLength: Long,
): ByteRange? {
    if (value == null || contentLength <= 0L) return null
    val match = Regex("""bytes=(\d*)-(\d*)""", RegexOption.IGNORE_CASE).matchEntire(value.trim()) ?: return null
    val startText = match.groupValues[1]
    val endText = match.groupValues[2]
    if (startText.isEmpty()) {
        val suffixLength = endText.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val start = (contentLength - suffixLength).coerceAtLeast(0L)
        return ByteRange(start, contentLength - 1L)
    }
    val start = startText.toLongOrNull()?.takeIf { it < contentLength } ?: return null
    val end = endText.toLongOrNull()?.coerceAtMost(contentLength - 1L) ?: (contentLength - 1L)
    return end.takeIf { it >= start }?.let { ByteRange(start, it) }
}

internal fun runCastProxyWorker(
    isClosed: () -> Boolean = { false },
    block: () -> Unit,
) {
    try {
        block()
    } catch (error: Exception) {
        val kind = error.javaClass.simpleName
        val transportFailureLogged = sharedHttpTransportHandlesFailures && error.hasHttpTransportFailureInCauseChain()
        if (
            isClosed() ||
            error is SocketException ||
            error is SocketTimeoutException ||
            error is InterruptedException ||
            transportFailureLogged
        ) {
            Logger.d("CastProxy", "Client worker stopped ($kind)")
        } else {
            // Exception messages can contain signed upstream URLs or the proxy token.
            PlaybackEventLog.error(
                PlaybackEventKind.NETWORK,
                "CastProxy",
                "Client worker failed ($kind)",
                error,
            )
            Logger.w("CastProxy", "Client worker failed ($kind)")
        }
    }
}

internal class CastProxyServer(
    private val receiverAddress: InetAddress,
    private val localAddress: InetAddress,
    private val media: CastMedia,
    private val writeTimeoutMillis: Long = 30_000L,
) : Closeable {
    private val upstreamUrl = validatedGoogleVideoUrl(media.resource.url)
    private val contentLength = requireNotNull(media.resource.contentLengthBytes).also { require(it > 0L) }
    private val token = ByteArray(24).also(SecureRandom()::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
    private val server =
        runCatching { ServerSocket(0, PROXY_BACKLOG, localAddress) }
            .getOrElse { ServerSocket(0, PROXY_BACKLOG) }
    private val workers =
        ThreadPoolExecutor(
            4,
            4,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(PROXY_BACKLOG),
            { runnable -> Thread(runnable, "cast-media-proxy").apply { isDaemon = true } },
        )
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val client =
        sharedOkHttpClient
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(PublicArtworkDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    @Volatile
    private var closed = false

    val port: Int
        get() = server.localPort

    private val acceptThread =
        thread(name = "cast-media-accept", isDaemon = true) {
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                if (!isAllowedClient(socket.inetAddress)) {
                    runCatching { socket.close() }
                    continue
                }
                sockets.add(socket)
                if (closed) {
                    closeClient(socket)
                    break
                }
                try {
                    workers.execute {
                        try {
                            runCastProxyWorker(isClosed = { closed }) { handle(socket) }
                        } finally {
                            closeClient(socket)
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    closeClient(socket)
                }
            }
        }

    fun mediaUrl(): String {
        val rawHost = requireNotNull(localAddress.hostAddress).substringBefore('%')
        val host = if (rawHost.contains(':')) "[$rawHost]" else rawHost
        return "http://$host:$port/media/$token"
    }

    private fun isAllowedClient(clientAddress: InetAddress): Boolean {
        if (clientAddress == receiverAddress) return true
        if (clientAddress.hostAddress?.substringBefore('%') == receiverAddress.hostAddress?.substringBefore('%')) return true
        return isLocalNetworkAddress(clientAddress)
    }

    private fun handle(socket: Socket) {
        socket.use {
            if (!isAllowedClient(it.inetAddress)) {
                writeError(it, 403, "Forbidden")
                return
            }
            it.soTimeout = 15_000
            val input = BufferedInputStream(it.getInputStream())
            val requestLine = readAsciiLine(input, 8_192) ?: return
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0)
            val path = parts.getOrNull(1)?.substringBefore('?')
            if ((method != "GET" && method != "HEAD") || path != "/media/$token") {
                writeError(it, 404, "Not Found")
                return
            }
            val headers = linkedMapOf<String, String>()
            var totalHeaderBytes = requestLine.length
            while (true) {
                val line = readAsciiLine(input, 8_192) ?: return
                totalHeaderBytes += line.length
                if (totalHeaderBytes > 32_768) {
                    writeError(it, 431, "Request Header Fields Too Large")
                    return
                }
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }

            val requestedRange = headers["range"]
            val range = if (requestedRange == null) ByteRange(0L, contentLength - 1L) else parseByteRange(requestedRange, contentLength)
            if (range == null) {
                writeRangeNotSatisfiable(it)
                return
            }
            if (method == "HEAD") {
                writeHeaders(it, if (requestedRange == null) 200 else 206, range, requestedRange != null)
                return
            }
            proxyRange(it, range, requestedRange != null)
        }
    }

    private fun proxyRange(
        socket: Socket,
        range: ByteRange,
        partialResponse: Boolean,
    ) {
        val builder =
            Request
                .Builder()
                .url(upstreamUrl)
                .get()
                .header("Range", "bytes=${range.start}-${range.endInclusive}")
        media.resource.headers.forEach { (name, value) ->
            if (!name.equals("Range", ignoreCase = true)) builder.header(name, value)
        }
        val call = client.newCall(builder.build())
        calls.add(call)
        try {
            if (closed) {
                call.cancel()
                return
            }
            call.execute().use { response ->
                val body = response.body
                if (response.code != 206 && !(response.code == 200 && range.start == 0L)) {
                    PlaybackEventLog.error(
                        PlaybackEventKind.NETWORK,
                        "CastProxy",
                        "Upstream rejected range request",
                        IllegalStateException("HTTP ${response.code}"),
                    )
                    Logger.w("CastProxy", "Upstream rejected range request (HTTP ${response.code})")
                    writeError(socket, 502, "Bad Gateway")
                    return
                }
                socket.soTimeout = 0
                val output = downstreamSink(socket)
                writeHeaders(output, if (partialResponse) 206 else 200, range, partialResponse)
                body.byteStream().use { source ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = range.length
                    while (remaining > 0L) {
                        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                    output.flush()
                }
            }
        } finally {
            calls.remove(call)
        }
    }

    private fun downstreamSink(socket: Socket): BufferedSink =
        socket.sink().apply { timeout().timeout(writeTimeoutMillis, TimeUnit.MILLISECONDS) }.buffer()

    private fun writeHeaders(
        socket: Socket,
        status: Int,
        range: ByteRange,
        partial: Boolean,
    ) = writeHeaders(downstreamSink(socket), status, range, partial)

    private fun writeHeaders(
        output: BufferedSink,
        status: Int,
        range: ByteRange,
        partial: Boolean,
    ) {
        val reason = if (status == 206) "Partial Content" else "OK"
        val contentType = requireNotNull(media.resource.mimeType).substringBefore(';').trim()
        val headers =
            buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${range.length}\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Connection: close\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                if (partial) append("Content-Range: bytes ${range.start}-${range.endInclusive}/$contentLength\r\n")
                append("\r\n")
            }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun writeRangeNotSatisfiable(socket: Socket) {
        val output = downstreamSink(socket)
        output.write(
            "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$contentLength\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.US_ASCII),
        )
        output.flush()
    }

    private fun writeError(
        socket: Socket,
        status: Int,
        reason: String,
    ) {
        val output = downstreamSink(socket)
        output.write("HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun closeClient(socket: Socket) {
        runCatching { socket.close() }
        sockets.remove(socket)
    }

    override fun close() {
        closed = true
        runCatching {
            server.close()
            // The JDK can defer closing the listener until the blocked accept call exits.
            if (Thread.currentThread() !== acceptThread) acceptThread.join(ACCEPT_THREAD_JOIN_TIMEOUT_MS)
        }
        workers.shutdownNow()
        sockets.forEach(::closeClient)
        calls.forEach(Call::cancel)
    }
}

private const val PROXY_BACKLOG = 8
private const val ACCEPT_THREAD_JOIN_TIMEOUT_MS = 2_000L

internal fun validatedGoogleVideoUrl(value: String): String {
    val uri = URI(value)
    val host = uri.host?.lowercase() ?: error("Missing media host")
    require(uri.scheme.equals("https", true))
    require(uri.userInfo == null && uri.fragment == null)
    require(uri.port == -1 || uri.port == 443)
    require(host.endsWith(".googlevideo.com"))
    require(uri.rawPath == "/videoplayback")
    return uri.toString()
}

private fun readAsciiLine(
    input: BufferedInputStream,
    maxLength: Int,
): String? {
    val bytes = ArrayList<Byte>()
    while (bytes.size <= maxLength) {
        val value = input.read()
        if (value < 0) return null
        if (value == '\n'.code) {
            if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
            return bytes.toByteArray().toString(Charsets.US_ASCII)
        }
        bytes += value.toByte()
    }
    return null
}
