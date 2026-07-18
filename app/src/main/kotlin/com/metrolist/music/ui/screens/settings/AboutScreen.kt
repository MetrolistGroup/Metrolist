/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

private data class Contributor(
    val name: String,
    val roleRes: Int,
    val githubHandle: String,
    val avatarUrl: String = "https://github.com/$githubHandle.png",
    val githubUrl: String = "https://github.com/$githubHandle",
    val sponsorUrl: String? = null,
    val polygon: RoundedPolygon? = null,
    val favoriteSongVideoId: String? = null
)

private data class CommunityLink(
    val labelRes: Int,
    val iconRes: Int,
    val url: String
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val leadDeveloper = Contributor(
    name = "Mo Agamy",
    roleRes = R.string.credits_lead_developer,
    githubHandle = "mostafaalagamy",
    polygon = MaterialShapes.Cookie9Sided,
    favoriteSongVideoId = "Mh2JWGWvy_Y"
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val collaborators = listOf(
    Contributor(name = "Adriel O'Connel", roleRes = R.string.credits_collaborator, githubHandle = "adrielGGmotion", sponsorUrl = "https://github.com/sponsors/adrielGGmotion", polygon = MaterialShapes.Cookie4Sided, favoriteSongVideoId = "m2zUrruKjDQ"),
    Contributor(name = "Nyx", roleRes = R.string.credits_collaborator, githubHandle = "nyxiereal", sponsorUrl = "https://github.com/sponsors/nyxiereal", polygon = MaterialShapes.Cookie12Sided, favoriteSongVideoId = "zselaN6zPXw"),
)

private val communityLinks = listOf(
    CommunityLink(R.string.credits_discord, R.drawable.discord, "https://discord.com/invite/zrdbeRG2Mt"),
    CommunityLink(R.string.credits_telegram, R.drawable.telegram, "https://t.me/metrolistapp"),
    CommunityLink(R.string.credits_view_repo, R.drawable.github, "https://github.com/MetrolistGroup/Metrolist"),
    CommunityLink(R.string.credits_license_name, R.drawable.info, "https://github.com/MetrolistGroup/Metrolist/blob/main/LICENSE")
)

private fun handleEasterEggClick(
    clickCount: Int,
    favoriteSongVideoId: String?,
    coroutineScope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    playerConnection: PlayerConnection?,
    wannaPlayStr: String,
    yeahStr: String,
    onCountUpdate: (Int) -> Unit
) {
    if (favoriteSongVideoId != null) {
        val newCount = clickCount + 1
        onCountUpdate(newCount)
        if (newCount >= 3) {
            onCountUpdate(0)
            coroutineScope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = wannaPlayStr,
                    actionLabel = yeahStr,
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    playerConnection?.playQueue(YouTubeQueue(WatchEndpoint(videoId = favoriteSongVideoId)))
                }
            }
        }
    }
}

@Composable
private fun ContributorAvatar(
    avatarUrl: String,
    sizeDp: Int,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null
) {
    val fallback = painterResource(R.drawable.about_icon)
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier.size(sizeDp.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 4.dp,
    ) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            placeholder = fallback,
            fallback = fallback,
            error = fallback,
        )
    }
}

