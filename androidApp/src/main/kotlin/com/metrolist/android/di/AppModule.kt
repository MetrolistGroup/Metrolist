/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.di

import coil3.ImageLoader
import coil3.imageLoader
import com.metrolist.android.playback.AudioCacheManager
import com.metrolist.android.playback.ExoPlaybackBenchmarkPlayer
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.android.playback.SongDownloadManager
import com.metrolist.android.widget.MetrolistWidgetManager
import com.metrolist.android.widget.PlaylistWidgetManager
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkEnvironment
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkRunner
import com.metrolist.shared.youtube.logging.MetrolistInnerTubeLogger
import com.metrolist.shared.youtube.playback.DefaultStreamResolver
import com.metrolist.shared.youtube.playback.LocalAudioSource
import com.metrolist.shared.youtube.strategy.ClientHealthTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule =
    module {
        single<CoroutineScope> {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }

        single<MusicDatabase> {
            getDatabaseBuilder(androidContext()).build()
        }

        single<DatabaseDao> {
            get<MusicDatabase>().dao()
        }

        single<AudioCacheManager> {
            AudioCacheManager(
                context = androidContext(),
                database = get(),
            )
        }

        single<LocalAudioSource> {
            LocalAudioSource { id, song ->
                val storedSong = song ?: get<MusicDatabase>().dao().getSongById(id)?.toSongItem()
                storedSong?.let { get<AudioCacheManager>().getCachedPlaybackResource(it) }
            }
        }

        single<SongDownloadManager> {
            SongDownloadManager(
                context = androidContext(),
                audioCacheManager = get(),
                database = get(),
            )
        }

        single<ImageLoader> {
            androidContext().imageLoader
        }

        single<PlayerConnection> {
            PlayerConnection(
                context = androidContext(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        }

        single<PlaylistWidgetManager> {
            PlaylistWidgetManager(
                context = androidContext(),
                database = get(),
                applicationScope = get(),
                imageLoader = get(),
            )
        }

        single<MetrolistWidgetManager> {
            MetrolistWidgetManager(
                context = androidContext(),
                playlistWidgetManager = get(),
                imageLoader = get(),
            )
        }

        single {
            val innerTube = get<InnerTube>()
            val tokenProvider = get<TokenProvider>()
            val cipherService = get<YouTubeCipherService>()
            val musicVideoResolver = get<MusicVideoResolver>()
            PlaybackBenchmarkRunner(
                environmentFor = { anonymousSession ->
                    val benchmarkHealthTracker = ClientHealthTracker(persistenceEnabled = false)
                    val benchmarkInnerTube = innerTube.createIsolatedSession(includeAccount = !anonymousSession)
                    val benchmarkExtractor =
                        InnerTubeExtractor(
                            configParser =
                                YtConfigParserImpl(
                                    innerTube.httpClient,
                                    benchmarkInnerTube,
                                    get<RemotePlayerConfigStore>(),
                                    MetrolistInnerTubeLogger,
                                    cipherService = cipherService,
                                ),
                            cipherService = cipherService,
                            innerTube = benchmarkInnerTube,
                            fallbackStrategy = ContentAwareFallbackStrategy(benchmarkHealthTracker),
                            tokenProvider = tokenProvider,
                            clientHealthMonitor = benchmarkHealthTracker,
                            logger = MetrolistInnerTubeLogger,
                        )
                    PlaybackBenchmarkEnvironment(
                        httpClient = benchmarkInnerTube.httpClient,
                        youtubeRepository = YouTubeRepository(benchmarkInnerTube),
                        // The runner calls this off the main thread; ExoPlayer must be created and configured on main.
                        playerFactory = { runBlocking(Dispatchers.Main) { ExoPlaybackBenchmarkPlayer(androidContext()) } },
                        resolverFor = {
                            DefaultStreamResolver(
                                extractor = benchmarkExtractor,
                                innerTube = benchmarkInnerTube,
                                tokenProvider = tokenProvider,
                                clientHealthTracker = benchmarkHealthTracker,
                                playerConfigRefresh = { cipherService.refreshAfterStreamRejection() },
                                musicVideoResolver = musicVideoResolver,
                            )
                        },
                    )
                },
            )
        }

        includes(viewModelsModule)
    }
