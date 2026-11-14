package com.metrolist.shared.state

import com.metrolist.shared.db.MusicDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.mp.KoinPlatform

object EpisodeListeningState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val progress by lazy {
        KoinPlatform
            .getKoin()
            .get<MusicDatabase>()
            .dao()
            .episodeListeningProgress()
            .map { rows -> rows.filter { it.hasStarted }.associateBy { it.id } }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    }
}
