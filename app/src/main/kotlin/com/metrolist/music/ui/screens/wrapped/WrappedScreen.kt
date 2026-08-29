/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.wrapped

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.metrolist.music.LocalNavController
import com.metrolist.music.R
import com.metrolist.music.ui.screens.wrapped.pages.ConclusionPage
import com.metrolist.music.ui.screens.wrapped.pages.PlaylistPage
import com.metrolist.music.ui.screens.wrapped.pages.WrappedIntro
import com.metrolist.music.ui.screens.wrapped.pages.WrappedMinutesScreen
import com.metrolist.music.ui.screens.wrapped.pages.WrappedMinutesTease
import com.metrolist.music.ui.screens.wrapped.pages.WrappedTop5AlbumsScreen
import com.metrolist.music.ui.screens.wrapped.pages.WrappedTop5ArtistsScreen
import com.metrolist.music.ui.screens.wrapped.pages.WrappedTop5SongsScreen
import com.metrolist.music.ui.screens.wrapped.pages.WrappedTopAlbumScreen
import com.metrolist.music.ui.screens.wrapped.pages.WrappedTopArtistScreen
import com.metrolist.music.ui.screens.wrapped.pages.WrappedTopSongScreen
import com.metrolist.music.ui.screens.wrapped.pages.WrappedTotalAlbumsScreen
import com.metrolist.music.ui.screens.wrapped.pages.WrappedTotalArtistsScreen
import com.metrolist.music.ui.screens.wrapped.pages.WrappedTotalSongsScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

sealed class WrappedScreenType {
    object Welcome : WrappedScreenType()

    object MinutesTease : WrappedScreenType()

    object MinutesReveal : WrappedScreenType()

    object TotalSongs : WrappedScreenType()

    object TopSongReveal : WrappedScreenType()

    object Top5Songs : WrappedScreenType()

    object TotalAlbums : WrappedScreenType()

    object TopAlbumReveal : WrappedScreenType()

    object Top5Albums : WrappedScreenType()

    object TotalArtists : WrappedScreenType()

    object TopArtistReveal : WrappedScreenType()

    object Top5Artists : WrappedScreenType()

    object Playlist : WrappedScreenType()

    object Conclusion : WrappedScreenType()
}

