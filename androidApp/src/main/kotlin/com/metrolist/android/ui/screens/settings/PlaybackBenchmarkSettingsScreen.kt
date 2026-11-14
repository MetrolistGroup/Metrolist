@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.viewmodels.PlaybackBenchmarkViewModel
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PlaybackClientOption
import com.metrolist.innertubex.extraction.strategy.PlaybackTransport
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_active
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_anonymous_session
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_anonymous_session_description
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_assert_advanced
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_assert_duration
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_assert_final
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_assert_media
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_assert_nonce
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_assert_one_player
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_assert_profile
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_assert_seek
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_assert_usable
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_assert_view_start
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_assert_watchtime
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_cancel
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_case_explicit
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_case_kids
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_case_normal
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_clear_selection
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_direct_profiles
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_failed_assertions
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_metrics
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_passed
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_profile_count
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_profile_group_description
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_profiles
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_profiles_description
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_results
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_results_summary
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_results_summary_anonymous
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_run
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_run_description
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_select_all
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_select_automatic
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_select_direct
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_select_sabr
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_start
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_title
import com.metrolist.shared.generated.resources.devtools_playback_sabr_clients
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.settings.ClientAccessLabel
import com.metrolist.shared.ui.settings.ClientCapabilityChips
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkAssertions
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkCase
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkConfig
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkRunner
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkSample
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkStatus
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaybackBenchmarkSettingsScreen(onNavigateBack: () -> Unit) {
    val runner: PlaybackBenchmarkRunner = koinInject()
    val context = LocalContext.current.applicationContext
    val playerConnection = LocalPlayerConnection.current
    val options = remember { PlaybackClientCatalog.benchmarkOptions }
    val directOptions = remember { options.filter { PlaybackTransport.SABR !in it.manifest.transports } }
    val sabrOptions = remember { options.filter { PlaybackTransport.SABR in it.manifest.transports } }
    val automaticOptions = remember { options.filter { it.manifest.isAutomatic } }
    val storedSelection =
        remember {
            Settings.shared.devtoolsBenchmarkClients
                .split(',')
                .filter(String::isNotBlank)
                .toSet()
        }
    val validStoredSelection =
        remember {
            storedSelection.filterTo(linkedSetOf()) { stored -> options.any { it.id == stored } }
        }
    var selectedIds by remember {
        mutableStateOf<Set<String>>(
            validStoredSelection.takeIf(Set<String>::isNotEmpty)
                ?: automaticOptions.mapTo(linkedSetOf()) { it.id },
        )
    }
    var anonymousSession by remember { mutableStateOf(false) }
    val viewModel =
        viewModel<PlaybackBenchmarkViewModel>(
            factory =
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = PlaybackBenchmarkViewModel(runner, context) as T
                },
        )
    LifecycleStartEffect(viewModel) {
        onStopOrDispose { viewModel.cancel() }
    }
    val progress by viewModel.progress.collectAsState()
    val report by viewModel.report.collectAsState()
    val benchmarkCaseCount = (selectedIds.size + 1) * BENCHMARK_SAMPLES.size
    val updateSelection: (Set<String>) -> Unit = { selection ->
        val orderedSelection = options.filter { it.id in selection }.mapTo(linkedSetOf()) { it.id }
        selectedIds = orderedSelection
        Settings.shared.devtoolsBenchmarkClients = orderedSelection.joinToString(",")
    }

    SettingsSubScreen(
        title = stringResource(Res.string.devtools_playback_benchmark_title),
        onNavigateBack = onNavigateBack,
    ) {
        ClientCapabilityLegend()
        BenchmarkPresetCard(
            title = stringResource(Res.string.devtools_playback_benchmark_profiles),
            description =
                stringResource(
                    Res.string.devtools_playback_benchmark_profiles_description,
                    selectedIds.size,
                    options.size,
                    benchmarkCaseCount,
                ),
            presets =
                listOf(
                    BenchmarkPreset(
                        title = stringResource(Res.string.devtools_playback_benchmark_select_automatic),
                        subtitle = stringResource(Res.string.devtools_playback_benchmark_profile_count, automaticOptions.size),
                        ids = automaticOptions.mapTo(linkedSetOf()) { it.id },
                    ),
                    BenchmarkPreset(
                        title = stringResource(Res.string.devtools_playback_benchmark_select_direct),
                        subtitle = stringResource(Res.string.devtools_playback_benchmark_profile_count, directOptions.size),
                        ids = directOptions.mapTo(linkedSetOf()) { it.id },
                    ),
                    BenchmarkPreset(
                        title = stringResource(Res.string.devtools_playback_benchmark_select_sabr),
                        subtitle = stringResource(Res.string.devtools_playback_benchmark_profile_count, sabrOptions.size),
                        ids = sabrOptions.mapTo(linkedSetOf()) { it.id },
                    ),
                    BenchmarkPreset(
                        title = stringResource(Res.string.devtools_playback_benchmark_select_all),
                        subtitle = stringResource(Res.string.devtools_playback_benchmark_profile_count, options.size),
                        ids = options.mapTo(linkedSetOf()) { it.id },
                    ),
                ),
            selectedIds = selectedIds,
            enabled = !progress.running,
            clearLabel = stringResource(Res.string.devtools_playback_benchmark_clear_selection),
            onSelect = updateSelection,
        )

        BenchmarkClientGroup(
            title = stringResource(Res.string.devtools_playback_benchmark_direct_profiles),
            options = directOptions,
            selectedIds = selectedIds,
            enabled = !progress.running,
            onSelectionChange = updateSelection,
        )

        BenchmarkClientGroup(
            title = stringResource(Res.string.devtools_playback_sabr_clients),
            options = sabrOptions,
            selectedIds = selectedIds,
            enabled = !progress.running,
            onSelectionChange = updateSelection,
        )

        BenchmarkRunCard(
            title = stringResource(Res.string.devtools_playback_benchmark_run),
            description =
                stringResource(
                    Res.string.devtools_playback_benchmark_run_description,
                    benchmarkCaseCount,
                    (benchmarkCaseCount * BENCHMARK_CASE_MINIMUM_SECONDS + 59) / 60,
                ),
            anonymousTitle = stringResource(Res.string.devtools_playback_benchmark_anonymous_session),
            anonymousDescription = stringResource(Res.string.devtools_playback_benchmark_anonymous_session_description),
            anonymousSession = anonymousSession,
            running = progress.running,
            progressFraction =
                if (progress.totalCases == 0) 0f else progress.completedCases.toFloat() / progress.totalCases,
            activeText =
                progress.activeProfileId?.let { profileId ->
                    val case = progress.activeCase?.displayName().orEmpty()
                    stringResource(
                        Res.string.devtools_playback_benchmark_active,
                        profileId,
                        case,
                        (progress.completedCases + 1).coerceAtMost(progress.totalCases),
                        progress.totalCases,
                    )
                },
            actionLabel =
                if (progress.running) {
                    stringResource(Res.string.devtools_playback_benchmark_cancel)
                } else {
                    stringResource(Res.string.devtools_playback_benchmark_start)
                },
            onToggleAnonymous = { anonymousSession = !anonymousSession },
            onAction = {
                if (progress.running) {
                    viewModel.cancel()
                } else {
                    playerConnection?.takeIf { it.isPlaying.value }?.togglePlayPause()
                    val selectedProfiles = options.filter { it.id in selectedIds }
                    viewModel.run(
                        PlaybackBenchmarkConfig(
                            samples = BENCHMARK_SAMPLES,
                            selectedProfiles = selectedProfiles,
                            anonymousSession = anonymousSession,
                        ),
                    )
                }
            },
        )

        report?.let { benchmarkReport ->
            SettingsSubScreenGroup(
                title = stringResource(Res.string.devtools_playback_benchmark_results),
                description =
                    stringResource(
                        if (benchmarkReport.anonymousSession) {
                            Res.string.devtools_playback_benchmark_results_summary_anonymous
                        } else {
                            Res.string.devtools_playback_benchmark_results_summary
                        },
                        benchmarkReport.results.count { it.status == PlaybackBenchmarkStatus.PASSED },
                        benchmarkReport.results.size,
                    ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    benchmarkReport.results.forEachIndexed { index, result ->
                        val failedAssertions = result.assertions.failedLabels()
                        val metrics =
                            stringResource(
                                Res.string.devtools_playback_benchmark_metrics,
                                result.resolutionMs.formatMilliseconds(),
                                result.firstMediaResponseMs.formatMilliseconds(),
                                result.preparationMs.formatMilliseconds(),
                                result.timeToPlaybackStartMs.formatMilliseconds(),
                                result.selectedClientName ?: "n/a",
                                result.selectedProfileId ?: "n/a",
                                result.transport ?: "n/a",
                                result.usedPoToken,
                                result.usedAuthenticatedWatchPage,
                                result.playerResponseAttempts,
                                result.fallbackHops,
                            )
                        val status =
                            if (failedAssertions.isEmpty()) {
                                stringResource(Res.string.devtools_playback_benchmark_passed)
                            } else {
                                buildString {
                                    append(
                                        stringResource(
                                            Res.string.devtools_playback_benchmark_failed_assertions,
                                            failedAssertions.joinToString(),
                                        ),
                                    )
                                    result.failureCategory?.let { append(" ·\u00a0").append(it) }
                                    result.failureType?.let { append(" ·\u00a0").append(it) }
                                }
                            }
                        BenchmarkResultRow(
                            title = "${result.profileId} ·\u00a0${result.case.displayName()} ·\u00a0${result.sampleId}",
                            metrics = metrics,
                            status = status,
                            passed = failedAssertions.isEmpty(),
                            isFirst = index == 0,
                            isLast = index == benchmarkReport.results.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BenchmarkClientGroup(
    title: String,
    options: List<PlaybackClientOption>,
    selectedIds: Set<String>,
    enabled: Boolean,
    onSelectionChange: (Set<String>) -> Unit,
) {
    val selectedCount = options.count { it.id in selectedIds }
    SettingsSubScreenGroup(
        title = title,
        description =
            stringResource(
                Res.string.devtools_playback_benchmark_profile_group_description,
                selectedCount,
                options.size,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEachIndexed { index, option ->
                val selected = option.id in selectedIds
                BenchmarkClientRow(
                    option = option,
                    selected = selected,
                    enabled = enabled,
                    isFirst = index == 0,
                    isLast = index == options.lastIndex,
                    onToggle = {
                        onSelectionChange(if (selected) selectedIds - option.id else selectedIds + option.id)
                    },
                )
            }
        }
    }
}

private val BenchmarkCardShape = RoundedCornerShape(28.dp)

private fun groupedRowShape(
    isFirst: Boolean,
    isLast: Boolean,
): RoundedCornerShape {
    val outer = 28.dp
    val inner = 8.dp
    return RoundedCornerShape(
        topStart = if (isFirst) outer else inner,
        topEnd = if (isFirst) outer else inner,
        bottomStart = if (isLast) outer else inner,
        bottomEnd = if (isLast) outer else inner,
    )
}

@Composable
private fun BenchmarkClientRow(
    option: PlaybackClientOption,
    selected: Boolean,
    enabled: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue =
            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "benchmarkRowContainer",
    )
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val muted =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.6f)
                .clip(groupedRowShape(isFirst, isLast))
                .background(container)
                .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
                .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(option.displayName, style = MaterialTheme.typography.titleMedium, color = content, modifier = Modifier.weight(1f))
                ClientAccessLabel(option.manifest.authentication, color = muted)
            }
            Text(
                "${option.client.clientName} ·\u00a0${option.client.clientVersion}",
                style = MaterialTheme.typography.labelMedium,
                color = muted,
            )
            ClientCapabilityChips(option.manifest)
        }
        Checkbox(checked = selected, enabled = enabled, onCheckedChange = null)
    }
}

private class BenchmarkPreset(
    val title: String,
    val subtitle: String,
    val ids: Set<String>,
)

@Composable
private fun BenchmarkPresetCard(
    title: String,
    description: String,
    presets: List<BenchmarkPreset>,
    selectedIds: Set<String>,
    enabled: Boolean,
    clearLabel: String,
    onSelect: (Set<String>) -> Unit,
) {
    SettingsSubScreenGroup(title = title, description = description) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = BenchmarkCardShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.alpha(if (enabled) 1f else 0.6f).padding(vertical = 8.dp)) {
                presets.forEach { preset ->
                    val active = selectedIds == preset.ids
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled, role = Role.Button) { onSelect(preset.ids) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(preset.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                preset.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (active) {
                            Icon(MetrolistIcons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled && selectedIds.isNotEmpty(), role = Role.Button) { onSelect(emptySet()) }
                            .alpha(if (selectedIds.isNotEmpty()) 1f else 0.5f)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(MetrolistIcons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(clearLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun BenchmarkRunCard(
    title: String,
    description: String,
    anonymousTitle: String,
    anonymousDescription: String,
    anonymousSession: Boolean,
    running: Boolean,
    progressFraction: Float,
    activeText: String?,
    actionLabel: String,
    onToggleAnonymous: () -> Unit,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 24.dp, end = 16.dp),
        shape = BenchmarkCardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        MetrolistIcons.Default.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = anonymousSession,
                                enabled = !running,
                                role = Role.Switch,
                                onValueChange = { onToggleAnonymous() },
                            ).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(anonymousTitle, style = MaterialTheme.typography.titleSmall)
                        Text(
                            anonymousDescription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ExpressiveSwitch(checked = anonymousSession, onCheckedChange = null, enabled = !running)
                }
            }
            if (running) {
                LinearWavyProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            activeText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running) {
                FilledTonalButton(
                    onClick = onAction,
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(MetrolistIcons.Default.Close, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(actionLabel)
                }
            } else {
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun BenchmarkResultRow(
    title: String,
    metrics: String,
    status: String,
    passed: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val badgeContainer = if (passed) scheme.primaryContainer else scheme.errorContainer
    val badgeContent = if (passed) scheme.onPrimaryContainer else scheme.onErrorContainer
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(groupedRowShape(isFirst, isLast))
                .background(scheme.surfaceContainer)
                .background(if (passed) Color.Transparent else scheme.errorContainer.copy(alpha = 0.28f))
                .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(badgeContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (passed) MetrolistIcons.Default.Check else MetrolistIcons.Default.Close,
                contentDescription = null,
                tint = badgeContent,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                metrics,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = scheme.onSurfaceVariant,
            )
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = if (passed) scheme.primary else scheme.error,
            )
        }
    }
}

@Composable
private fun PlaybackBenchmarkCase.displayName(): String =
    stringResource(
        when (this) {
            PlaybackBenchmarkCase.NORMAL -> Res.string.devtools_playback_benchmark_case_normal
            PlaybackBenchmarkCase.EXPLICIT -> Res.string.devtools_playback_benchmark_case_explicit
            PlaybackBenchmarkCase.KIDS -> Res.string.devtools_playback_benchmark_case_kids
        },
    )

@Composable
private fun PlaybackBenchmarkAssertions.failedLabels(): List<String> =
    buildList {
        if (!exactlyOneProfileAttempt) add(stringResource(Res.string.devtools_playback_benchmark_assert_one_player))
        if (!requestedProfileUsed) add(stringResource(Res.string.devtools_playback_benchmark_assert_profile))
        if (!playerResponseUsable) add(stringResource(Res.string.devtools_playback_benchmark_assert_usable))
        if (!noHttp4xxOrMediaFailure) add(stringResource(Res.string.devtools_playback_benchmark_assert_media))
        if (!stableDuration) add(stringResource(Res.string.devtools_playback_benchmark_assert_duration))
        if (!playbackAdvanced) add(stringResource(Res.string.devtools_playback_benchmark_assert_advanced))
        if (!seekSettled) add(stringResource(Res.string.devtools_playback_benchmark_assert_seek))
        if (!singleViewStart) add(stringResource(Res.string.devtools_playback_benchmark_assert_view_start))
        if (!oneLogicalNonce) add(stringResource(Res.string.devtools_playback_benchmark_assert_nonce))
        if (!nonOverlappingWatchtime) add(stringResource(Res.string.devtools_playback_benchmark_assert_watchtime))
        if (!finalRecorded) add(stringResource(Res.string.devtools_playback_benchmark_assert_final))
    }

private val BENCHMARK_SAMPLES =
    listOf(
        PlaybackBenchmarkSample("6LEs1yKXnb8", PlaybackBenchmarkCase.NORMAL),
        PlaybackBenchmarkSample("dQw4w9WgXcQ", PlaybackBenchmarkCase.NORMAL),
        PlaybackBenchmarkSample("5QBLlknVo7E", PlaybackBenchmarkCase.EXPLICIT),
        PlaybackBenchmarkSample("iVaqQe3V498", PlaybackBenchmarkCase.EXPLICIT),
        PlaybackBenchmarkSample("1-iKwZKc7Ok", PlaybackBenchmarkCase.KIDS),
        PlaybackBenchmarkSample("XqZsoesa55w", PlaybackBenchmarkCase.KIDS),
    )

private fun Long?.formatMilliseconds(): String = this?.let { "${it}ms" } ?: "n/a"

private const val BENCHMARK_CASE_MINIMUM_SECONDS = 96
