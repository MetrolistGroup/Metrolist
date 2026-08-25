/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.metrolist.music.ui.player.WearMusicPlayer

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
                        onOpenOptions = {
                            navController.navigate("menu")
                        }
                    )
                }
                composable("menu") {
                    WearMenuScreen(
                        onNavigateToSearch = { navController.navigate("search") },
                        onNavigateToSettings = { navController.navigate("settings") }
                    )
                }
                composable("search") {
                    WearSearchScreen()
                }
                composable("settings") {
                    WearSettingsScreen()
                }
            }
        }
    }
}
