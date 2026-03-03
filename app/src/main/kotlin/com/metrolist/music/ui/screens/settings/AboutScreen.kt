/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

private data class Contributor(
    val name: String,
    val roleRes: Int,
    val githubHandle: String,
    val avatarUrl: String = "https://github.com/$githubHandle.png",
    val githubUrl: String = "https://github.com/$githubHandle",
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
    name = "Ther Vizhi",
    roleRes = R.string.credits_lead_developer,
    githubHandle = "thervizhi",
    polygon = MaterialShapes.Cookie9Sided,
    favoriteSongVideoId = "Mh2JWGWvy_Y"
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val collaborators = listOf(
    Contributor(name = "Adriel O'Connel", roleRes = R.string.credits_collaborator, githubHandle = "adrielGGmotion", polygon = MaterialShapes.Cookie4Sided, favoriteSongVideoId = "m2zUrruKjDQ"),
    Contributor(name = "Avrumi Sternheim", roleRes = R.string.credits_collaborator, githubHandle = "alltechdev", polygon = MaterialShapes.Square, favoriteSongVideoId = "bezmD6ZYXJY"), // Best for Android penguin
    Contributor(name = "Nyx", roleRes = R.string.credits_collaborator, githubHandle = "nyxiereal", polygon = MaterialShapes.Cookie12Sided, favoriteSongVideoId = "zselaN6zPXw"), // More mass for face
)

private val communityLinks = listOf(
    CommunityLink(R.string.credits_discord, R.drawable.discord, "https://discord.gg/rJwDxXsf8c"),
    CommunityLink(R.string.credits_telegram, R.drawable.telegram, "https://t.me/tes"),
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
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 8.dp, top = 8.dp),
        textAlign = TextAlign.Start
    )
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
    val fallback = painterResource(R.drawable.small_icon)
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier.size(sizeDp.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
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

/** Action button for the 3-segment row under the lead developer */
@Composable
private fun RowScope.SegmentedActionButton(
    label: String,
    iconRes: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.weight(1f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.height(80.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A generic clickable card for secondary actions like Buy Me a Coffee */
@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    iconRes: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(20.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(Modifier.width(20.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AboutScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val uriHandler = LocalUriHandler.current
    val playerConnection = LocalPlayerConnection.current
    val coroutineScope = rememberCoroutineScope()
    val localSnackbarHostState = remember { SnackbarHostState() }
    val wannaPlayStr = stringResource(R.string.wanna_play_favorite_song)
    val yeahStr = stringResource(R.string.yeah)
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                    )
                )
                .verticalScroll(rememberScrollState())
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(
                Modifier.windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
                )
            )
    
            Spacer(Modifier.height(16.dp))
    
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(MaterialShapes.SoftBurst.toShape())
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.small_icon),
                            contentDescription = stringResource(R.string.metrolist),
                            colorFilter = ColorFilter.tint(
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                blendMode = BlendMode.SrcIn,
                            ),
                            modifier = Modifier.size(40.dp)
                        )
                    }
            
                    Spacer(Modifier.height(16.dp))
            
                    Text(
                        text = stringResource(R.string.metrolist),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = MaterialTheme.typography.headlineMedium.letterSpacing
                    )
            
                    Spacer(Modifier.height(8.dp))
            
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        val archText = BuildConfig.ARCHITECTURE.uppercase()
                        val versionText = if (BuildConfig.DEBUG) {
                            stringResource(R.string.app_version_info, BuildConfig.VERSION_NAME, "$archText • DEBUG")
                        } else {
                            stringResource(R.string.app_version_info, BuildConfig.VERSION_NAME, archText)
                        }
                        Text(
                            text = versionText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
    
            Spacer(Modifier.height(32.dp))
    
            LinearWavyProgressIndicator(
                progress = { 1f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                trackColor = Color.Transparent,
                amplitude = { 1f }
            )
    
            Spacer(Modifier.height(32.dp))
    
            SectionHeader(stringResource(R.string.credits_lead_developer))
    
            var leadClickCount by remember(leadDeveloper.name) { mutableIntStateOf(0) }
    
            // Large Avatar
            ContributorAvatar(
                avatarUrl = leadDeveloper.avatarUrl,
                sizeDp = 180,
                shape = leadDeveloper.polygon?.toShape() ?: CircleShape,
                contentDescription = leadDeveloper.name,
                onClick = {
                    handleEasterEggClick(
                        clickCount = leadClickCount,
                        favoriteSongVideoId = leadDeveloper.favoriteSongVideoId,
                        coroutineScope = coroutineScope,
                        snackbarHostState = localSnackbarHostState,
                        playerConnection = playerConnection,
                        wannaPlayStr = wannaPlayStr,
                        yeahStr = yeahStr,
                        onCountUpdate = { leadClickCount = it }
                    )
                }
            )
    
            Spacer(Modifier.height(24.dp))
    
            Text(
                text = leadDeveloper.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
    
            Spacer(Modifier.height(32.dp))
    
            // Segmented buttons (Website, GitHub, Discord)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    SegmentedActionButton(
                        label = stringResource(R.string.credits_website),
                        iconRes = R.drawable.language,
                        onClick = { uriHandler.openUri("https://metrolist.meowery.eu") }
                    )
                    
                    Box(modifier = Modifier.width(1.dp).height(80.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f)))
                    
                    SegmentedActionButton(
                        label = stringResource(R.string.credits_github),
                        iconRes = R.drawable.github,
                        onClick = { uriHandler.openUri("https://github.com/mostafaalagamy") }
                    )
                    
                    Box(modifier = Modifier.width(1.dp).height(80.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f)))
                    
                    SegmentedActionButton(
                        label = stringResource(R.string.credits_discord),
                        iconRes = R.drawable.discord,
                        onClick = { uriHandler.openUri("https://discord.gg/rJwDxXsf8c") }
                    )
                }
            }
    
            Spacer(Modifier.height(16.dp))
    
            ActionCard(
                title = stringResource(R.string.like_what_i_do),
                subtitle = stringResource(R.string.buy_mo_a_coffee),
                iconRes = R.drawable.buymeacoffee,
                onClick = { uriHandler.openUri("https://buymeacoffee.com/mostafaalagamy") }
            )
    
            Spacer(Modifier.height(48.dp))
    
            SectionHeader(stringResource(R.string.credits_collaborators_section))
    
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    collaborators.forEachIndexed { index, contributor ->
                        var clickCount by remember(contributor.name) { mutableIntStateOf(0) }
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = contributor.name,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            supportingContent = { Text(stringResource(contributor.roleRes)) },
                            leadingContent = {
                                    ContributorAvatar(
                                        avatarUrl = contributor.avatarUrl,
                                        sizeDp = 56,
                                        shape = contributor.polygon?.toShape() ?: CircleShape,
                                        contentDescription = contributor.name,
                                        onClick = {
                                        handleEasterEggClick(
                                            clickCount = clickCount,
                                            favoriteSongVideoId = contributor.favoriteSongVideoId,
                                            coroutineScope = coroutineScope,
                                            snackbarHostState = localSnackbarHostState,
                                            playerConnection = playerConnection,
                                            wannaPlayStr = wannaPlayStr,
                                            yeahStr = yeahStr,
                                            onCountUpdate = { clickCount = it }
                                        )
                                    }
                                )
                            },
                            trailingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.github),
                                    contentDescription = stringResource(R.string.credits_github),
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { uriHandler.openUri(contributor.githubUrl) }
                        )
                        
                        if (index < collaborators.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
    
            Spacer(Modifier.height(32.dp))
    
            SectionHeader(stringResource(R.string.community_and_info))
    
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    communityLinks.forEachIndexed { index, link ->
                        ListItem(
                            headlineContent = { Text(stringResource(link.labelRes), fontWeight = FontWeight.SemiBold) },
                            supportingContent = if (link.labelRes == R.string.credits_license_name) {
                                { Text(stringResource(R.string.credits_license_desc)) }
                            } else null,
                            leadingContent = { Icon(painterResource(link.iconRes), null, modifier = Modifier.size(24.dp)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { uriHandler.openUri(link.url) }
                        )
                        
                        if (index < communityLinks.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
    
            Spacer(Modifier.height(32.dp))
            
            Text(
                text = stringResource(R.string.stands_with_palestine),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            
            Spacer(Modifier.height(40.dp))
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
            },
            scrollBehavior = scrollBehavior,
        )

        androidx.compose.material3.SnackbarHost(
            hostState = localSnackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
        )
    }
}
