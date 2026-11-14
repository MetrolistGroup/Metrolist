package com.metrolist.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.loading
import com.valentinilk.shimmer.shimmer
import org.jetbrains.compose.resources.stringResource

@Composable
fun CollectionLoadingPlaceholder(modifier: Modifier = Modifier) {
    val loadingText = stringResource(Res.string.loading)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = lifecycleState == Lifecycle.State.RESUMED
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics {
                contentDescription = loadingText
                liveRegion = LiveRegionMode.Polite
            }.then(if (animate) Modifier.shimmer() else Modifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        when {
            maxWidth >= 960.dp -> {
                Row(
                    Modifier.widthIn(max = 1400.dp).fillMaxWidth().padding(horizontal = CollectionHeaderHorizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                ) {
                    CollectionHeaderPlaceholder(Modifier.width(360.dp))
                    CollectionSongsPlaceholder(Modifier.weight(1f))
                }
            }

            maxWidth >= 600.dp -> {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = CollectionHeaderHorizontalPadding, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CollectionCoverPlaceholder(Modifier.size(160.dp))
                        CollectionDetailsPlaceholder(Modifier.weight(1f))
                    }
                    CollectionSongsPlaceholder()
                }
            }

            else -> {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = CollectionHeaderHorizontalPadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CollectionHeaderPlaceholder()
                    CollectionSongsPlaceholder()
                }
            }
        }
    }
}

@Composable
fun CollectionSongsLoadingPlaceholder(modifier: Modifier = Modifier) {
    val loadingText = stringResource(Res.string.loading)
    CollectionSongsPlaceholder(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CollectionHeaderHorizontalPadding, vertical = 8.dp)
            .clearAndSetSemantics {
                contentDescription = loadingText
                liveRegion = LiveRegionMode.Polite
            }.loadingShimmer(),
    )
}

@Composable
private fun CollectionCoverPlaceholder(modifier: Modifier) {
    Spacer(modifier.clip(collectionCoverShape()).background(MaterialTheme.colorScheme.surfaceContainerHigh))
}

@Composable
private fun CollectionHeaderPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CollectionCoverPlaceholder(Modifier.fillMaxWidth().aspectRatio(1f))
        CollectionDetailsPlaceholder()
    }
}

@Composable
private fun CollectionDetailsPlaceholder(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.fillMaxWidth(0.7f).height(32.dp).background(color))
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(collectionInfoCardShape())
                .background(color),
        )
    }
}

@Composable
private fun CollectionSongsPlaceholder(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(6) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CollectionCoverPlaceholder(Modifier.size(48.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.fillMaxWidth().height(18.dp).background(color))
                    Spacer(Modifier.fillMaxWidth(0.6f).height(14.dp).background(color))
                }
            }
        }
    }
}
