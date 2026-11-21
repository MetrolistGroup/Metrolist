@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.apply
import com.metrolist.shared.generated.resources.automatic
import com.metrolist.shared.generated.resources.brand_better_lyrics
import com.metrolist.shared.generated.resources.brand_kugou
import com.metrolist.shared.generated.resources.brand_lrclib
import com.metrolist.shared.generated.resources.brand_lrcred
import com.metrolist.shared.generated.resources.brand_lyprio
import com.metrolist.shared.generated.resources.brand_paxsenix
import com.metrolist.shared.generated.resources.brand_unison
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.choose_lyrics
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.collapse
import com.metrolist.shared.generated.resources.collapsed
import com.metrolist.shared.generated.resources.expand
import com.metrolist.shared.generated.resources.expanded
import com.metrolist.shared.generated.resources.lyrics_capability_line
import com.metrolist.shared.generated.resources.lyrics_capability_line_syllable
import com.metrolist.shared.generated.resources.lyrics_capability_line_syllable_unsynced
import com.metrolist.shared.generated.resources.lyrics_capability_line_unsynced
import com.metrolist.shared.generated.resources.lyrics_capability_unsynced
import com.metrolist.shared.generated.resources.lyrics_provider_user
import com.metrolist.shared.generated.resources.lyrics_providers_loading
import com.metrolist.shared.generated.resources.lyrics_timing_line_synced
import com.metrolist.shared.generated.resources.lyrics_timing_unsynced
import com.metrolist.shared.generated.resources.lyrics_timing_word_synced
import com.metrolist.shared.generated.resources.lyrics_youtube_captions
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.refetch
import com.metrolist.shared.generated.resources.youtube_music
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsProviderResult
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.supportedTimingKinds
import com.metrolist.shared.repository.LyricsProviderSearch
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.GroupedListItem
import com.metrolist.shared.ui.components.loadingShimmer
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.utils.formatDuration
import org.jetbrains.compose.resources.stringResource