@Composable
private fun DeveloperSocials(
    uriHandler: androidx.compose.ui.platform.UriHandler
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilledTonalButton(
            onClick = { uriHandler.openUri("https://metrolist.cc") },
            modifier = Modifier.weight(1f).height(48.dp)
        ) {
            Icon(painterResource(R.drawable.language), contentDescription = null)
        }
        FilledTonalButton(
            onClick = { uriHandler.openUri("https://github.com/mostafaalagamy") },
            modifier = Modifier.weight(1f).height(48.dp)
        ) {
            Icon(painterResource(R.drawable.github), contentDescription = null)
        }
        FilledTonalButton(
            onClick = { uriHandler.openUri("https://www.instagram.com/mostafaalagamy") },
            modifier = Modifier.weight(1f).height(48.dp)
        ) {
            Icon(painterResource(R.drawable.instagram), contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AboutScreen(
    navController: NavController,
) {
    val uriHandler = LocalUriHandler.current
    val playerConnection = LocalPlayerConnection.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val wannaPlayStr = stringResource(R.string.wanna_play_favorite_song)
    val yeahStr = stringResource(R.string.yeah)
    
    val windowInsets = LocalPlayerAwareWindowInsets.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                windowInsets.only(WindowInsetsSides.Top)
            )
        )

        Spacer(Modifier.height(16.dp))

        // App Header Section
        ElevatedCard(
            shape = RoundedCornerShape(32.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.ic_logo_oval),
                        contentDescription = null,
                        modifier = Modifier.size(84.dp)
                    )
                    Image(
                        painter = painterResource(R.drawable.about_icon),
                        contentDescription = stringResource(R.string.metrolist),
                        modifier = Modifier.size(64.dp)
                    )
                }
        
                Spacer(Modifier.width(20.dp))
        
                Column {
                    val metrolistName = stringResource(R.string.metrolist)
                        .lowercase(Locale.getDefault())
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                    Text(
                        text = metrolistName,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = (-0.5).sp
                    )
            
                    Spacer(Modifier.height(8.dp))
            
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = BuildConfig.VERSION_NAME,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = BuildConfig.ARCHITECTURE.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        if (BuildConfig.DEBUG) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                            ) {
                                Text(
                                    text = "DEBUG",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Lead Developer Hero Card
        ElevatedCard(
            shape = RoundedCornerShape(32.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    var leadClickCount by remember(leadDeveloper.name) { mutableIntStateOf(0) }
            
                    ContributorAvatar(
                        avatarUrl = leadDeveloper.avatarUrl,
                        sizeDp = 110,
                        shape = leadDeveloper.polygon?.toShape() ?: CircleShape,
                        contentDescription = leadDeveloper.name,
                        onClick = {
                            handleEasterEggClick(
                                clickCount = leadClickCount,
                                favoriteSongVideoId = leadDeveloper.favoriteSongVideoId,
                                coroutineScope = coroutineScope,
                                snackbarHostState = snackbarHostState,
                                playerConnection = playerConnection,
                                wannaPlayStr = wannaPlayStr,
                                yeahStr = yeahStr,
                                onCountUpdate = { leadClickCount = it }
                            )
                        }
                    )

                    Column(
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = leadDeveloper.name,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 38.sp,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            text = stringResource(R.string.credits_lead_developer),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                
                Spacer(Modifier.height(24.dp))
                
                DeveloperSocials(uriHandler)
                
                Spacer(Modifier.height(16.dp))
                
                Button(
                    onClick = { uriHandler.openUri("https://buymeacoffee.com/mostafaalagamy") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(painterResource(R.drawable.buymeacoffee), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.buy_mo_a_coffee), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        
        // Collaborators section - back to Material3SettingsGroup
        Material3SettingsGroup(
            title = stringResource(R.string.credits_collaborators_section),
            items = collaborators.map { contributor ->
                Material3SettingsItem(
                    leadingContent = {
                        var clickCount by remember(contributor.name) { mutableIntStateOf(0) }
                        ContributorAvatar(
                            avatarUrl = contributor.avatarUrl,
                            sizeDp = 48,
                            shape = contributor.polygon?.toShape() ?: CircleShape,
                            contentDescription = contributor.name,
                            onClick = {
                                handleEasterEggClick(
                                    clickCount = clickCount,
                                    favoriteSongVideoId = contributor.favoriteSongVideoId,
                                    coroutineScope = coroutineScope,
                                    snackbarHostState = snackbarHostState,
                                    playerConnection = playerConnection,
                                    wannaPlayStr = wannaPlayStr,
                                    yeahStr = yeahStr,
                                    onCountUpdate = { clickCount = it }
                                )
                            }
                        )
                    },
                    title = { Text(text = contributor.name, fontWeight = FontWeight.SemiBold) },
                    description = { Text(stringResource(contributor.roleRes)) },
                    trailingContent = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (contributor.sponsorUrl != null) {
                                Surface(
                                    onClick = { uriHandler.openUri(contributor.sponsorUrl) },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = painterResource(R.drawable.buymeacoffee),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Icon(
                                painter = painterResource(R.drawable.github),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = { uriHandler.openUri(contributor.githubUrl) }
                )
            }
        )

        Spacer(Modifier.height(32.dp))

        // Community & Info using standard Group
        Material3SettingsGroup(
            title = stringResource(R.string.community_and_info),
            items = communityLinks.map { link ->
                Material3SettingsItem(
                    icon = painterResource(link.iconRes),
                    title = { Text(stringResource(link.labelRes), fontWeight = FontWeight.SemiBold) },
                    description = if (link.labelRes == R.string.credits_license_name) {
                        { Text(stringResource(R.string.credits_license_desc)) }
                    } else null,
                    onClick = { uriHandler.openUri(link.url) }
                )
            }
        )

        Spacer(Modifier.height(48.dp))
        
        Text(
            text = stringResource(R.string.stands_with_palestine),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        
        Spacer(Modifier.height(48.dp))
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
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
        }
    )

    Box(Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(windowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
        )
    }
}