@Composable
fun WrappedScreen() {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val manager = remember { provideWrappedManager(context) }

    CompositionLocalProvider(LocalWrappedManager provides manager) {
        WrappedScreenContent()
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WrappedScreenContent() {
    val navController = LocalNavController.current
    val onClose: () -> Unit = {
        navController.previousBackStackEntry?.savedStateHandle?.set("wrapped_seen", true)
        navController.popBackStack()
    }
    BackHandler(onBack = onClose)

    val messagePairSaver =
        Saver<MessagePair, List<Any>>(
            save = { listOf(it.range.first, it.range.last, it.tease, it.reveal) },
            restore = {
                MessagePair(
                    range = (it[0] as Long)..(it[1] as Long),
                    tease = it[2] as String,
                    reveal = it[3] as String,
                )
            },
        )
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val manager = LocalWrappedManager.current
    val audioService = remember { WrappedAudioService(view.context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        val window = (view.context as android.app.Activity).window
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        audioService.pause()
                    }

                    Lifecycle.Event.ON_RESUME -> {
                        audioService.resume()
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            lifecycleOwner.lifecycle.removeObserver(observer)
            audioService.release()
        }
    }

    val screens =
        remember {
            listOf(
                WrappedScreenType.Welcome,
                WrappedScreenType.MinutesTease,
                WrappedScreenType.MinutesReveal,
                WrappedScreenType.TotalSongs,
                WrappedScreenType.TopSongReveal,
                WrappedScreenType.Top5Songs,
                WrappedScreenType.TotalAlbums,
                WrappedScreenType.TopAlbumReveal,
                WrappedScreenType.Top5Albums,
                WrappedScreenType.TotalArtists,
                WrappedScreenType.TopArtistReveal,
                WrappedScreenType.Top5Artists,
                WrappedScreenType.Playlist,
                WrappedScreenType.Conclusion,
            )
        }
    val pagerState = rememberPagerState(pageCount = { screens.size })
    val state by manager.state.collectAsStateWithLifecycle()
    val isMuted by audioService.isMuted.collectAsStateWithLifecycle()
    val messagePair =
        rememberSaveable(state.totalMinutes, saver = messagePairSaver) {
            WrappedRepository.getMessage(state.totalMinutes)
        }

    LaunchedEffect(Unit) {
        manager.prepare()
    }

    LaunchedEffect(pagerState, state.trackMap) {
        if (state.trackMap.isEmpty()) return@LaunchedEffect

        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { page ->
            val screen = screens.getOrNull(page)
            audioService.playTrack(state.trackMap[screen])
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.arrow_back), stringResource(R.string.back_button_desc), tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { audioService.toggleMute() }) {
                        val icon = if (isMuted) R.drawable.volume_off else R.drawable.volume_up
                        Icon(painterResource(icon), "Mute", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Black,
    ) { paddingValues ->
        VerticalPager(
            state = pagerState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) { page ->
            when (screens[page]) {
                is WrappedScreenType.Welcome -> {
                    WrappedIntro { scope.launch { pagerState.animateScrollToPage(page = 1) } }
                }

                is WrappedScreenType.MinutesTease -> {
                    WrappedMinutesTease(
                        messagePair = messagePair,
                        onNavigateForward = { scope.launch { pagerState.animateScrollToPage(page = 2) } },
                        isDataReady = state.isDataReady,
                    )
                }

                is WrappedScreenType.MinutesReveal -> {
                    WrappedMinutesScreen(
                        messagePair = messagePair,
                        totalMinutes = state.totalMinutes,
                        isVisible = pagerState.currentPage == screens.indexOf(WrappedScreenType.MinutesReveal),
                    )
                }

                is WrappedScreenType.TotalSongs -> {
                    WrappedTotalSongsScreen(
                        uniqueSongCount = state.uniqueSongCount,
                        isVisible = pagerState.currentPage == screens.indexOf(WrappedScreenType.TotalSongs),
                    )
                }

                is WrappedScreenType.TopSongReveal -> {
                    WrappedTopSongScreen(
                        topSong = state.topSongs.firstOrNull(),
                        isVisible = pagerState.currentPage == screens.indexOf(WrappedScreenType.TopSongReveal),
                    )
                }

                is WrappedScreenType.Top5Songs -> {
                    WrappedTop5SongsScreen(
                        topSongs = state.topSongs.take(5),
                        isVisible = pagerState.currentPage == screens.indexOf(WrappedScreenType.Top5Songs),
                    )
                }

                is WrappedScreenType.TotalAlbums -> {
                    WrappedTotalAlbumsScreen(
                        uniqueAlbumCount = state.totalAlbums,
                        isVisible = pagerState.currentPage == screens.indexOf(WrappedScreenType.TotalAlbums),
                    )
                }

                is WrappedScreenType.TopAlbumReveal -> {
                    WrappedTopAlbumScreen(
                        topAlbum = state.topAlbum,
                        isVisible = pagerState.currentPage == screens.indexOf(WrappedScreenType.TopAlbumReveal),
                    )
                }

                is WrappedScreenType.Top5Albums -> {
                    WrappedTop5AlbumsScreen(
                        topAlbums = state.top5Albums,
                        isVisible = pagerState.currentPage == screens.indexOf(WrappedScreenType.Top5Albums),
                    )
                }

                is WrappedScreenType.TotalArtists -> {
                    WrappedTotalArtistsScreen(
                        uniqueArtistCount = state.uniqueArtistCount,
                        isVisible = pagerState.currentPage == screens.indexOf(WrappedScreenType.TotalArtists),
                    )
                }

                is WrappedScreenType.TopArtistReveal -> {
                    WrappedTopArtistScreen(
                        topArtist = state.topArtists.firstOrNull(),
                        isVisible = pagerState.currentPage == screens.indexOf(WrappedScreenType.TopArtistReveal),
                    )
                }

                is WrappedScreenType.Top5Artists -> {
                    WrappedTop5ArtistsScreen(
                        topArtists = state.topArtists,
                        isVisible = pagerState.currentPage == screens.indexOf(WrappedScreenType.Top5Artists),
                    )
                }

                is WrappedScreenType.Playlist -> {
                    PlaylistPage()
                }

                is WrappedScreenType.Conclusion -> {
                    ConclusionPage(onClose = onClose)
                }
            }
        }
    }
}
