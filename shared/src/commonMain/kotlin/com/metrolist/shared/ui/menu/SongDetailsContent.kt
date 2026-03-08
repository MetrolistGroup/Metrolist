/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.api.SongPlayerInformation
import com.metrolist.shared.api.SongWatchInformation
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.about
import com.metrolist.shared.generated.resources.album
import com.metrolist.shared.generated.resources.artist
import com.metrolist.shared.generated.resources.bitrate
import com.metrolist.shared.generated.resources.bitrate_value
import com.metrolist.shared.generated.resources.cached_audio
import com.metrolist.shared.generated.resources.client
import com.metrolist.shared.generated.resources.codecs
import com.metrolist.shared.generated.resources.description
import com.metrolist.shared.generated.resources.detail_add_token
import com.metrolist.shared.generated.resources.detail_available
import com.metrolist.shared.generated.resources.detail_in_library
import com.metrolist.shared.generated.resources.detail_remove_token
import com.metrolist.shared.generated.resources.details
import com.metrolist.shared.generated.resources.duration
import com.metrolist.shared.generated.resources.explicit
import com.metrolist.shared.generated.resources.file_size
import com.metrolist.shared.generated.resources.itag
import com.metrolist.shared.generated.resources.likes
import com.metrolist.shared.generated.resources.listening_times
import com.metrolist.shared.generated.resources.local_file
import com.metrolist.shared.generated.resources.loudness
import com.metrolist.shared.generated.resources.loudness_value
import com.metrolist.shared.generated.resources.media_id
import com.metrolist.shared.generated.resources.mime_type
import com.metrolist.shared.generated.resources.no
import com.metrolist.shared.generated.resources.no_song_selected
import com.metrolist.shared.generated.resources.percent_symbol
import com.metrolist.shared.generated.resources.profile
import com.metrolist.shared.generated.resources.sample_rate
import com.metrolist.shared.generated.resources.sample_rate_value
import com.metrolist.shared.generated.resources.source
import com.metrolist.shared.generated.resources.statistics
import com.metrolist.shared.generated.resources.subscribers
import com.metrolist.shared.generated.resources.title
import com.metrolist.shared.generated.resources.total_listening
import com.metrolist.shared.generated.resources.total_time_listening
import com.metrolist.shared.generated.resources.video_stats_resolution
import com.metrolist.shared.generated.resources.video_stats_video_codec
import com.metrolist.shared.generated.resources.views
import com.metrolist.shared.generated.resources.volume
import com.metrolist.shared.generated.resources.yes
import com.metrolist.shared.generated.resources.youtube_music
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.loadingShimmer
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.screens.statisticsDuration
import com.metrolist.shared.ui.statistics.StatisticsFormatter
import com.metrolist.shared.ui.theme.currentAppLocaleList
import com.metrolist.shared.ui.utils.rememberFileSizeFormatter
import com.metrolist.shared.ui.utils.segmentedGroupShape
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.utils.formatDuration
import com.metrolist.shared.utils.formatLoudness
import com.metrolist.shared.youtube.playback.PlaybackResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
fun SongDetailsContent(
    song: SongItem?,
    database: MusicDatabase,
    playbackResource: PlaybackResource? = null,
    volume: Float? = null,
    durationMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    if (song == null) {
        Text(stringResource(Res.string.no_song_selected), modifier = modifier.padding(24.dp))
        return
    }
    key(song.id) {
        val formatFileSize = rememberFileSizeFormatter()
        val languageTag = currentAppLocaleList()[0].toLanguageTag()
        val numbers = remember(languageTag) { StatisticsFormatter(languageTag) }
        val runtimeResource = playbackResource?.takeIf { it.videoId == song.id }
        val localSong by remember(database, song.id) { database.dao().song(song.id) }.collectAsState(initial = null)
        val listeningStats by remember(database, song.id) { database.dao().songListeningTotals(song.id) }.collectAsState(initial = null)
        var playerInformation by remember { mutableStateOf<SongPlayerInformation?>(null) }
        var watchInformation by remember { mutableStateOf<SongWatchInformation?>(null) }
        var playerInformationLoading by remember { mutableStateOf(!song.isLocal) }
        var watchInformationLoading by remember { mutableStateOf(!song.isLocal) }
        LaunchedEffect(song.id) {
            if (!song.isLocal) {
                try {
                    playerInformation = GlobalYouTubeRepository.instance.getSongPlayerInformation(song.id)
                } finally {
                    playerInformationLoading = false
                }
            }
        }
        LaunchedEffect(song.id, languageTag) {
            if (!song.isLocal) {
                watchInformationLoading = true
                try {
                    watchInformation = GlobalYouTubeRepository.instance.getSongWatchInformation(song.id, languageTag)
                } finally {
                    watchInformationLoading = false
                }
            }
        }
        val storedSong = localSong?.song
        val storedFormat = localSong?.format
        val title = storedSong?.title ?: song.title
        val artistText =
            localSong
                ?.orderedArtists
                ?.joinToString(", ") { it.name }
                ?.takeIf { it.isNotBlank() }
                ?: song.artists.joinToString(", ") { it.displayName }
        val albumText = storedSong?.albumName ?: localSong?.album?.title ?: song.album?.name
        val duration = storedSong?.duration?.takeIf { it >= 0 }?.toLong() ?: song.duration
        val durationText = (durationMs ?: duration?.times(1000))?.takeIf { it > 0 }?.let(::formatDuration)
        val thumbnail = storedSong?.thumbnailUrl ?: song.thumbnail
        val isExplicit = storedSong?.explicit ?: song.isExplicit
        val fileSize = runtimeResource?.contentLengthBytes ?: storedFormat?.contentLength?.takeIf { it > 0L }
        val loudnessDb = runtimeResource?.loudnessDb ?: storedFormat?.loudnessDb
        val details =
            buildList {
                title.takeIf { it.isNotBlank() }?.let { add(stringResource(Res.string.title) to it) }
                artistText.takeIf { it.isNotBlank() }?.let { add(stringResource(Res.string.artist) to it) }
                albumText?.takeIf { it.isNotBlank() }?.let { add(stringResource(Res.string.album) to it) }
                durationText?.let { add(stringResource(Res.string.duration) to it) }
                add(stringResource(Res.string.media_id) to song.id)
                add(stringResource(Res.string.explicit) to stringResource(if (isExplicit) Res.string.yes else Res.string.no))
                add(
                    stringResource(Res.string.source) to
                        if (song.isLocal) {
                            stringResource(Res.string.local_file)
                        } else {
                            runtimeResource?.let { streamSource(it) }
                                ?: stringResource(Res.string.youtube_music)
                        },
                )
                runtimeResource?.clientName?.takeIf { it.isNotBlank() && it != "unknown" }?.let {
                    add(
                        stringResource(Res.string.client) to it,
                    )
                }
                runtimeResource?.profileId?.takeIf { it.isNotBlank() && it != "unknown" }?.let {
                    add(
                        stringResource(Res.string.profile) to it,
                    )
                }
                (runtimeResource?.itag ?: storedFormat?.itag)?.let { add(stringResource(Res.string.itag) to it.toString()) }
                (runtimeResource?.mimeType ?: storedFormat?.mimeType)?.takeIf { it.isNotBlank() }?.let {
                    add(stringResource(Res.string.mime_type) to it)
                }
                (runtimeResource?.codecs ?: storedFormat?.codecs)?.takeIf { it.isNotBlank() }?.let {
                    add(stringResource(Res.string.codecs) to it)
                }
                (runtimeResource?.bitrate ?: storedFormat?.bitrate)?.takeIf { it > 0 }?.let {
                    add(stringResource(Res.string.bitrate) to stringResource(Res.string.bitrate_value, it / 1000))
                }
                (runtimeResource?.sampleRate ?: storedFormat?.sampleRate)?.takeIf { it > 0 }?.let {
                    add(stringResource(Res.string.sample_rate) to stringResource(Res.string.sample_rate_value, it))
                }
                loudnessDb?.let {
                    add(
                        stringResource(Res.string.loudness) to stringResource(Res.string.loudness_value, formatLoudness(it)),
                    )
                }
                if (runtimeResource != null && volume != null) {
                    add(
                        stringResource(Res.string.volume) to
                            "${numbers.number(
                                (volume * 100).roundToInt().coerceIn(0, 100).toLong(),
                            )}${stringResource(Res.string.percent_symbol)}",
                    )
                }
                fileSize?.takeIf { it > 0 }?.let { add(stringResource(Res.string.file_size) to formatFileSize(it)) }
                add(
                    stringResource(Res.string.detail_in_library) to
                        stringResource(
                            if (storedSong?.inLibrary != null || song.libraryAddToken != null ||
                                song.libraryRemoveToken != null
                            ) {
                                Res.string.yes
                            } else {
                                Res.string.no
                            },
                        ),
                )
                song.libraryAddToken?.let {
                    add(stringResource(Res.string.detail_add_token) to stringResource(Res.string.detail_available))
                }
                song.libraryRemoveToken?.let {
                    add(stringResource(Res.string.detail_remove_token) to stringResource(Res.string.detail_available))
                }
                runtimeResource?.let { resource ->
                    if (resource.videoWidth != null && resource.videoHeight != null) {
                        add(stringResource(Res.string.video_stats_resolution) to "${resource.videoWidth} × ${resource.videoHeight}")
                    }
                    resource.videoCodecs?.takeIf { it.isNotBlank() }?.let { add(stringResource(Res.string.video_stats_video_codec) to it) }
                }
            }

        val statistics =
            if (listeningStats?.playCount == 0) {
                emptyList()
            } else {
                listOf(
                    stringResource(Res.string.total_listening) to
                        listeningStats?.let {
                            pluralStringResource(Res.plurals.listening_times, it.playCount, numbers.number(it.playCount.toLong()))
                        },
                    stringResource(Res.string.total_time_listening) to listeningStats?.let { statisticsDuration(it.playTimeMs) },
                )
            }
        val information =
            buildList {
                fun addValue(
                    label: String,
                    value: String?,
                    loading: Boolean,
                ) {
                    if (!value.isNullOrBlank() || loading) add(label to value?.takeIf { it.isNotBlank() })
                }
                addValue(stringResource(Res.string.subscribers), watchInformation?.subscriberCount, watchInformationLoading)
                addValue(
                    stringResource(Res.string.views),
                    (watchInformation?.viewCount ?: playerInformation?.viewCount)?.let(numbers::number),
                    playerInformationLoading || watchInformationLoading,
                )
                addValue(stringResource(Res.string.likes), watchInformation?.likeCount?.let(numbers::number), watchInformationLoading)
                addValue(
                    stringResource(Res.string.artist),
                    watchInformation?.artist ?: playerInformation?.artist ?: artistText.takeIf { it.isNotBlank() },
                    playerInformationLoading || watchInformationLoading,
                )
                addValue(stringResource(Res.string.description), watchInformation?.description, watchInformationLoading)
            }
        val sections =
            listOf(
                Triple(stringResource(Res.string.statistics), MetrolistIcons.Outlined.BarChart, statistics),
                Triple(stringResource(Res.string.details), MetrolistIcons.Outlined.Info, details),
                Triple(stringResource(Res.string.about), MetrolistIcons.Outlined.Description, information),
            ).filter { it.third.isNotEmpty() }
        SelectionContainer {
            Column(modifier = modifier.fillMaxWidth().verticalScrollWithDrag(rememberScrollState()).padding(16.dp)) {
                SongDetailsHeader(title, artistText, thumbnail)
                Spacer(Modifier.height(16.dp))
                sections.forEachIndexed { index, (sectionTitle, icon, rows) ->
                    if (index > 0) Spacer(Modifier.height(4.dp))
                    SongDetailsSection(sectionTitle, icon, rows, index == 0, index == sections.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun SongDetailsHeader(
    title: String,
    subtitle: String,
    thumbnail: String?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    url = thumbnail,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
        }
    }
}

@Composable
private fun SongDetailsSection(
    title: String,
    icon: ImageVector,
    rows: List<Pair<String, String?>>,
    first: Boolean,
    last: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = segmentedGroupShape(first, last, pressed = false),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(4.dp))
            rows.forEach { (label, value) ->
                Column(Modifier.fillMaxWidth()) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    if (value == null) {
                        Box(
                            Modifier
                                .padding(top = 4.dp)
                                .width(120.dp)
                                .height(16.dp)
                                .loadingShimmer()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp)),
                        )
                    } else {
                        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun streamSource(resource: PlaybackResource): String =
    when {
        resource.profileId == "cached-audio" -> stringResource(Res.string.cached_audio)
        resource.url.startsWith("file:") -> stringResource(Res.string.local_file)
        else -> stringResource(Res.string.youtube_music)
    }
