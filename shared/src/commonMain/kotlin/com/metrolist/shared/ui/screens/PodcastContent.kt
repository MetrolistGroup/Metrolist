@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.screens

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_download
import com.metrolist.shared.generated.resources.add_to_queue
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.episode_actions
import com.metrolist.shared.generated.resources.episode_duration_hours
import com.metrolist.shared.generated.resources.episode_duration_minutes
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.no_episode_description
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.play_latest
import com.metrolist.shared.generated.resources.play_next
import com.metrolist.shared.generated.resources.podcast
import com.metrolist.shared.generated.resources.podcast_actions
import com.metrolist.shared.generated.resources.remove_from_saved_episodes
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.save
import com.metrolist.shared.generated.resources.save_episode
import com.metrolist.shared.generated.resources.saved
import com.metrolist.shared.generated.resources.share
import com.metrolist.shared.generated.resources.show_less
import com.metrolist.shared.generated.resources.show_more
import com.metrolist.shared.generated.resources.unable_to_load_podcast
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PodcastPage
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.EpisodePlayedIndicator
import com.metrolist.shared.ui.components.ExpandableText
import com.metrolist.shared.ui.components.ModalBottomSheet
import com.metrolist.shared.ui.components.SongThumbnailPlayingIndicator
import com.metrolist.shared.ui.components.episodeProgressFraction
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.loadingShimmer
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.CursorDropdownMenu
import com.metrolist.shared.ui.menu.contextMenuAnchor
import com.metrolist.shared.ui.menu.onSecondaryClick
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.theme.gridCellShape
import com.metrolist.shared.ui.theme.uiEnhanced
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.desktopArrowScroll
import com.metrolist.shared.ui.utils.segmentedGroupShape
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.utils.isDesktop
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import com.metrolist.shared.ui.components.rememberModalSheetState as rememberBottomSheetState

