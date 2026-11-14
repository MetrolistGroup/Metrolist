package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.episode_played
import com.metrolist.shared.state.EpisodeListeningState
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource

@Composable
fun episodeProgressFraction(
    episodeId: String,
    durationSeconds: Long? = null,
    serverProgressPercent: Int? = null,
): Float? {
    val progress by EpisodeListeningState.progress.collectAsState()
    val status = progress[episodeId]
    if (status?.isComplete(durationSeconds) == true) return 1f
    val serverFraction = serverProgressPercent?.takeIf { it > 0 }?.coerceAtMost(100)?.div(100f)
    return listOfNotNull(status?.fraction(durationSeconds), serverFraction).maxOrNull()
}

@Composable
fun EpisodePlayedIndicator(
    episodeId: String,
    durationSeconds: Long? = null,
    modifier: Modifier = Modifier,
    serverProgressPercent: Int? = null,
) {
    val fraction = episodeProgressFraction(episodeId, durationSeconds, serverProgressPercent) ?: return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (fraction >= 1f) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(MetrolistIcons.Filled.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(Res.string.episode_played), style = MaterialTheme.typography.labelSmall)
            }
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}
