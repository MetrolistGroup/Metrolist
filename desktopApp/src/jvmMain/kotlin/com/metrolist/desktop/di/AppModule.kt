/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.di

import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.SongCacheManager
import com.metrolist.desktop.storage.desktopSongOfflineAvailability
import com.metrolist.desktop.youtube.DesktopPageBoundTokenRuntime
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.OfflineSongAvailability
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.youtube.playback.DesktopTokenProvider
import com.metrolist.shared.youtube.playback.LocalAudioSource
import org.koin.dsl.module

val composeStateModule =
    module {
        single<MusicDatabase> { getDatabaseBuilder().build() }
        single<LocalAudioSource> {
            LocalAudioSource { id, song ->
                DownloadManager.getLocalPlaybackResource(id, song) ?: SongCacheManager.getCachedPlaybackResource(id, song)
            }
        }
        single<DatabaseDao> { get<MusicDatabase>().dao() }
        single<OfflineSongAvailability> { desktopSongOfflineAvailability(get()) }
        single { DesktopPageBoundTokenRuntime() }
        single<TokenProvider> {
            DesktopTokenProvider(get<DesktopPageBoundTokenRuntime>()) {
                val queue = PlayerState.queue.snapshots.value
                queue.displayedItems
                    .asSequence()
                    .drop(queue.currentIndex + 1)
                    .take(2)
                    .map { it.id }
                    .toList()
            }
        }
    }
