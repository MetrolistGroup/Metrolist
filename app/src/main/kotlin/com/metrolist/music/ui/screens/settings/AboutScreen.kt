/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        Spacer(Modifier.height(4.dp))

        Image(
            painter = painterResource(R.drawable.small_icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(
                MaterialTheme.colorScheme.onBackground,
                BlendMode.SrcIn
            ),
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { },
        )

        Row(
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = "METROLIST",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.secondary,
                        shape = CircleShape,
                    )
                    .padding(
                        horizontal = 6.dp,
                        vertical = 2.dp,
                    ),
            )

            Spacer(Modifier.width(4.dp))

            if (BuildConfig.DEBUG) {
                Spacer(Modifier.width(4.dp))

                Text(
                    text = "DEBUG",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.secondary,
                            shape = CircleShape,
                        )
                        .padding(
                            horizontal = 6.dp,
                            vertical = 2.dp,
                        ),
                )
            } else {
                Spacer(Modifier.width(4.dp))

                Text(
                    text = BuildConfig.ARCHITECTURE.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.secondary,
                            shape = CircleShape,
                        )
                        .padding(
                            horizontal = 6.dp,
                            vertical = 2.dp,
                        ),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "MO AGAMY",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
        )

        Spacer(Modifier.height(8.dp))

        Row {
            IconButton(
                onClick = {
                    uriHandler.openUri("https://github.com/mostafaalagamy")
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.github),
                    contentDescription = null
                )
            }

            IconButton(
                onClick = {
                    uriHandler.openUri("https://buymeacoffee.com/mostafaalagamy")
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.buymeacoffee),
                    contentDescription = null
                )
            }

            IconButton(
                onClick = {
                    uriHandler.openUri("https://metrolist.meowery.eu")
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.language),
                    contentDescription = null
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "COLLABORATORS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.secondary,
                    shape = CircleShape,
                )
                .padding(
                    horizontal = 6.dp,
                    vertical = 2.dp,
                ),
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "Damian Sobczak",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.clickable {
                uriHandler.openUri("https://github.com/FullerBread2032")
            }
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Adriel O'Connel",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.clickable {
                uriHandler.openUri("https://github.com/adrielGGmotion")
            }
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Nyx",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.clickable {
                uriHandler.openUri("https://github.com/nyxiereal")
            }
        )

        Spacer(Modifier.height(32.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.about)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    )
}
