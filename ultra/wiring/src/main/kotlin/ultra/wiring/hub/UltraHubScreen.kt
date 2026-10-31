package ultra.wiring.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ultra.hooks.UltraHooks
import ultra.wiring.R

/**
 * Body of the Ultra hub. The :app glue supplies the top bar, back button and insets, so this only
 * draws content. Stats are read once per composition: contributors are fixed after startup.
 */
@Composable
internal fun UltraHubScreen() {
    val stats = UltraHooks.stats()

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.ultra_hub_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        HubRow(
            label = stringResource(if (stats.installed) R.string.ultra_hub_status_active else R.string.ultra_hub_status_inactive),
            value = null,
        )
        HubRow(stringResource(R.string.ultra_hook_api_client), stats.apiClient)
        HubRow(stringResource(R.string.ultra_hook_stream_client), stats.streamClient)
        HubRow(stringResource(R.string.ultra_hook_audio_processors), stats.audioProcessors)
        HubRow(stringResource(R.string.ultra_hook_stream_resolvers), stats.streamResolvers)
        HubRow(stringResource(R.string.ultra_hook_destinations), stats.destinations)
        HubRow(stringResource(R.string.ultra_hook_settings_entries), stats.settingsEntries)
    }
}

@Composable
private fun HubRow(
    label: String,
    value: Int?,
) {
    // Plain Row (no container color) so it blends with the app's surface, including pure-black mode.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        if (value != null) {
            Text(text = value.toString(), style = MaterialTheme.typography.titleMedium)
        }
    }
}
