package com.metrolist.shared.youtube.di

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.api.lyrics.AiModelCatalog
import com.metrolist.shared.api.lyrics.LyprioLyricsProvider
import com.metrolist.shared.api.lyrics.LyricsClient
import com.metrolist.shared.api.lyrics.LyricsFetcher
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.api.lyrics.YouTubeLyricsApi
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.youtube.logging.MetrolistInnerTubeLogger
import com.metrolist.shared.youtube.lyrics.InnerTubeLyricsApi
import com.metrolist.shared.youtube.playback.DefaultStreamResolver
import com.metrolist.shared.youtube.playback.LocalAudioSource
import com.metrolist.shared.youtube.playback.MetrolistPlayerConfigRepository
import com.metrolist.shared.youtube.playback.PlatformTokenProvider
import com.metrolist.shared.youtube.playback.StreamResolver
import com.metrolist.shared.youtube.strategy.ClientHealthTracker
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val sharedModule =
    module {
        single {
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        }
        single { RemotePlayerConfigStore(get(), MetrolistPlayerConfigRepository, MetrolistInnerTubeLogger) }
        single<YtConfigParser> {
            YtConfigParserImpl(
                get(),
                get(),
                get(),
                MetrolistInnerTubeLogger,
                cipherService = get<YouTubeCipherService>(),
            )
        }

        single { ClientHealthTracker() }
        single<ClientHealthMonitor> { get<ClientHealthTracker>() }
        single<ClientFallbackStrategy> { ContentAwareFallbackStrategy(get()) }

        single { YouTubeCipherService(get(), get(), MetrolistInnerTubeLogger) }

        single<TokenProvider> { PlatformTokenProvider() }

        single<StreamExtractor> {
            InnerTubeExtractor(
                configParser = get(),
                cipherService = get<YouTubeCipherService>(),
                innerTube = get<InnerTube>(),
                fallbackStrategy = get(),
                tokenProvider = get<TokenProvider>(),
                clientHealthMonitor = get(),
                logger = MetrolistInnerTubeLogger,
            )
        }

        single<StreamResolver> {
            DefaultStreamResolver(
                extractor = get(),
                innerTube = get(),
                tokenProvider = getOrNull(),
                clientHealthTracker = get(),
                playerConfigRefresh = { get<YouTubeCipherService>().refreshAfterStreamRejection() },
                musicVideoResolver = get(),
                localAudioSource = getOrNull<LocalAudioSource>(),
            )
        }

        single<MusicVideoResolver> { MusicVideoResolver() }

        single { GlobalYouTube.repository }
        single<YouTubeLyricsApi> { InnerTubeLyricsApi(get()) }
        single {
            LyricsClient(
                httpClient = get(),
                youtubeLyricsApi = get(),
                lyprioLyricsProvider =
                    LyprioLyricsProvider.createOrNull(
                        httpClient = get(),
                        baseUrl = BuildConfig.LYPRIO_BASE_URL,
                        signingKeyBase64 = BuildConfig.LYPRIO_HMAC_KEY,
                        allowInsecureHttp = BuildConfig.DEBUG,
                    ),
            )
        }
        single<LyricsFetcher> { get<LyricsClient>() }
        single { LyricsTranslationClient(get(), get()) }
        single { AiModelCatalog(get()) }
        single {
            LyricsRepository(
                lyricsClient = get(),
                lyricsTranslationClient = get(),
                aiModelCatalog = get(),
                databaseDao = getOrNull(),
                youtubeLyricsApi = get(),
            )
        }
    }