@Composable
fun PodcastContent(
    page: PodcastPage?,
    isLoading: Boolean,
    isSaving: Boolean,
    error: String?,
    onBackClick: (() -> Unit)?,
    onRetry: () -> Unit,
    onPlay: () -> Unit,
    onToggleSaved: () -> Unit,
    onPodcastMenuClick: () -> Unit,
    onEpisodeClick: (EpisodeItem) -> Unit,
    onToggleEpisodeSaved: (EpisodeItem) -> Unit,
    onPlayNext: (EpisodeItem) -> Unit,
    onAddToQueue: (EpisodeItem) -> Unit,
    onDownload: ((EpisodeItem) -> Unit)?,
    onShare: (EpisodeItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    currentEpisodeId: String? = null,
    isPlaying: Boolean = false,
    onChannelClick: ((String) -> Unit)? = null,
    detailsInDialog: Boolean = false,
    onLoadMore: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState, page?.continuation, page?.episodes?.size, isLoading, error) {
        if (page?.continuation == null || isLoading || error != null || onLoadMore == null) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo
                .lastOrNull()
                ?.index
                ?.let { layout.totalItemsCount == page.episodes.size + 2 && it >= layout.totalItemsCount - 5 } == true
        }.collect { nearEnd -> if (nearEnd) onLoadMore() }
    }
    var detailsEpisode by remember { mutableStateOf<EpisodeItem?>(null) }
    detailsEpisode?.let { episode ->
        EpisodeDetails(
            episode,
            asDialog = detailsInDialog,
            onDismiss = { detailsEpisode = null },
            onPlay = {
                detailsEpisode = null
                onEpisodeClick(episode)
            },
            channelId = episode.author?.id?.takeIf { onChannelClick != null },
            onChannelClick = {
                detailsEpisode = null
                onChannelClick?.invoke(it)
            },
        )
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        page?.podcast?.title ?: stringResource(Res.string.podcast),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick, shapes = IconButtonDefaults.shapes()) {
                            Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
    ) { scaffoldPadding ->
        when {
            isLoading && page == null -> {
                PodcastLoadingPlaceholder(Modifier.padding(scaffoldPadding))
            }

            page == null -> {
                Column(
                    Modifier.fillMaxSize().padding(scaffoldPadding).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(Res.string.unable_to_load_podcast), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.retry)) }
                }
            }

            else -> {
                DragScrollableColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().desktopArrowScroll(listState),
                    contentPadding =
                        PaddingValues(
                            start = 16.dp,
                            top = scaffoldPadding.calculateTopPadding() + contentPadding.calculateTopPadding() + 8.dp,
                            end = 16.dp,
                            bottom = scaffoldPadding.calculateBottomPadding() + contentPadding.calculateBottomPadding() + 16.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item(key = "podcast_header") {
                        PodcastHeader(
                            page = page,
                            isSaving = isSaving,
                            onPlay = onPlay,
                            onToggleSaved = onToggleSaved,
                            onMenuClick = onPodcastMenuClick,
                            onChannelClick = onChannelClick,
                        )
                    }
                    itemsIndexed(page.episodes, key = { _, it -> it.id }) { index, episode ->
                        EpisodeRow(
                            episode = episode,
                            isFirst = index == 0,
                            isLast = index == page.episodes.lastIndex,
                            isActive = episode.id == currentEpisodeId,
                            isPlaying = isPlaying,
                            onClick = { onEpisodeClick(episode) },
                            onToggleSaved = { onToggleEpisodeSaved(episode) },
                            onPlayNext = { onPlayNext(episode) },
                            onAddToQueue = { onAddToQueue(episode) },
                            onDownload = onDownload?.let { { it(episode) } },
                            onShare = { onShare(episode) },
                            onShowDetails = { detailsEpisode = episode },
                        )
                    }
                    if (error != null) {
                        item(key = "podcast_load_error") {
                            Column(Modifier.padding(16.dp)) {
                                Text(error, color = MaterialTheme.colorScheme.error)
                                Button(onClick = onRetry, enabled = !isLoading, shapes = ButtonDefaults.shapes()) {
                                    Text(stringResource(Res.string.retry))
                                }
                            }
                        }
                    } else if (page.continuation != null && onLoadMore != null) {
                        item(key = "podcast_load_more") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                if (isLoading) ContainedLoadingIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PodcastLoadingPlaceholder(modifier: Modifier = Modifier) {
    val loading = stringResource(Res.string.loading)
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp)
            .semantics { contentDescription = loading }
            .loadingShimmer(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(color))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.fillMaxWidth().height(24.dp).background(color))
                Spacer(Modifier.fillMaxWidth(0.6f).height(16.dp).background(color))
            }
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(color),
        )
        repeat(6) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun PodcastHeader(
    page: PodcastPage,
    isSaving: Boolean,
    onPlay: () -> Unit,
    onToggleSaved: () -> Unit,
    onMenuClick: () -> Unit,
    onChannelClick: ((String) -> Unit)?,
) {
    val title: @Composable () -> Unit = {
        Text(
            page.podcast.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        page.podcast.author?.let { author ->
            val channelId =
                (page.podcast.channelId ?: page.episodes.firstNotNullOfOrNull { e -> e.author?.id?.takeIf { it.startsWith("UC") } })
                    ?.takeIf { onChannelClick != null }
            Text(
                author,
                style = MaterialTheme.typography.labelLarge,
                color = if (channelId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (channelId != null) Modifier.clickable { onChannelClick?.invoke(channelId) } else Modifier,
            )
        }
    }
    val description: @Composable () -> Unit = {
        page.description?.takeIf(String::isNotBlank)?.let {
            ExpandableText(
                text = it,
                collapsedMaxLines = 3,
                showMoreText = stringResource(Res.string.show_more),
                showLessText = stringResource(Res.string.show_less),
            )
        }
    }
    val actions: @Composable () -> Unit = {
        FlowRow(
            Modifier.fillMaxWidth().padding(end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onPlay, enabled = page.episodes.any { !it.isUnavailable }, shapes = ButtonDefaults.shapes()) {
                Icon(MetrolistIcons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.play_latest))
            }
            val saveLabel = stringResource(if (page.saved) Res.string.saved else Res.string.save)
            val loadingLabel = stringResource(Res.string.loading)
            FilledTonalButton(
                onClick = onToggleSaved,
                enabled = !isSaving,
                modifier =
                    Modifier.weight(1f).semantics {
                        if (isSaving) {
                            contentDescription = saveLabel
                            stateDescription = loadingLabel
                        }
                    },
                shapes = ButtonDefaults.shapes(),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(saveLabel, modifier = Modifier.alpha(if (isSaving) 0f else 1f))
                    if (isSaving) {
                        ContainedLoadingIndicator(
                            Modifier.size(18.dp),
                        )
                    }
                }
            }
            FilledTonalIconButton(
                onClick = onMenuClick,
                modifier = Modifier.size(36.dp).navigationPopupSource(),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.podcast_actions))
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        if (maxWidth < 620.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    PodcastArtwork(page, Modifier.size(96.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { title() }
                }
                description()
                actions()
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                PodcastArtwork(page, Modifier.size(160.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    title()
                    description()
                    actions()
                }
            }
        }
    }
}

@Composable
private fun PodcastArtwork(
    page: PodcastPage,
    modifier: Modifier,
) {
    AsyncImage(
        url = page.podcast.thumbnail,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(uiEnhanced(8.dp, 16.dp))),
    )
}

