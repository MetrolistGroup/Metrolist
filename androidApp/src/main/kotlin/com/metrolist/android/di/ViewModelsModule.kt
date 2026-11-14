package com.metrolist.android.di

import android.app.Application
import com.metrolist.android.R
import com.metrolist.android.data.PlayHistoryRepository
import com.metrolist.android.playback.AndroidPlayerPlatformServices
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.android.viewmodels.AboutArtistViewModel
import com.metrolist.android.viewmodels.AccountViewModel
import com.metrolist.android.viewmodels.AlbumViewModel
import com.metrolist.android.viewmodels.ArtistViewModel
import com.metrolist.android.viewmodels.BrowseViewModel
import com.metrolist.android.viewmodels.HistoryViewModel
import com.metrolist.android.viewmodels.HomeViewModel
import com.metrolist.android.viewmodels.LibraryViewModel
import com.metrolist.android.viewmodels.PlaybackViewModel
import com.metrolist.android.viewmodels.PlaylistViewModel
import com.metrolist.android.viewmodels.SearchViewModel
import com.metrolist.android.viewmodels.StatsViewModel
import com.metrolist.shared.api.artwork.AnimatedArtworkService
import com.metrolist.shared.api.artwork.DirectCanvasProvider
import com.metrolist.shared.viewmodels.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelsModule =
    module {
        singleOf(::PlayHistoryRepository)

        viewModel { StatsViewModel(get<PlayHistoryRepository>()) }

        viewModel { params ->
            AlbumViewModel(
                params.getOrNull(),
                get(),
                get(),
                get<CoroutineScope>(),
            )
        }

        singleOf(::AnimatedArtworkService)

        viewModel { params ->
            BrowseViewModel(params[0], params[1], params[2])
        }

        viewModel { params ->
            ArtistViewModel(params.getOrNull(), get(), get<CoroutineScope>())
        }

        viewModel { (id: String, name: String) -> AboutArtistViewModel(id, name) }

        viewModel {
            PlaybackViewModel(
                androidContext().applicationContext as Application,
                get(),
                get(),
            )
        }

        viewModelOf(::AccountViewModel)
        viewModel { HistoryViewModel(get<PlayHistoryRepository>(), get<CoroutineScope>()) }
        viewModelOf(::LibraryViewModel)
        viewModel {
            HomeViewModel(
                database = get(),
                likedSongsName = androidContext().getString(R.string.liked_songs),
            )
        }
        viewModelOf(::SearchViewModel)
        single { DirectCanvasProvider(get()) }

        viewModel { params ->
            PlaylistViewModel(
                params.getOrNull(),
                get(),
                get<CoroutineScope>(),
                thumbnailHint = params[1],
            )
        }

        single {
            PlayerViewModel(
                playerConnection = get<PlayerConnection>(),
                imageLoader = get(),
                platformContext = androidContext().applicationContext,
                platform = AndroidPlayerPlatformServices(androidContext().applicationContext, get(), get()),
                lyricsRepository = get(),
                animatedArtworkService = get(),
                musicVideoResolver = get(),
                viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        }
    }
