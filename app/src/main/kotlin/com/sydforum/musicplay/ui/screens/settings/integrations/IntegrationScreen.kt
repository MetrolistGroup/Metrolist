/**
 * MusicPlayer Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.sydforum.musicplay.ui.screens.settings.integrations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.sydforum.musicplay.LocalPlayerAwareWindowInsets
import com.sydforum.musicplay.R
import com.sydforum.musicplay.ui.component.IconButton
import com.sydforum.musicplay.ui.component.IntegrationCard
import com.sydforum.musicplay.ui.component.IntegrationCardItem
import com.sydforum.musicplay.ui.utils.backToMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationScreen(
    navController: NavController
) {
    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        IntegrationCard(
            title = stringResource(R.string.general),
            items = listOf(
                IntegrationCardItem(
                    icon = painterResource(R.drawable.discord),
                    title = { Text(stringResource(R.string.discord_integration)) },
                    onClick = { navController.navigate("settings/integrations/discord") }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.music_note),
                    title = { Text(stringResource(R.string.lastfm_integration)) },
                    onClick = {
                        navController.navigate("settings/integrations/lastfm")
                    }
                )
            )
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.integrations)) },
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
        }
    )
}