@Composable
fun LyricsProviderDialog(
    search: LyricsProviderSearch?,
    noResultsText: String,
    onDismiss: () -> Unit,
    onSelect: (LyricsProviderResult) -> Unit,
) {
    DisposableEffect(search) { onDispose { search?.cancel() } }
    var selectedResult by remember(search) { mutableStateOf<LyricsProviderResult?>(null) }
    val dismiss = {
        search?.cancel()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 720.dp).fillMaxWidth(),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.choose_lyrics),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = dismiss, shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Default.Close, stringResource(Res.string.close))
                }
            }
        },
        text = {
            if (search != null) {
                val groups by search.groups.collectAsState()
                val visibleGroups = groups.filter { it.hasResults }
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScrollWithDrag(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (visibleGroups.isEmpty()) {
                        if (groups.any { it.loading }) {
                            ProviderLoadingRows()
                        } else {
                            Text(noResultsText, Modifier.padding(16.dp))
                        }
                    }
                    visibleGroups.forEachIndexed { index, group ->
                        key(group.provider) {
                            GroupedListItem(index, visibleGroups.size) {
                                Column {
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .tvFocusHighlight()
                                                .clickable { search.toggle(group.provider) }
                                                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(providerName(group.provider), style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                providerCapabilities(group.provider, group.results.orEmpty()),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        ExpandButton(group.expanded) { search.toggle(group.provider) }
                                    }
                                    AnimatedVisibility(group.expanded) {
                                        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                                            val results = group.results.orEmpty()
                                            results.forEachIndexed { resultIndex, result ->
                                                LyricsResultCard(
                                                    result = result,
                                                    index = resultIndex,
                                                    count = results.size,
                                                    selected = result == selectedResult,
                                                ) {
                                                    selectedResult = result
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val searched = groups.count { !it.loading }
                    if (visibleGroups.isNotEmpty() && searched < groups.size) {
                        val loadingLabel = stringResource(Res.string.lyrics_providers_loading)
                        LinearWavyProgressIndicator(
                            progress = { searched.toFloat() / groups.size },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .semantics { contentDescription = loadingLabel },
                        )
                    }
                }
            } else {
                ProviderLoadingRows()
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedResult?.let { search?.select(it, onSelect) } },
                enabled = selectedResult != null && search != null,
            ) {
                Text(stringResource(Res.string.apply))
            }
        },
    )
}

@Composable
private fun LyricsResultCard(
    result: LyricsProviderResult,
    index: Int,
    count: Int,
    selected: Boolean = false,
    onSelect: () -> Unit,
) {
    var expanded by remember(result) { mutableStateOf(false) }
    GroupedListItem(index, count) {
        Surface(
            color =
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .tvFocusHighlight()
                    .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            listOfNotNull(result.song.title, result.song.album, result.song.artist)
                                .filter(String::isNotBlank)
                                .joinToString(" - "),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val timing = timingLabel(result.timingKind)
                        Text(
                            listOfNotNull(
                                result.song.durationMs
                                    ?.takeIf { it > 0 }
                                    ?.let(::formatDuration),
                                timing,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (result.providerName.contains('/')) {
                            Text(result.providerName, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    ExpandButton(expanded) { expanded = !expanded }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(
                        result.preview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (expanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandButton(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val state = stringResource(if (expanded) Res.string.expanded else Res.string.collapsed)
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.tvFocusHighlight().semantics { stateDescription = state },
    ) {
        Icon(
            if (expanded) MetrolistIcons.Outlined.KeyboardArrowUp else MetrolistIcons.Outlined.KeyboardArrowDown,
            stringResource(if (expanded) Res.string.collapse else Res.string.expand),
        )
    }
}

@Composable
private fun ProviderLoadingRows() {
    val label = stringResource(Res.string.lyrics_providers_loading)
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .loadingShimmer()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(3) { index ->
            Box(
                Modifier
                    .fillMaxWidth(if (index == 2) 0.65f else 1f)
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small),
            )
        }
    }
}

@Composable
private fun providerName(provider: LyricsProvider): String =
    stringResource(
        when (provider) {
            LyricsProvider.USER -> Res.string.lyrics_provider_user
            LyricsProvider.LYPRIO -> Res.string.brand_lyprio
            LyricsProvider.BETTERLYRICS -> Res.string.brand_better_lyrics
            LyricsProvider.LRCRED -> Res.string.brand_lrcred
            LyricsProvider.UNISON -> Res.string.brand_unison
            LyricsProvider.LRCLIB -> Res.string.brand_lrclib
            LyricsProvider.KUGOU -> Res.string.brand_kugou
            LyricsProvider.PAXSENIX -> Res.string.brand_paxsenix
            LyricsProvider.YOUTUBESUBTITLE -> Res.string.lyrics_youtube_captions
            LyricsProvider.YOUTUBE -> Res.string.youtube_music
            LyricsProvider.AUTO, LyricsProvider.SIMPMUSIC -> error("Provider is not in the manual source list: $provider")
        },
    )

@Composable
private fun timingLabel(kind: LyricsTimingKind): String =
    stringResource(
        when (kind) {
            LyricsTimingKind.UNSYNCED -> Res.string.lyrics_timing_unsynced
            LyricsTimingKind.LINE_SYNCED -> Res.string.lyrics_timing_line_synced
            LyricsTimingKind.WORD_SYNCED -> Res.string.lyrics_timing_word_synced
        },
    )

@Composable
private fun providerCapabilities(
    provider: LyricsProvider,
    results: List<LyricsProviderResult>,
): String {
    // Once results arrive, describe what was returned instead of what the provider can return.
    if (results.isNotEmpty()) {
        val returned = results.map { it.timingKind }.distinct().sortedDescending()
        return returned.map { timingLabel(it) }.joinToString(" · ")
    }
    val kinds = provider.supportedTimingKinds
    return stringResource(
        when {
            LyricsTimingKind.WORD_SYNCED in kinds && LyricsTimingKind.UNSYNCED in kinds -> {
                Res.string.lyrics_capability_line_syllable_unsynced
            }

            LyricsTimingKind.WORD_SYNCED in kinds -> {
                Res.string.lyrics_capability_line_syllable
            }

            LyricsTimingKind.LINE_SYNCED in kinds && LyricsTimingKind.UNSYNCED in kinds -> {
                Res.string.lyrics_capability_line_unsynced
            }

            LyricsTimingKind.LINE_SYNCED in kinds -> {
                Res.string.lyrics_capability_line
            }

            else -> {
                Res.string.lyrics_capability_unsynced
            }
        },
    )
}

@Composable
fun LyricsRefreshDialog(
    onDismiss: () -> Unit,
    onAutomatic: () -> Unit,
    onProvider: (LyricsProvider) -> Unit,
) {
    val providers =
        remember {
            LyricsProvider.parseUserOrderFromStoredCsv(Settings.shared.lyricsProviderOrder) + LyricsProvider.afterUserLyricsFallbacks
        }
    var selectedProvider by remember { mutableStateOf<LyricsProvider?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.refetch),
                    style = MaterialTheme.typography.headlineSmall,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.Close,
                        contentDescription = stringResource(Res.string.close),
                    )
                }
            }
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val isAutoSelected = selectedProvider == null
                GroupedListItem(0, 1) {
                    Surface(
                        selected = isAutoSelected,
                        modifier = Modifier.semantics { role = Role.RadioButton },
                        color =
                            if (isAutoSelected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        onClick = { selectedProvider = null },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(Res.string.automatic),
                                style = MaterialTheme.typography.titleSmall,
                                color =
                                    if (isAutoSelected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                providers.forEachIndexed { index, provider ->
                    val isSelected = selectedProvider == provider
                    GroupedListItem(index, providers.size) {
                        Surface(
                            selected = isSelected,
                            modifier = Modifier.semantics { role = Role.RadioButton },
                            color =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            onClick = { selectedProvider = provider },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = providerName(provider),
                                    style = MaterialTheme.typography.titleSmall,
                                    color =
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    val provider = selectedProvider
                    if (provider == null) {
                        onAutomatic()
                    } else {
                        onProvider(provider)
                    }
                },
            ) {
                Text(stringResource(Res.string.next))
            }
        },
    )
}
