/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.metrolist.music.core.R
import com.metrolist.music.constants.AppLanguageKey
import com.metrolist.music.constants.ContentCountryKey
import com.metrolist.music.constants.ContentLanguageKey
import com.metrolist.music.constants.CountryCodeToName
import com.metrolist.music.constants.LanguageCodeToName
import com.metrolist.music.ui.player.WearMusicPlayer
import com.metrolist.music.utils.SearchRoutes
import timber.log.Timber

@Composable
fun WearApp() {
    val navController = rememberSwipeDismissableNavController()
    
    MaterialTheme {
        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
        ) {
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = "player"
            ) {
                composable("player") {
                    WearMusicPlayer(
                        onNavigateToSearch = {
                            navController.navigate("search")
                        },
                        onNavigateToSettings = {
                            navController.navigate("settings")
                        },
                        onNavigateToLibrary = {
                            navController.navigate("library")
                        },
                        onNavigateToLiked = {
                            navController.navigate("library/liked")
                        },
                        onNavigateToDownloads = {
                            navController.navigate("library/downloads")
                        },
                        onNavigateToHistory = {
                            navController.navigate("library/history")
                        },
                        onNavigateToVolume = {
                            navController.navigate("volume")
                        },
                        onNavigateToQueue = {
                            // Already handled by HorizontalPager, but can navigate specifically if needed
                        }
                    )
                }
                composable("menu") {
                    WearMenuScreen(
                        onNavigateToSearch = {
                            try {
                                navController.navigate("search")
                            } catch (e: Exception) {
                                Timber.e(e, "Navigation to search failed")
                            }
                        },
                        onNavigateToSettings = {
                            try {
                                navController.navigate("settings")
                            } catch (e: Exception) {
                                Timber.e(e, "Navigation to settings failed")
                            }
                        },
                        onNavigateToLogin = {
                            try {
                                navController.navigate("login")
                            } catch (e: Exception) {
                                Timber.e(e, "Navigation to login failed")
                            }
                        }
                    )
                }
                composable("search") {
                    WearSearchScreen(
                        onSearch = { q ->
                            try {
                                navController.navigate(SearchRoutes.resultRoute(q))
                            } catch (e: Exception) {
                                Timber.tag("WearApp").e(e, "Navigation to search result failed")
                            }
                        },
                        onItemClick = {
                            navController.navigate("player") {
                                popUpTo("player") { inclusive = true }
                            }
                        }
                    )
                }
                composable(SearchRoutes.ROUTE) {
                    WearSearchScreen(
                        onSearch = { q ->
                            try {
                                navController.navigate(SearchRoutes.resultRoute(q))
                            } catch (e: Exception) {
                                Timber.tag("WearApp").e(e, "Navigation to search result failed")
                            }
                        },
                        onItemClick = {
                            navController.navigate("player") {
                                popUpTo("player") { inclusive = true }
                            }
                        }
                    )
                }
                composable("login") {
                    WearLoginScreen()
                }
                composable("settings") {
                    WearSettingsScreen(
                        onNavigateToLogin = { navController.navigate("login") },
                        onNavigateToLanguage = { navController.navigate("settings/language") },
                        onNavigateToContentLanguage = { navController.navigate("settings/content_language") },
                        onNavigateToContentCountry = { navController.navigate("settings/content_country") }
                    )
                }
                composable("settings/language") {
                    WearLanguageScreen(
                        title = stringResource(R.string.app_language),
                        preferenceKey = AppLanguageKey,
                        options = LanguageCodeToName,
                        onSelected = { navController.navigateUp() }
                    )
                }
                composable("settings/content_language") {
                    WearLanguageScreen(
                        title = stringResource(R.string.content_language),
                        preferenceKey = ContentLanguageKey,
                        options = LanguageCodeToName,
                        onSelected = { navController.navigateUp() }
                    )
                }
                composable("settings/content_country") {
                    WearLanguageScreen(
                        title = stringResource(R.string.content_country),
                        preferenceKey = ContentCountryKey,
                        options = CountryCodeToName,
                        onSelected = { navController.navigateUp() }
                    )
                }
                composable("volume") {
                    WearVolumeScreen()
                }
                composable("library") {
                    WearLibraryScreen(
                        onNavigateToSongs = { navController.navigate("library/songs") },
                        onNavigateToAlbums = { navController.navigate("library/albums") },
                        onNavigateToArtists = { navController.navigate("library/artists") },
                        onNavigateToPlaylists = { navController.navigate("library/playlists") },
                        onNavigateToLiked = { navController.navigate("library/liked") },
                        onNavigateToDownloads = { navController.navigate("library/downloads") },
                        onNavigateToHistory = { navController.navigate("library/history") },
                        onNavigateToLogin = { navController.navigate("login") }
                    )
                }
                composable("library/songs") {
                    WearLibrarySongsScreen(
                        onItemClick = {
                            navController.navigate("player") {
                                popUpTo("player") { inclusive = true }
                            }
                        }
                    )
                }
                composable("library/liked") {
                    WearLibrarySongsScreen(
                        filterLiked = true,
                        onItemClick = {
                            navController.navigate("player") {
                                popUpTo("player") { inclusive = true }
                            }
                        }
                    )
                }
                composable("library/downloads") {
                    WearLibrarySongsScreen(
                        filterDownloaded = true,
                        onItemClick = {
                            navController.navigate("player") {
                                popUpTo("player") { inclusive = true }
                            }
                        }
                    )
                }
                composable("library/history") {
                    WearLibrarySongsScreen(
                        filterHistory = true,
                        onItemClick = {
                            navController.navigate("player") {
                                popUpTo("player") { inclusive = true }
                            }
                        }
                    )
                }
                composable("library/albums") {
                    WearLibraryAlbumsScreen(
                        onAlbumClick = { _ -> 
                            // TODO: Navigate to album detail
                        }
                    )
                }
                composable("library/artists") {
                    WearLibraryArtistsScreen(
                        onArtistClick = { _ ->
                            // TODO: Navigate to artist detail
                        }
                    )
                }
                composable("library/playlists") {
                    WearLibraryPlaylistsScreen(
                        onPlaylistClick = { _ ->
                            // TODO: Navigate to playlist detail
                        }
                    )
                }
            }
        }
    }
}
