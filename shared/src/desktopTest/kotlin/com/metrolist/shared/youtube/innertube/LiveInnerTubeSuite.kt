package com.metrolist.shared.youtube.innertube

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.settings.Settings
import io.ktor.client.HttpClient
import java.io.File
import java.util.Base64
import java.util.Properties
import kotlin.test.fail

internal object LiveInnerTubeSuite {
    private const val CLIENT_TEST_FLAG = "runLiveInnerTubeClientTests"
    private const val CLIENT_TEST_ENV = "RUN_LIVE_INNERTUBE_CLIENT_TESTS"
    private const val TOKEN_PROPERTY = "innerTubeTokenBase64"
    private const val TOKEN_ENV = "INNERTUBE_TOKEN_BASE64"
    private const val METROLIST_TOKEN_ENV = "METROLIST_INNERTUBE_TOKEN_BASE64"
    private const val TOKEN_FILE_PROPERTY = "innerTubeTokenFile"
    private const val TOKEN_FILE_ENV = "INNERTUBE_TOKEN_FILE"
    private const val DESKTOP_SESSION_ENV = "INNERTUBE_USE_DESKTOP_SESSION"
    private const val DESKTOP_SESSION_PROPERTY = "innerTubeUseDesktopSession"
    private const val TOKEN_FILE = "innertube_cookie.txt"
    private const val MAX_TOKEN_FILE_BYTES = 1L * 1024L * 1024L
    private const val SIGNED_OUT_FLAG = "innerTubeSignedOut"
    private const val SIGNED_OUT_ENV = "INNERTUBE_SIGNED_OUT"

    data class DecodedInnerTubeToken(
        val cookie: String,
        val visitorData: String,
        val dataSyncId: String,
        val authUser: String,
    )

    fun liveClientTestsEnabled(): Boolean = flagEnabled(CLIENT_TEST_FLAG, CLIENT_TEST_ENV)

    fun createConfiguredInnerTube(httpClient: HttpClient): InnerTube {
        if (signedOutMode()) return InnerTube(httpClient)
        return createConfiguredInnerTube(httpClient, requireToken())
    }

    fun createConfiguredInnerTube(
        httpClient: HttpClient,
        token: DecodedInnerTubeToken,
    ): InnerTube =
        if (signedOutMode()) {
            InnerTube(httpClient)
        } else {
            InnerTube(httpClient).also { applyToken(it, token) }
        }

    fun signedOutMode(): Boolean = flagEnabled(SIGNED_OUT_FLAG, SIGNED_OUT_ENV)

    fun applyToken(
        innerTube: InnerTube,
        token: DecodedInnerTubeToken,
    ) {
        innerTube.cookie = token.cookie
        innerTube.visitorData = token.visitorData.takeIf { it.isNotBlank() }
        innerTube.dataSyncId = token.dataSyncId.takeIf { it.isNotBlank() }
        innerTube.authUser = token.authUser
        innerTube.useLoginForBrowse = true
    }

    fun requireToken(): DecodedInnerTubeToken {
        val tokenText =
            readTokenText()
                ?: fail(
                    "Live InnerTube client tests require a base64 app token. Set $TOKEN_ENV in the environment, " +
                        "local.properties, or $TOKEN_FILE.",
                )
        val decoded = decodeTokenText(tokenText)
        val token = parseDecodedToken(decoded)
        if (token.cookie.isBlank()) {
            fail("Decoded InnerTube token did not contain an INNERTUBE COOKIE section.")
        }
        val cookieMap = parseCookieString(token.cookie)
        val hasSapisid =
            cookieMap.containsKey("SAPISID") ||
                cookieMap.containsKey("__Secure-3PAPISID") ||
                cookieMap.containsKey("__Secure-1PAPISID")
        if (!hasSapisid) {
            fail("InnerTube token cookie must include SAPISID or a __Secure-*PAPISID cookie for authenticated client tests.")
        }
        return token
    }

    fun log(message: String) = println("[live-innertube] $message")

    private fun flagEnabled(
        propertyName: String,
        envName: String,
    ): Boolean = System.getProperty(propertyName) == "true" || System.getenv(envName) == "true"

    private fun readTokenText(): String? =
        selectTokenText(
            useDesktopSession = flagEnabled(DESKTOP_SESSION_PROPERTY, DESKTOP_SESSION_ENV),
            desktopSessionToken = { readDesktopSettingsToken(Settings.shared) },
            fallbackToken = {
                firstNonBlank(
                    System.getProperty(TOKEN_PROPERTY),
                    System.getenv(TOKEN_ENV),
                    System.getenv(METROLIST_TOKEN_ENV),
                    readLocalProperty(TOKEN_PROPERTY),
                    readLocalProperty(TOKEN_ENV),
                    readLocalProperty(METROLIST_TOKEN_ENV),
                    readLocalTokenFile(),
                )
            },
        )

