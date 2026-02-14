package com.metrolist.innertube.utils

import com.metrolist.innertube.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.countryCode
import platform.Foundation.languageCode

actual class PlatformProxy

actual fun getDefaultCountryCode(): String = NSLocale.currentLocale.countryCode ?: "US"
actual fun getDefaultLanguageTag(): String = NSLocale.currentLocale.languageCode ?: "en"

actual object HttpClientFactory {
    actual fun create(
        proxy: PlatformProxy?,
        proxyAuth: String?,
        visitorData: String?,
        dataSyncId: String?
    ): HttpClient {
        return HttpClient(Darwin) {
            expectSuccess = true

            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                    encodeDefaults = true
                })
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 60000
                connectTimeoutMillis = 30000
                socketTimeoutMillis = 60000
            }

            engine {
                configureRequest {
                    setAllowsCellularAccess(true)
                }
            }

            defaultRequest {
                url(YouTubeClient.API_URL_YOUTUBE_MUSIC)
                header("Accept", "application/json")
                header("Accept-Language", "en-US,en;q=0.9")
                header("Cache-Control", "no-cache")
            }
        }
    }
}