@Composable
private fun formatEpisodeDuration(seconds: Long): String {
    val hours = (seconds / 3600).toInt()
    val minutes = ((seconds % 3600) / 60).toInt()
    val hoursText = pluralStringResource(Res.plurals.episode_duration_hours, hours, hours)
    val minutesText = pluralStringResource(Res.plurals.episode_duration_minutes, maxOf(minutes, 1), maxOf(minutes, 1))
    return when {
        hours > 0 && minutes > 0 -> "$hoursText $minutesText"
        hours > 0 -> hoursText
        else -> minutesText
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeItem,
    isFirst: Boolean,
    isLast: Boolean,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onToggleSaved: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownload: (() -> Unit)?,
    onShare: () -> Unit,
    onShowDetails: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val menuAnchor = rememberCursorContextMenuState()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val progress = episodeProgressFraction(episode.id, episode.duration, episode.playbackProgressPercent)
    val primaryTint =
        when {
            progress == null -> 0f
            progress >= 1f -> 0.24f
            else -> 0.12f
        }
    val shape = segmentedGroupShape(isFirst, isLast, isPressed)
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    enabled = !episode.isUnavailable,
                    onClick = onClick,
                ).then(if (isDesktop) Modifier.contextMenuAnchor(menuAnchor) else Modifier)
                .onSecondaryClick { showMenu = true },
        colors =
            CardDefaults.cardColors(
                containerColor = lerp(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.primary, primaryTint),
            ),
        shape = shape,
    ) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    AsyncImage(
                        url = episode.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(gridCellShape()),
                    )
                    SongThumbnailPlayingIndicator(
                        isActive = isActive && !Settings.shared.uiEnhancements,
                        isPlaying = isPlaying,
                        shape = gridCellShape(),
                    )
                }
                Text(
                    episode.title,
                    modifier = Modifier.weight(1f).clickable(onClick = onShowDetails),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(36.dp).navigationPopupSource(),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.episode_actions), Modifier.size(20.dp))
                    }
                    val menuContent: @Composable ColumnScope.() -> Unit = {
                        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                            EpisodeAction(
                                stringResource(if (episode.saved) Res.string.remove_from_saved_episodes else Res.string.save_episode),
                            ) {
                                showMenu = false
                                onToggleSaved()
                            }
                            EpisodeAction(stringResource(Res.string.play_next), enabled = !episode.isUnavailable) {
                                showMenu = false
                                onPlayNext()
                            }
                            EpisodeAction(stringResource(Res.string.add_to_queue), enabled = !episode.isUnavailable) {
                                showMenu = false
                                onAddToQueue()
                            }
                            if (onDownload != null) {
                                EpisodeAction(stringResource(Res.string.action_download), enabled = !episode.isUnavailable) {
                                    showMenu = false
                                    onDownload()
                                }
                            }
                            EpisodeAction(stringResource(Res.string.share)) {
                                showMenu = false
                                onShare()
                            }
                        }
                    }
                    if (isDesktop) {
                        CursorDropdownMenu(showMenu, menuAnchor.offset, { showMenu = false }, content = menuContent)
                    } else {
                        DropdownMenuPopup(expanded = showMenu, onDismissRequest = { showMenu = false }, content = menuContent)
                    }
                }
            }
            Column(Modifier.padding(end = 8.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                episode.description?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                EpisodePlayedIndicator(episode.id, episode.duration, serverProgressPercent = episode.playbackProgressPercent)
                val stats = episodeStats(episode)
                if (stats.isNotBlank()) {
                    Text(
                        stats,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun episodeStats(episode: EpisodeItem): String =
    listOfNotNull(
        episode.subtitle ?: listOfNotNull(episode.viewCountText, episode.publishDateText).joinToString(" • ").takeIf(String::isNotBlank),
        episode.durationText ?: episode.duration?.let { formatEpisodeDuration(it) },
    ).joinToString(" • ")

private fun cleanDescription(text: String): String =
    text
        .lines()
        .filterNot { line -> line.trim().let { it.length > 3 && it.all { c -> c in "=-_*~" } } }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

@Composable
private fun EpisodeDetailsBody(
    episode: EpisodeItem,
    onPlay: () -> Unit,
    channelId: String?,
    onChannelClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            AsyncImage(
                url = episode.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            FilledIconButton(
                onClick = onPlay,
                enabled = !episode.isUnavailable,
                modifier = Modifier.size(56.dp),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(MetrolistIcons.Rounded.PlayArrow, stringResource(Res.string.play), Modifier.size(32.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(episode.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            episode.author?.name?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (channelId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (channelId != null) Modifier.clickable { onChannelClick(channelId) } else Modifier,
                )
            }
        }
        episodeStats(episode).takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        val description = episode.description?.let(::cleanDescription)?.takeIf(String::isNotBlank)
        Column(Modifier.weight(1f, fill = false).verticalScrollWithDrag(rememberScrollState())) {
            if (description != null) {
                ExpandableText(
                    text = description,
                    collapsedMaxLines = Int.MAX_VALUE,
                    showMoreText = "",
                    showLessText = "",
                )
            } else {
                Text(
                    stringResource(Res.string.no_episode_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeDetails(
    episode: EpisodeItem,
    asDialog: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    channelId: String?,
    onChannelClick: (String) -> Unit,
) {
    if (asDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { EpisodeDetailsBody(episode, onPlay, channelId, onChannelClick, Modifier.width(420.dp)) },
            confirmButton = {
                TextButton(
                    onClick = onDismiss,
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.close)) }
            },
        )
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
        ) {
            EpisodeDetailsBody(
                episode,
                onPlay,
                channelId,
                onChannelClick,
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp).navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun EpisodeAction(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick, enabled = enabled)
}
