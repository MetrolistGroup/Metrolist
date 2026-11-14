/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.loading
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.ContainedLoadingIndicator as MaterialContainedLoadingIndicator

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContainedLoadingIndicator(
    modifier: Modifier = Modifier,
    containerColor: Color = LoadingIndicatorDefaults.containedContainerColor,
    indicatorColor: Color = LoadingIndicatorDefaults.containedIndicatorColor,
    label: String = stringResource(Res.string.loading),
) {
    MaterialContainedLoadingIndicator(
        modifier =
            modifier.semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        containerColor = containerColor,
        indicatorColor = indicatorColor,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContainedLoadingIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    containerColor: Color = LoadingIndicatorDefaults.containedContainerColor,
    indicatorColor: Color = LoadingIndicatorDefaults.containedIndicatorColor,
    label: String = stringResource(Res.string.loading),
) {
    MaterialContainedLoadingIndicator(
        progress = progress,
        modifier =
            modifier.semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        containerColor = containerColor,
        indicatorColor = indicatorColor,
    )
}

@Composable
fun LoadingScreen(
    modifier: Modifier = Modifier,
    label: String = stringResource(Res.string.loading),
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        ContainedLoadingIndicator(label = label)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WavyCircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    animate: Boolean = true,
) {
    CircularWavyProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        waveSpeed = if (animate) WavyProgressIndicatorDefaults.CircularWavelength else 0.dp,
    )
}
