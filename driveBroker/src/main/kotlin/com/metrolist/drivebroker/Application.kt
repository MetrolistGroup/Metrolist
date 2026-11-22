package com.metrolist.drivebroker

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.cio.CIO as ServerCIO

@Serializable
data class BrokerConfigResponse(
    val webClientId: String,
    val androidClientId: String,
    val iosClientId: String,
    val desktopClientId: String,
)

@Serializable
data class ExchangeRequest(
    val platform: String,
    val code: String,
    val redirectUri: String? = null,
    val codeVerifier: String? = null,
)

@Serializable
data class SessionResponse(
    val sessionToken: String,
    val email: String? = null,
)

@Serializable
data class AccessTokenResponse(
    val accessToken: String,
    val expiresIn: Long,
)

@Serializable
data class ErrorResponse(
    val error: String,
)

fun main() {
    val configuration = BrokerConfiguration.fromEnvironment()
    val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = configuration.databaseUrl
                username = configuration.databaseUser
                password = configuration.databasePassword
                maximumPoolSize = 10
                connectionTimeout = 5000
                validate()
            },
        )
    Flyway
        .configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()
    val repository = TokenRepository(dataSource, configuration.encryptionKey)
    val httpClient =
        HttpClient(CIO) {
            install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                connectTimeoutMillis = 5000
                requestTimeoutMillis = 15000
                socketTimeoutMillis = 15000
            }
            expectSuccess = false
        }
    val googleOAuth = GoogleOAuth(httpClient, configuration)
    embeddedServer(ServerCIO, port = configuration.port) {
        monitor.subscribe(ApplicationStopped) {
            httpClient.close()
            dataSource.close()
        }
        driveBrokerModule(configuration, repository, googleOAuth)
    }.start(wait = true)
}

fun Application.driveBrokerModule(
    configuration: BrokerConfiguration,
    repository: TokenRepository,
    googleOAuth: GoogleOAuth,
) {
    install(CallLogging)
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    // A Google revocation can invalidate refresh tokens obtained by an overlapping sign-in.
    val driveAuthorizationMutex = Mutex()
    routing {
        get("/healthz") { call.respond(HttpStatusCode.OK) }
        route("/v1") {
            get("/config") {
                call.respond(
                    BrokerConfigResponse(
                        configuration.webClientId,
                        configuration.androidClientId,
                        configuration.iosClientId,
                        configuration.desktopClientId,
                    ),
                )
            }
            post("/auth/exchange") {
                // Read unauthenticated input before taking the lock shared with disconnect.
                val request = safeOperation { call.receive<ExchangeRequest>() }
                val platform = request?.platform?.let(ClientPlatform::parse)
                if (request == null || platform == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request"))
                    return@post
                }
                driveAuthorizationMutex.withLock {
                    val initialIssuedAt = safeOperation { repository.currentDatabaseTime() }
                    if (initialIssuedAt == null) {
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("session_creation_failed"))
                        return@withLock
                    }
                    val account =
                        safeOperation {
                            googleOAuth.exchange(platform, request.code, request.redirectUri, request.codeVerifier)
                        }
                    if (account == null) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("authorization_failed"))
                        return@withLock
                    }
                    when (val creation = safeOperation { repository.createSession(account, platform, initialIssuedAt) }) {
                        is SessionCreationResult.Created -> {
                            call.respond(SessionResponse(creation.token, account.email))
                        }

                        // A revocation overlapped the code exchange; the client must obtain a new code.
                        SessionCreationResult.StaleCredentials -> {
                            call.respond(HttpStatusCode.Conflict, ErrorResponse("authorization_failed"))
                        }

                        null -> {
                            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("session_creation_failed"))
                        }
                    }
                }
            }
            post("/auth/access-token") {
                val token = bearerToken(call.request.headers["Authorization"])
                if (token == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                    return@post
                }
                val account =
                    try {
                        repository.accountForSession(token)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("session_lookup_failed"))
                        return@post
                    }
                if (account == null) {
                    // A well-formed token the broker no longer knows cannot recover by retrying.
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("reauthorization_required"))
                    return@post
                }
                when (val accessToken = safeOperation { googleOAuth.accessToken(account) }) {
                    is GoogleAccessTokenResult.Success -> {
                        call.respond(AccessTokenResponse(accessToken.accessToken, accessToken.expiresIn))
                    }

                    GoogleAccessTokenResult.ReauthorizationRequired -> {
                        // Google already rejected the grant, so retire its sessions before the client
                        // reconnects; otherwise a later code exchange would revive the old tokens.
                        val invalidated =
                            driveAuthorizationMutex.withLock {
                                safeOperation { repository.invalidateAccount(account) }
                            }
                        if (invalidated == null) {
                            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("session_cleanup_failed"))
                        } else {
                            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("reauthorization_required"))
                        }
                    }

                    else -> {
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("token_refresh_failed"))
                    }
                }
            }
            delete("/auth/session") {
                val token = bearerToken(call.request.headers["Authorization"])
                if (token == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                    return@delete
                }
                driveAuthorizationMutex.withLock {
                    try {
                        repository.revokeSession(token) { account ->
                            if (account != null) googleOAuth.revoke(account.refreshToken)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("revocation_failed"))
                        return@withLock
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private fun bearerToken(header: String?): String? =
    header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.takeIf { it.length in 40..64 }

private suspend fun <T> safeOperation(operation: suspend () -> T): T? =
    try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