    internal fun selectTokenText(
        useDesktopSession: Boolean,
        desktopSessionToken: () -> String?,
        fallbackToken: () -> String?,
    ): String? =
        if (useDesktopSession) {
            desktopSessionToken()?.takeIf { it.isNotBlank() }
                ?: fail("INNERTUBE_USE_DESKTOP_SESSION=true but desktop session credentials are unavailable.")
        } else {
            fallbackToken()
        }

    private fun parseDecodedToken(decoded: String): DecodedInnerTubeToken {
        val cookie =
            section(decoded, "INNERTUBE COOKIE").ifBlank {
                decoded
                    .takeIf { it.contains("SAPISID=") || it.contains("__Secure-3PAPISID=") }
                    ?.let(::sanitizeCookie)
                    .orEmpty()
            }
        return DecodedInnerTubeToken(
            cookie = cookie,
            visitorData = section(decoded, "VISITOR DATA").trim().removePrefix("=").trim(),
            dataSyncId = sanitizeDataSyncId(section(decoded, "DATASYNC ID")),
            authUser = sanitizeAuthUser(section(decoded, "AUTH USER").ifBlank { section(decoded, "SESSION INDEX") }),
        )
    }

    private fun section(
        token: String,
        label: String,
    ): String =
        Regex(
            """\*\*\*${Regex.escape(label)}\*\*\*\s*=?\s*(.*?)(?=\r?\n\*\*\*|$)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).find(token)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    private fun sanitizeCookie(cookie: String): String =
        cookie
            .filterNot { it.isISOControl() }
            .trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .trim()
            .removeSuffix(";")
            .trim()

    private fun sanitizeDataSyncId(dataSyncId: String): String =
        dataSyncId
            .trim()
            .substringBefore("||")
            .ifBlank { dataSyncId.trim().substringAfter("||", "").trim() }

    private fun sanitizeAuthUser(authUser: String): String = authUser.filter { it.isDigit() }.ifBlank { "0" }

    private fun decodeTokenText(value: String): String {
        decodeBase64OrNull(value)?.let { return it }
        val raw = value.trim()
        if (raw.contains("PAPISID=") || raw.contains("***INNERTUBE COOKIE***", ignoreCase = true)) return raw
        fail("$TOKEN_ENV is not valid base64 and $TOKEN_FILE did not contain a recognizable InnerTube token.")
    }

    private fun decodeBase64OrNull(value: String): String? {
        val compact = value.filterNot { it.isWhitespace() }
        val bytes =
            listOf(
                Base64.getDecoder(),
                Base64.getUrlDecoder(),
                Base64.getMimeDecoder(),
            ).firstNotNullOfOrNull { decoder ->
                runCatching { decoder.decode(compact) }.getOrNull()
            } ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readLocalTokenFile(): String? =
        listOfNotNull(
            firstNonBlank(System.getProperty(TOKEN_FILE_PROPERTY), System.getenv(TOKEN_FILE_ENV))?.let(::File),
            File(TOKEN_FILE),
            File("../$TOKEN_FILE"),
            File("../innertubex/$TOKEN_FILE"),
        ).firstOrNull { it.isFile && it.length() in 1..MAX_TOKEN_FILE_BYTES }
            ?.readText()
            ?.takeIf { it.isNotBlank() }

    internal fun readDesktopSettingsToken(settings: Settings): String? {
        val cookie = settings.innerTubeCookie.takeIf { it.isNotBlank() } ?: return null
        return listOf(
            "***INNERTUBE COOKIE***",
            cookie,
            "***VISITOR DATA***",
            settings.visitorData,
            "***DATASYNC ID***",
            settings.dataSyncId,
            "***AUTH USER***",
            settings.innerTubeAuthUser,
        ).joinToString("\n")
    }

    private fun readLocalProperty(name: String): String? {
        val files = listOf(File("local.properties"), File("../local.properties"))
        for (file in files) {
            if (!file.exists()) continue
            val properties = Properties()
            file.inputStream().use(properties::load)
            properties.getProperty(name)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()
}

private fun parseCookieString(cookie: String): Map<String, String> =
    cookie
        .split(';')
        .mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            entry.substring(0, separator).trim() to entry.substring(separator + 1).trim()
        }.toMap()

internal suspend fun withLiveInnerTubeClient(block: suspend (HttpClient, InnerTube) -> Unit) {
    createHttpClient().use { httpClient ->
        block(httpClient, LiveInnerTubeSuite.createConfiguredInnerTube(httpClient))
    }
}
