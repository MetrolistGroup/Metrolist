package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.metrolist.music.R
import com.metrolist.music.db.entities.SongWithStats
import com.metrolist.music.viewmodels.StatsViewModel
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun TimeTransfer(
    onDismiss: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val sourceSong = remember { mutableStateOf<SongWithStats?>(null) }
    val targetSong = remember { mutableStateOf<SongWithStats?>(null) }

    val mostPlayedSongsStats by viewModel.mostPlayedSongsStats.collectAsStateWithLifecycle()

    DefaultDialog(
        onDismiss = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.time_transfer_title),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        content = {
            Text(
                text = stringResource(R.string.time_transfer_warning),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = androidx.compose.ui.graphics.Color.Red,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Column {
                SongSelectDropdown(
                    titleT = stringResource(R.string.time_transfer_source_song),
                    songs = mostPlayedSongsStats,
                    selectedSong = sourceSong
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row {
                    Text(stringResource(R.string.time_transfer_listen_time_label))
                    if (sourceSong.value != null) {
                        Text(
                            text = formatMillis(sourceSong.value!!.timeListened),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))


                SongSelectDropdown(
                    titleT = stringResource(R.string.time_transfer_target_song),
                    songs = mostPlayedSongsStats,
                    selectedSong = targetSong,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row {
                    Text(stringResource(R.string.time_transfer_listen_time_label))
                    if (targetSong.value != null) {
                        Text(
                            text = formatMillis(targetSong.value!!.timeListened),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        val from = sourceSong.value?.id
                        val to = targetSong.value?.id
                        if (from != null && to != null && from != to) {
                            viewModel.transferSongStats(from, to) {
                                sourceSong.value = null
                                targetSong.value = null
                                onDismiss()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = sourceSong.value != null &&
                            targetSong.value != null &&
                            sourceSong.value!!.id != targetSong.value!!.id,
                ) {
                    Text(
                        text = stringResource(R.string.time_transfer_convert),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

    )
}

fun formatMillis(ms: Long?): String {
    if (ms == null) {
        return "00:00:00"
    }
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60

    return String.format(Locale.US,"%02d:%02d:%02d", hours, minutes, seconds)
}