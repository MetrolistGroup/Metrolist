package com.metrolist.shared.backup

import com.metrolist.shared.network.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

class DriveTokenBrokerClient(
    brokerUrl: String,
    private val httpClient: HttpClient = createHttpClient().config { followRedirects = false },
) {
    private val baseUrl = brokerUrl.trimEnd('/')

    suspend fun configuration(): DriveBrokerConfiguration = httpClient.get("$baseUrl/v1/config").requireSuccess().body()

    suspend fun exchangeCode(
        platform: DriveClientPlatform,
        code: String,
        redirectUri: String? = null,
        codeVerifier: String? = null,
    ): DriveBrokerSession =
        httpClient
            .post("$baseUrl/v1/auth/exchange") {
                contentType(ContentType.Application.Json)
                setBody(DriveCodeExchangeRequest(platform.name, code, redirectUri, codeVerifier))
            }.requireSuccess()
            .body()

    suspend fun accessToken(sessionToken: String): DriveAccessToken =
        httpClient
            .post("$baseUrl/v1/auth/access-token") {
                header(HttpHeaders.Authorization, "Bearer $sessionToken")
            }.requireSuccess()
            .body()

    suspend fun revoke(sessionToken: String) {
        httpClient
            .delete("$baseUrl/v1/auth/session") {
                header(HttpHeaders.Authorization, "Bearer $sessionToken")
            }.requireSuccess()
    }

    fun close() = httpClient.close()
}

class DriveBrokerException(
    val statusCode: Int,
) : Exception("Drive broker request failed with HTTP $statusCode")

class DriveReauthorizationRequiredException : DriveBrokerException(HttpStatusCode.Unauthorized.value)

private suspend fun HttpResponse.requireSuccess(): HttpResponse =
    also {
        if (status == HttpStatusCode.Unauthorized) {
            val error = runCatching { body<DriveBrokerError>().error }.getOrNull()
            if (error == REAUTHORIZATION_REQUIRED) throw DriveReauthorizationRequiredException()
        }
        if (status.value !in 200..299) throw DriveBrokerException(status.value)
    }

private const val REAUTHORIZATION_REQUIRED = "reauthorization_required"

@Serializable
data class DriveBrokerConfiguration(
    val webClientId: String,
    val androidClientId: String,
    val iosClientId: String,
    val desktopClientId: String,
)

@Serializable
data class DriveBrokerSession(
    val sessionToken: String,
    val email: String? = null,
)

@Serializable
data class DriveAccessToken(
    val accessToken: String,
    val expiresIn: Long,
)

@Serializable
private data class DriveBrokerError(
    val error: String? = null,
)

@Serializable
private data class DriveCodeExchangeRequest(
    val platform: String,
    val code: String,
    val redirectUri: String? = null,
    val codeVerifier: String? = null,
)

enum class DriveClientPlatform {
    ANDROID,
    IOS,
    DESKTOP,
}
