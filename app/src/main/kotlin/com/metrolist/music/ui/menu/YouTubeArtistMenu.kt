/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.SpeedDialItem
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.NewAction
import com.metrolist.music.ui.component.NewActionGrid
import com.metrolist.music.ui.component.YouTubeListItem
import com.metrolist.music.utils.ArtistNameAliases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)

@Composable
fun YouTubeArtistMenu(
    artist: ArtistItem,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val libraryArtist by database.artist(artist.id).collectAsStateWithLifecycle(initialValue = null)
    val listenTogetherManager = LocalListenTogetherManager.current
    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost
    val isPinned by database.speedDialDao.isPinned(artist.id).collectAsStateWithLifecycle(initialValue = false)
    val coroutineScope = rememberCoroutineScope()

    YouTubeListItem(
        item = artist,
        trailingContent = {},
    )

    HorizontalDivider()

    Spacer(modifier = Modifier.height(12.dp))

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    LazyColumn(
        contentPadding = PaddingValues(
            start = 0.dp,
            top = 0.dp,
            end = 0.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        item {
            NewActionGrid(
                actions = buildList {
                    if (!isGuest) {
                        artist.radioEndpoint?.let { watchEndpoint ->
                            add(
                                NewAction(
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.radio),
                                            contentDescription = null,
                                            modifier = Modifier.size(28.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    text = stringResource(R.string.start_radio),
                                    onClick = {
                                        playerConnection.playQueue(YouTubeQueue(watchEndpoint))
                                        onDismiss()
                                    }
                                )
                            )
                        }
                    }

                    add(
                        NewAction(
                            icon = {
                                Icon(
                                    painter = painterResource(if (isPinned) R.drawable.remove else R.drawable.add),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            text = if (isPinned) stringResource(R.string.unpin) else stringResource(R.string.pin),
                            onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    if (isPinned) {
                                        database.speedDialDao.delete(artist.id)
                                    } else {
                                        database.speedDialDao.insert(SpeedDialItem.fromYTItem(artist))
                                    }
                                }
                                onDismiss()
                            }
                        )
                    )

                    add(
                        NewAction(
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.share),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            text = stringResource(R.string.share),
                            onClick = {
                                val intent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, artist.shareLink)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                                onDismiss()
                            }
                        )
                    )
                },
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                columns = if (isGuest) 1 else 3
            )
        }

        item {
            Material3MenuGroup(
                items = listOf(
                    Material3MenuItemData(
                        title = {
                            Text(text = if (libraryArtist?.artist?.bookmarkedAt != null) stringResource(R.string.subscribed) else stringResource(R.string.subscribe))
                        },
                        icon = {
                            Icon(
                                painter = painterResource(
                                    if (libraryArtist?.artist?.bookmarkedAt != null) {
                                        R.drawable.subscribed
                                    } else {
                                        R.drawable.subscribe
                                    }
                                ),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            database.query {
                                val libraryArtist = libraryArtist
                                if (libraryArtist != null) {
                                    update(libraryArtist.artist.toggleLike())
                                } else {
                                    insert(
                                        ArtistEntity(
                                            id = artist.id,
                                            name = ArtistNameAliases.resolve(artist.id, artist.title),
                                            channelId = artist.channelId,
                                            thumbnailUrl = artist.thumbnail,
                                        ).toggleLike()
                                    )
                                }
                            }
                        }
                    )
                )
            )
        }
    }
}
