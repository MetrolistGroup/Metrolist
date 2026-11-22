package com.metrolist.desktop.backup

import com.metrolist.shared.backup.DriveAccountConnectionState
import com.metrolist.shared.backup.DriveClientPlatform
import com.metrolist.shared.backup.DriveTokenBrokerClient
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object DesktopDriveSignIn {
    fun isConfigured(): Boolean = brokerUrl() != null

    suspend fun connect() =
        withContext(Dispatchers.IO) {
            DriveAccountConnectionState.clearSignInFailure()
            val brokerUrl = requireBrokerUrl()
            val broker = DriveTokenBrokerClient(brokerUrl)
            val configuration =
                try {
                    broker.configuration()
                } finally {
                    broker.close()
                }
            val state = randomUrlSafe(32)
            val verifier = randomUrlSafe(64)
            val challenge =
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
                )
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            server.soTimeout = CALLBACK_POLL_INTERVAL_MILLIS
            val port = server.localPort
            val redirectUri = "http://127.0.0.1:$port/oauth2callback"
            val authorizationCode = CompletableFuture<String>()
            val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
            val listener =
                thread(isDaemon = true) {
                    while (!authorizationCode.isDone) {
                        try {
                            server.accept().use { socket ->
                                if (!activeSockets.add(socket)) return@use
                                try {
                                    socket.soTimeout = SOCKET_READ_TIMEOUT_MILLIS
                                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                                    val requestLine = reader.readLine().orEmpty()
                                    while (true) {
                                        if (reader.readLine().isNullOrEmpty()) break
                                    }
                                    val rawQuery = requestLine.substringAfter(' ', "").substringBefore(' ').substringAfter('?', "")
                                    val parameters =
                                        rawQuery
                                            .split('&')
                                            .mapNotNull { item ->
                                                val pair = item.split('=', limit = 2)
                                                pair.firstOrNull()?.let { key ->
                                                    URLDecoder.decode(key, StandardCharsets.UTF_8) to
                                                        URLDecoder.decode(pair.getOrElse(1) { "" }, StandardCharsets.UTF_8)
                                                }
                                            }.toMap()
                                    if (requestLine.substringAfter(' ', "").startsWith("/oauth2callback?") &&
                                        parameters["state"] == state
                                    ) {
                                        parameters["code"]?.takeIf(String::isNotBlank)?.let(authorizationCode::complete)
                                            ?: authorizationCode.completeExceptionally(
                                                IllegalStateException("Google authorization was denied"),
                                            )
                                    }
                                    val status = if (authorizationCode.isDone) "204 No Content" else "400 Bad Request"
                                    socket.getOutputStream().write(
                                        "HTTP/1.1 $status\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(),
                                    )
                                } finally {
                                    activeSockets.remove(socket)
                                }
                            }
                        } catch (_: SocketTimeoutException) {
                            continue
                        } catch (error: SocketException) {
                            if (!server.isClosed) authorizationCode.completeExceptionally(error)
                            return@thread
                        }
                    }
                }
            try {
                val query =
                    mapOf(
                        "client_id" to configuration.desktopClientId,
                        "redirect_uri" to redirectUri,
                        "response_type" to "code",
                        "scope" to "openid email https://www.googleapis.com/auth/drive.file",
                        "access_type" to "offline",
                        "prompt" to "consent",
                        "state" to state,
                        "code_challenge" to challenge,
                        "code_challenge_method" to "S256",
                    ).entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
                check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    "A system browser is required to connect Google Drive"
                }
                Desktop.getDesktop().browse(URI("https://accounts.google.com/o/oauth2/v2/auth?$query"))
                val code = authorizationCode.get(3, TimeUnit.MINUTES)
                val exchangeBroker = DriveTokenBrokerClient(brokerUrl)
                val session =
                    try {
                        exchangeBroker.exchangeCode(DriveClientPlatform.DESKTOP, code, redirectUri, verifier)
                    } finally {
                        exchangeBroker.close()
                    }
                Settings.shared.driveBrokerSession = session.sessionToken
                DriveAccountConnectionState.update(session.sessionToken)
                Settings.shared.flush()
            } finally {
                authorizationCode.completeExceptionally(IllegalStateException("Google sign-in ended before a callback arrived"))
                activeSockets.forEach { socket -> runCatching { socket.close() } }
                server.close()
                listener.join(CALLBACK_SHUTDOWN_TIMEOUT_MILLIS)
            }
        }

    suspend fun disconnect() =
        withContext(Dispatchers.IO) {
            val session = Settings.shared.driveBrokerSession
            val broker = DriveTokenBrokerClient(requireBrokerUrl())
            try {
                if (session.isNotBlank()) broker.revoke(session)
                DriveAccountConnectionState.clearIfCurrent(session)
            } finally {
                broker.close()
            }
        }

    private fun brokerUrl(): String? = System.getenv(DRIVE_BROKER_URL_ENV)?.takeIf { it.startsWith("https://") }

    internal fun requireBrokerUrl(): String = brokerUrl() ?: error("Google Drive broker is not configured")

    private fun randomUrlSafe(byteCount: Int): String =
        ByteArray(byteCount).also(SecureRandom()::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private const val DRIVE_BROKER_URL_ENV = "METROLIST_DRIVE_BROKER_URL"
    private const val CALLBACK_POLL_INTERVAL_MILLIS = 1_000
    private const val SOCKET_READ_TIMEOUT_MILLIS = 2_000
    private const val CALLBACK_SHUTDOWN_TIMEOUT_MILLIS = 1_000L
}
