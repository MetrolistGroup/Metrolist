package com.metrolist.music.utils

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SpotifyImportClient(private val context: Context) {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val tokenFile = File(context.noBackupFilesDir, "spotify-connection")
    private var tokens: JsonObject? = null
    private var expiresAt = 0L

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("metrolist-spotify-import", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder("metrolist-spotify-import", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun disconnect() {
        tokenFile.delete()
        tokens = null
        expiresAt = 0L
    }

    private fun saveTokens(value: JsonObject, clientId: String) {
        val refresh = value["refresh_token"] ?: tokens?.get("refresh_token")
        expiresAt = System.currentTimeMillis() + (value["expires_in"]?.jsonPrimitive?.long ?: 3600) * 1000
        tokens = buildJsonObject {
            value.forEach { (key, item) -> put(key, item) }
            refresh?.let { put("refresh_token", it) }
            put("client_id", clientId)
            put("expires_at", expiresAt)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        tokenFile.writeBytes(cipher.iv + cipher.doFinal(tokens.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun loadTokens(clientId: String) {
        if (tokens != null || !tokenFile.exists()) return
        try {
            val data = tokenFile.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            }
            val value = Json.parseToJsonElement(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)).jsonObject
            if (value["client_id"]?.jsonPrimitive?.content != clientId) { disconnect(); return }
            tokens = value
            expiresAt = value["expires_at"]?.jsonPrimitive?.long ?: 0
        } catch (_: Exception) { disconnect() }
    }

    private suspend fun request(request: Request): JsonObject {
        repeat(4) { attempt ->
            currentCoroutineContext().ensureActive()
            val result = http.newCall(request).awaitSpotifyJson(attempt)
            result.first?.let { return it }
            delay(result.second * 1000)
        }
        error("Spotify request failed")
    }

    private suspend fun accessToken(clientId: String): String {
        if (System.currentTimeMillis() >= expiresAt - 60_000) {
            val refresh = tokens?.get("refresh_token")?.jsonPrimitive?.content ?: throw SpotifyApiException(401)
            val updated = request(Request.Builder().url("https://accounts.spotify.com/api/token")
                .post(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).add("client_id", clientId).build()).build())
            saveTokens(updated, clientId)
        }
        return tokens?.get("access_token")?.jsonPrimitive?.content ?: throw SpotifyApiException(401)
    }

    suspend fun connect(clientId: String, openBrowser: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
        if (tokens != null && tokens?.get("client_id")?.jsonPrimitive?.content != clientId) disconnect()
        loadTokens(clientId)
        if (tokens != null) {
            try { accessToken(clientId); return@withContext } catch (error: SpotifyApiException) {
                if (error.status != 400 && error.status != 401) throw error
                disconnect()
            }
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun random() = encoder.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val verifier = random()
        val state = random()
        val challenge = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        // Spotify permits a dynamic port when http://127.0.0.1/callback is registered.
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 1000
            val redirect = "http://127.0.0.1:${server.localPort}/callback"
            val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
                .appendQueryParameter("client_id", clientId).appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", redirect).appendQueryParameter("scope", "user-library-read")
                .appendQueryParameter("state", state).appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge).build().toString()
            openBrowser(url)
            val deadline = System.currentTimeMillis() + 300_000
            var code: String? = null
            while (code == null) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() > deadline) throw SpotifyApiException(408)
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                socket.use {
                    it.soTimeout = 2000
                    val line = try { it.getInputStream().bufferedReader().readLine() } catch (_: SocketTimeoutException) { null }
                    val path = line?.split(' ')?.getOrNull(1)
                    val callback = path?.let { target -> Uri.parse("http://127.0.0.1$target") }
                    val callbackCode = callback?.getQueryParameter("code")?.takeIf { value -> value.isNotBlank() }
                    val callbackError = callback?.getQueryParameter("error")?.takeIf { value -> value.isNotBlank() }
                    val valid = line?.startsWith("GET ") == true && callback?.path == "/callback" &&
                        callback.getQueryParameter("state") == state && ((callbackCode != null) xor (callbackError != null))
                    val denied = valid && callbackError != null
                    if (valid) code = callbackCode
                    val message = context.getString(if (code != null) com.metrolist.music.R.string.spotify_browser_success else com.metrolist.music.R.string.spotify_browser_failed)
                    val body = "<!doctype html><meta name=viewport content='width=device-width'><p>$message</p>".toByteArray(Charsets.UTF_8)
                    it.getOutputStream().write("HTTP/1.1 ${if (valid) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII) + body)
                    if (denied) throw SpotifyApiException(401)
                }
            }
            val value = request(Request.Builder().url("https://accounts.spotify.com/api/token").post(FormBody.Builder()
                .add("grant_type", "authorization_code").add("code", code!!).add("redirect_uri", redirect)
                .add("client_id", clientId).add("code_verifier", verifier).build()).build())
            saveTokens(value, clientId)
        }
    }

    suspend fun likedTracks(clientId: String, page: suspend (List<SpotifyLikedTrack>, Int, Int) -> Unit) {
        var offset = 0
        val seen = mutableSetOf<String>()
        while (true) {
            val token = accessToken(clientId)
            val value = try {
                request(Request.Builder().url("https://api.spotify.com/v1/me/tracks?limit=50&offset=$offset")
                    .header("Authorization", "Bearer $token").build())
            } catch (error: SpotifyApiException) {
                if (error.status == 401) disconnect()
                throw error
            }
            val items = value["items"]?.jsonArray ?: error("Missing Spotify tracks")
            val tracks = items.mapNotNull { item ->
                val track = item.jsonObject["track"] as? JsonObject ?: return@mapNotNull null
                val id = track["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (!seen.add(id)) return@mapNotNull null
                SpotifyLikedTrack(id, track["name"]?.jsonPrimitive?.content.orEmpty(),
                    track["artists"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.orEmpty(),
                    (track["duration_ms"]?.jsonPrimitive?.intOrNull ?: 0) / 1000,
                    track["explicit"]?.jsonPrimitive?.booleanOrNull ?: false)
            }
            page(tracks, items.size - tracks.size, value["total"]?.jsonPrimitive?.intOrNull ?: (offset + items.size))
            if (items.isEmpty() || value["next"] == JsonNull || value["next"] == null) break
            offset += items.size
        }
    }
}

class SpotifyApiException(val status: Int) : Exception("Spotify HTTP $status")

internal suspend fun Call.awaitSpotifyJson(attempt: Int): Pair<JsonObject?, Long> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                try {
                    val result: Pair<JsonObject?, Long> = if (it.code == 429 && attempt < 3) {
                        null to (it.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 120) ?: 5L)
                    } else {
                        if (!it.isSuccessful) throw SpotifyApiException(it.code)
                        Json.parseToJsonElement(it.body?.string() ?: "{}").jsonObject to 0L
                    }
                    continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    })
}
