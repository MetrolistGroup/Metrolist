/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.deviceconnect.DeviceCrypto
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock

class OpenRouterAuth(
    private val client: HttpClient,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var session: Session? = null

    private class Session(
        val verifier: String,
        val createdAt: Long,
        var used: Boolean = false,
    )

    fun start(): String {
        val verifier = encode(DeviceCrypto.secureRandomBytes(32))
        val challenge = challenge(verifier)
        session = Session(verifier, now())
        return URLBuilder("https://openrouter.ai/auth")
            .apply {
                parameters.append("code_challenge", challenge)
                parameters.append("code_challenge_method", "S256")
                parameters.append("key_label", "Metrolist")
            }.buildString()
    }

    fun pendingSession(): String? = session?.takeUnless { it.used }?.let { "${it.createdAt}:${it.verifier}" }

    fun restore(pending: String): Boolean {
        val parts = pending.split(':', limit = 2)
        val createdAt = parts.firstOrNull()?.toLongOrNull()
        val verifier = parts.getOrNull(1)
        session =
            if (createdAt != null && verifier?.matches(Regex("[A-Za-z0-9_-]{43}")) == true && now() - createdAt in 0..600_000) {
                Session(verifier, createdAt)
            } else {
                null
            }
        return session != null
    }

    fun cancel() {
        session = null
    }

    suspend fun exchange(code: String): String {
        val current = checkNotNull(session) { "No authorization in progress" }
        check(!current.used && now() - current.createdAt in 0..600_000) { "Authorization expired or already used" }
        require(code.isNotBlank() && code.length <= 2048 && code.none(Char::isWhitespace)) { "Invalid authorization code" }
        current.used = true
        try {
            val response =
                client.post("https://openrouter.ai/api/v1/auth/keys") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("code", JsonPrimitive(code))
                            put("code_verifier", JsonPrimitive(current.verifier))
                            put("code_challenge_method", JsonPrimitive("S256"))
                        }.toString(),
                    )
                }
            check(response.status.isSuccess()) { "Authorization failed (${response.status.value})" }
            val key =
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["key"]
                    ?.jsonPrimitive
                    ?.contentOrNull
            check(!key.isNullOrBlank()) { "Authorization returned no key" }
            check(session === current) { "Authorization cancelled" }
            return key
        } finally {
            if (session === current) session = null
        }
    }

    companion object {
        internal fun challenge(verifier: String): String = encode(DeviceCrypto.sha256(verifier.encodeToByteArray()))

        @OptIn(ExperimentalEncodingApi::class)
        private fun encode(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')
    }
}
