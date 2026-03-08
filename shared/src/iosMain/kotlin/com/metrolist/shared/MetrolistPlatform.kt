package com.metrolist.shared

import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.backup.IosBackupRestoreManager
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.discord.DiscordRpcManager
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.scrobble.ScrobbleSessions
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.coreModule
import com.metrolist.shared.youtube.di.sharedModule
import com.metrolist.shared.youtube.playback.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

object MetrolistPlatform {
    private var isInitialized = false

    fun initialize() {
        if (isInitialized) return
        IosBackupRestoreManager().recoverInterruptedRestore()
        isInitialized = true
        startKoin {
            modules(
                coreModule,
                sharedModule,
                module {
                    single<MusicDatabase> { getDatabaseBuilder(null).build() }
                    single<DatabaseDao> { get<MusicDatabase>().dao() }
                    single { MusicPlayer() }
                },
            )
        }

        LastFM.initialize(
            apiKey = BuildConfig.LASTFM_API_KEY,
            secret = BuildConfig.LASTFM_SECRET,
        )
        LastFM.sessionKey = Settings.shared.lastFmSession.ifBlank { null }
        ScrobbleSessions.restorePersistedAccounts()

        AuthManager().restorePersistedSession()

        val startupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        startupScope.launch {
            BlockedContent.refreshAiSlopBlocklist()
        }
        startupScope.launch {
            runCatching {
                KoinPlatform.getKoin().get<StreamResolver>().prewarm()
            }
        }
    }

    suspend fun reopenDatabaseAfterRestore(): MusicDatabase {
        val koin = KoinPlatform.getKoin()
        val database = getDatabaseBuilder(null).build()
        koin.declare(database)
        koin.declare(database.dao())
        koin.declare(MusicPlayer())
        koin.declare(
            LyricsRepository(
                lyricsClient = koin.get(),
                lyricsTranslationClient = koin.get(),
                databaseDao = database.dao(),
                youtubeLyricsApi = koin.get(),
                aiModelCatalog = koin.get(),
            ),
        )
        refreshRestoredSettings()
        return database
    }

    suspend fun refreshRestoredSettings() {
        DiscordRpcManager.reloadAfterRestore(GlobalYouTube.client)
        AuthManager().restorePersistedSession()
        ScrobbleSessions.restorePersistedAccounts()
        SongLikeState.refreshFromSettings()
    }
}
