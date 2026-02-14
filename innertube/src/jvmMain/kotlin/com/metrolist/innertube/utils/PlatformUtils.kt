package com.metrolist.innertube.utils

import com.metrolist.innertube.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.net.Proxy
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

actual typealias PlatformProxy = Proxy

actual fun getDefaultCountryCode(): String = Locale.getDefault().country
actual fun getDefaultLanguageTag(): String = Locale.getDefault().toLanguageTag()

actual object HttpClientFactory {
    actual fun create(
        proxy: PlatformProxy?,
        proxyAuth: String?,
        visitorData: String?,
        dataSyncId: String?
    ): HttpClient {
        return HttpClient(OkHttp) {
            expectSuccess = true

            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                    encodeDefaults = true
                })
            }

            install(ContentEncoding) {
                gzip(0.9F)
                deflate(0.8F)
            }

            // Enhanced network configuration for better performance
            engine {
                config {
                    // Connection pool settings for better connection reuse
                    connectionPool(
                        okhttp3.ConnectionPool(
                            10, // maxIdleConnections
                            5, // keepAliveDuration
                            TimeUnit.MINUTES
                        )
                    )

                    // Timeout configurations
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(60, TimeUnit.SECONDS)
                    writeTimeout(60, TimeUnit.SECONDS)

                    // Enable HTTP/2 for better performance
                    protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))

                    // Retry on connection failure
                    retryOnConnectionFailure(true)

                    // Cache configuration for better performance
                    cache(
                        okhttp3.Cache(
                            directory = File(System.getProperty("java.io.tmpdir"), "http_cache"),
                            maxSize = 50L * 1024L * 1024L // 50 MB
                        )
                    )

                    // Apply proxy configuration
                    proxy?.let { proxyConfig ->
                        proxy(proxyConfig)
                    }

                    // Apply proxy authentication
                    proxyAuth?.let { auth ->
                        proxyAuthenticator { _, response ->
                            response.request.newBuilder()
                                .header("Proxy-Authorization", auth)
                                .build()
                        }
                    }
                }
            }

            // Request timeout configuration
            install(HttpTimeout) {
                requestTimeoutMillis = 60000
                connectTimeoutMillis = 30000
                socketTimeoutMillis = 60000
            }

            defaultRequest {
                url(YouTubeClient.API_URL_YOUTUBE_MUSIC)
                // Add common headers for better compatibility
                header("Accept", "application/json")
                header("Accept-Language", "en-US,en;q=0.9")
                header("Cache-Control", "no-cache")
            }
        }
    }
}
