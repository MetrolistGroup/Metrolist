package com.metrolist.innertube.utils

import io.ktor.client.HttpClient

expect class PlatformProxy

expect fun getDefaultCountryCode(): String
expect fun getDefaultLanguageTag(): String

expect object HttpClientFactory {
    fun create(proxy: PlatformProxy?, proxyAuth: String?, visitorData: String?, dataSyncId: String?): HttpClient
}
