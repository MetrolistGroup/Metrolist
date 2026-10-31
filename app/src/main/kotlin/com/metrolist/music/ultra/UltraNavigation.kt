package com.metrolist.music.ultra

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import ultra.hooks.UltraDestination
import ultra.hooks.UltraHooks
import ultra.hooks.UltraNavigator

/*
 * Glue for seam S8 (NavigationBuilder.kt). This package is the only place that sees both upstream
 * types (NavGraphBuilder, LocalPlayerAwareWindowInsets, the settings top bar) and ultra types.
 * The ultra contracts stay free of any navigation or app-UI type.
 */

/**
 * Registers every destination contributed through [UltraHooks] into [graph].
 * A plain function (not an extension) so the seam can call it by fully-qualified name without adding
 * an import line to the upstream file.
 */
fun ultraDestinations(
    graph: NavGraphBuilder,
    navController: NavController,
) {
    val navigator = NavControllerNavigator(navController)
    UltraHooks.destinations().forEach { destination ->
        graph.composable(destination.route) {
            UltraScreenFrame(destination, navController, navigator)
        }
    }
}

/**
 * Gives ultra screens the same chrome as upstream's own settings screens: content padded by the
 * player-aware insets (clear of the status bar, top bar, nav bar and mini player), with a top bar
 * overlaid on top, a back arrow, and long-press-to-main. Feature screens only draw their body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UltraScreenFrame(
    destination: UltraDestination,
    navController: NavController,
    navigator: UltraNavigator,
) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current),
    ) {
        destination.content(navigator)
    }

    TopAppBar(
        title = { Text(stringResource(destination.titleRes)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

private class NavControllerNavigator(
    private val navController: NavController,
) : UltraNavigator {
    override fun navigate(route: String) = navController.navigate(route)

    override fun navigateUp() {
        navController.navigateUp()
    }
}
