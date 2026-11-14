package meow.nyx.spotlib

import com.metrolist.shared.network.TextResponse
import com.metrolist.shared.network.bodyAsTextLimited
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.statement.HttpStatement
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import java.net.CookieManager
import java.net.CookieStore
import java.net.HttpCookie
import java.net.URI

class SpotifyCookieJar {
    private val store: CookieStore = CookieManager().cookieStore
    private val cookieExemptHosts = setOf("api-partner.spotify.com", "clienttoken.spotify.com")

    fun requestCookies(url: Url): String? {
        if (url.host in cookieExemptHosts) return null
        return store
            .get(uri(url))
            .takeIf(List<HttpCookie>::isNotEmpty)
            ?.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun cookieValue(
        url: Url,
        name: String,
    ): String? = store.get(uri(url)).firstOrNull { it.name == name }?.value

    fun capture(
        url: Url,
        setCookieHeaders: List<String>,
    ) {
        for (raw in setCookieHeaders) {
            for (cookie in HttpCookie.parse(raw)) {
                if (cookie.path.isNullOrEmpty()) cookie.path = "/"
                if (cookie.domain.isNullOrBlank()) cookie.domain = url.host
                try {
                    store.add(uri(url), cookie)
                } catch (_: IllegalArgumentException) {
                }
            }
        }
    }

    fun store(
        url: Url,
        name: String,
        value: String,
    ) {
        val cookie = HttpCookie(name, value)
        cookie.path = "/"
        cookie.domain = url.host
        store.add(uri(url), cookie)
    }

    fun all(): List<HttpCookie> = store.cookies

    fun clear() = store.removeAll()

    private fun uri(url: Url): URI = URI("${url.protocol.name}://${url.host}")
}

internal class ApiHttp(
    private val client: HttpClient,
    private val jar: SpotifyCookieJar,
) {
    suspend fun get(
        url: String,
        maxBytes: Int = 256 * 1024,
        block: HttpRequestBuilder.() -> Unit = {},
    ): TextResponse = send(client.prepareGet(url) { applyCommon(url, block) }, maxBytes)

    suspend fun post(
        url: String,
        maxBytes: Int = 256 * 1024,
        block: HttpRequestBuilder.() -> Unit = {},
    ): TextResponse = send(client.preparePost(url) { applyCommon(url, block) }, maxBytes)

    private fun HttpRequestBuilder.applyCommon(
        url: String,
        block: HttpRequestBuilder.() -> Unit,
    ) {
        jar.requestCookies(Url(url))?.let { header(HttpHeaders.Cookie, it) }
        block()
    }

    private suspend fun send(
        statement: HttpStatement,
        maxBytes: Int,
    ): TextResponse =
        statement.execute { response ->
            response.headers.getAll(HttpHeaders.SetCookie)?.let { jar.capture(response.call.request.url, it) }
            val request = response.call.request
            Log.info("${request.method.value} ${request.url.host}${request.url.encodedPath} -> ${response.status.value}")
            TextResponse(response.status, response.headers, response.bodyAsTextLimited(maxBytes))
        }
}
