package com.metrolist.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.metrolist.innertubex.extraction.strategy.AuthenticationPolicy
import com.metrolist.innertubex.extraction.strategy.CapabilitySupport
import com.metrolist.innertubex.extraction.strategy.PlaybackClientManifest
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.devtools_access_anonymous
import com.metrolist.shared.generated.resources.devtools_access_any
import com.metrolist.shared.generated.resources.devtools_access_logged_in
import com.metrolist.shared.generated.resources.devtools_capability_explicit
import com.metrolist.shared.generated.resources.devtools_capability_kids
import com.metrolist.shared.generated.resources.devtools_capability_limited
import com.metrolist.shared.generated.resources.devtools_capability_normal
import com.metrolist.shared.generated.resources.devtools_capability_passed
import com.metrolist.shared.generated.resources.devtools_capability_unavailable
import com.metrolist.shared.generated.resources.devtools_capability_untested
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClientCapabilityChips(
    manifest: PlaybackClientManifest,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapabilityChip(stringResource(Res.string.devtools_capability_normal), manifest.benchmarkContent.normal)
        CapabilityChip(stringResource(Res.string.devtools_capability_explicit), manifest.benchmarkContent.explicit)
        CapabilityChip(stringResource(Res.string.devtools_capability_kids), manifest.benchmarkContent.kids)
    }
}

@Composable
fun CapabilityChip(
    label: String,
    support: CapabilitySupport,
    modifier: Modifier = Modifier,
) {
    val (container, content) = support.chipColors()
    Surface(modifier = modifier, shape = CircleShape, color = container, contentColor = content) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(support.icon(), contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
fun ClientAccessLabel(
    policy: AuthenticationPolicy,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val (icon, label) =
        when (policy) {
            AuthenticationPolicy.OPTIONAL -> MetrolistIcons.Filled.Person to stringResource(Res.string.devtools_access_any)
            AuthenticationPolicy.UNSUPPORTED -> MetrolistIcons.Filled.VisibilityOff to stringResource(Res.string.devtools_access_anonymous)
            AuthenticationPolicy.REQUIRED -> MetrolistIcons.Filled.AccountCircle to stringResource(Res.string.devtools_access_logged_in)
        }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CapabilityStatusKey(modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapabilityChip(stringResource(Res.string.devtools_capability_passed), CapabilitySupport.SUPPORTED)
        CapabilityChip(stringResource(Res.string.devtools_capability_limited), CapabilitySupport.LIMITED)
        CapabilityChip(stringResource(Res.string.devtools_capability_untested), CapabilitySupport.UNKNOWN)
        CapabilityChip(stringResource(Res.string.devtools_capability_unavailable), CapabilitySupport.UNSUPPORTED)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClientAccessKey(modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ClientAccessLabel(AuthenticationPolicy.OPTIONAL)
        ClientAccessLabel(AuthenticationPolicy.UNSUPPORTED)
        ClientAccessLabel(AuthenticationPolicy.REQUIRED)
    }
}

@Composable
private fun CapabilitySupport.chipColors(): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        CapabilitySupport.SUPPORTED -> scheme.primaryContainer to scheme.onPrimaryContainer
        CapabilitySupport.LIMITED -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        CapabilitySupport.UNKNOWN -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        CapabilitySupport.UNSUPPORTED -> scheme.errorContainer to scheme.onErrorContainer
    }
}

private fun CapabilitySupport.icon(): ImageVector =
    when (this) {
        CapabilitySupport.SUPPORTED -> MetrolistIcons.Filled.Check
        CapabilitySupport.LIMITED -> MetrolistIcons.Filled.Warning
        CapabilitySupport.UNKNOWN -> MetrolistIcons.Filled.QuestionMark
        CapabilitySupport.UNSUPPORTED -> MetrolistIcons.Filled.Close
    }
