package com.metrolist.shared.state

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.OfflineSongAvailability
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.logging.MetrolistInnerTubeLogger
import com.metrolist.shared.youtube.playback.StreamResolver
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

private val contentLanguageCodes: Set<String> by lazy {
    (platformContentLanguageCodes + BuildConfig.BUNDLED_LANGUAGE_TAGS.split(','))
        .filterTo(mutableSetOf()) { it.isNotBlank() && it != "und" }
}

// Language, optional script and optional region; platforms list different combinations (iOS has zh-Hant-HK, not zh-HK).
private val contentLanguageTag = Regex("([a-zA-Z]{2,3})(?:-([A-Z][a-z]{3}))?(?:-([A-Z]{2}|[0-9]{3}))?")

private fun isContentLanguage(tag: String): Boolean {
    if (tag in contentLanguageCodes) return true
    val match = contentLanguageTag.matchEntire(tag) ?: return false
    val language = match.groupValues[1]
    val region = match.groupValues[3]
    return (language in contentLanguageCodes || canonicalLanguageTag(language) in contentLanguageCodes) &&
        (region.isEmpty() || region.first().isDigit() || region in contentCountryCodes)
}

val coreModule =
    module {
        single { GlobalYouTube.client }
        single { GlobalYouTube.innerTube }
    }

object GlobalYouTube {
    val client = createHttpClient()
    val innerTube: InnerTube = InnerTube(client, logger = MetrolistInnerTubeLogger)
    val repository: YouTubeRepository =
        YouTubeRepository(
            innerTube,
            ::currentStreamResolver,
            databaseProvider = { KoinPlatform.getKoinOrNull()?.getOrNull<MusicDatabase>() },
            offlineSongAvailabilityProvider = { KoinPlatform.getKoinOrNull()?.getOrNull<OfflineSongAvailability>() },
        )
    private val playbackSessionGenerationCounter = atomic(0L)
    val playbackSessionGeneration: Long get() = playbackSessionGenerationCounter.value

    private val _regionApplied =
        MutableSharedFlow<YouTubeLocale>(
            replay = 0,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val regionApplied: SharedFlow<YouTubeLocale> = _regionApplied.asSharedFlow()

    fun updateCookie(cookie: String?) {
        innerTube.cookie = cookie
        playbackSessionGenerationCounter.incrementAndGet()
    }

    fun updateSession(
        cookie: String?,
        visitorData: String?,
        dataSyncId: String?,
        authUser: String,
        useLoginForBrowse: Boolean,
    ) {
        innerTube.replaceSession(cookie, visitorData, dataSyncId, authUser, useLoginForBrowse)
        playbackSessionGenerationCounter.incrementAndGet()
    }

    fun applyContentRegion(
        contentCountry: String,
        contentLanguage: String,
        systemCountry: String,
        systemLanguage: String,
        settings: Settings = Settings.shared,
    ) {
        val validContentLanguage = isContentLanguage(contentLanguage)
        if (contentLanguage != SYSTEM && !validContentLanguage) {
            settings.contentLanguage = SYSTEM
        }
        val resolvedHl = if (validContentLanguage) contentLanguage else systemLanguage.substringBefore('-').substringBefore('_')
        val validContentCountry = contentCountry in contentCountryCodes
        if (contentCountry != SYSTEM && !validContentCountry) {
            settings.contentCountry = SYSTEM
        }
        val resolvedGl = if (validContentCountry) contentCountry else systemCountry

        if (resolvedGl != settings.lastResolvedContentCountry && !settings.credentialsPending) {
            settings.lastResolvedContentCountry = resolvedGl
            settings.visitorData = ""
            settings.lastHomePageJson = ""
            innerTube.visitorData = ""
        }
        if (resolvedHl != settings.lastResolvedContentLanguage) {
            settings.lastResolvedContentLanguage = resolvedHl
            settings.lastHomePageJson = ""
        }

        innerTube.locale = YouTubeLocale(gl = resolvedGl, hl = resolvedHl)
        innerTube.regionOverrideActive = true
        playbackSessionGenerationCounter.incrementAndGet()

        Logger.i(
            "ContentRegion",
            "applied hl=$resolvedHl gl=$resolvedGl " +
                "(contentLanguage=$contentLanguage contentCountry=$contentCountry " +
                "systemLanguage=$systemLanguage systemCountry=$systemCountry)",
        )
        _regionApplied.tryEmit(YouTubeLocale(gl = resolvedGl, hl = resolvedHl))
    }

    const val SYSTEM = "SYSTEM"

    suspend fun close() {
        currentStreamResolver()?.close()
        runCatching { KoinPlatform.getKoinOrNull()?.getOrNull<YouTubeCipherService>()?.dispose() }
        runCatching { KoinPlatform.getKoinOrNull()?.getOrNull<TokenProvider>()?.close() }
        innerTube.close()
        client.close()
    }
}

private fun currentStreamResolver(): StreamResolver? =
    try {
        KoinPlatform.getKoinOrNull()?.getOrNull()
    } catch (e: Exception) {
        Logger.w("GlobalYouTube", "Failed to resolve StreamResolver: ${e.message}")
        null
    }

object GlobalInnerTube {
    val client get() = GlobalYouTube.client
    val instance get() = GlobalYouTube.innerTube
}

object GlobalYouTubeRepository {
    val instance get() = GlobalYouTube.repository
    val streamResolver: StreamResolver? get() = currentStreamResolver()
}
