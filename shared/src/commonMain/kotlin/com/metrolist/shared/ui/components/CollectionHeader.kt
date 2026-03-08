package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.go_back
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.theme.uiEnhanced
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource

val CollectionHeaderHorizontalPadding = 16.dp

internal fun mediumCollectionArtworkSize(
    contentWidth: Dp,
    contentHeight: Dp,
): Dp =
    minOf(
        256.dp,
        (contentWidth - CollectionHeaderHorizontalPadding * 2 - 24.dp) * 0.4f,
        // Lazy headers have unbounded height; use the viewport to leave room for songs.
        contentHeight.coerceAtLeast(0.dp) * 0.4f,
    )

fun collectionCoverShape(): RoundedCornerShape = RoundedCornerShape(uiEnhanced(8.dp, 28.dp))

fun collectionInfoCardShape(): RoundedCornerShape = RoundedCornerShape(uiEnhanced(8.dp, 20.dp))

@Composable
internal fun CollectionHeader(
    isWide: Boolean,
    isMedium: Boolean,
    modifier: Modifier = Modifier,
    mediumArtworkSize: Dp = 176.dp,
    artwork: @Composable (Modifier, Dp) -> Unit,
    actions: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val headerModifier =
        when {
            isWide -> Modifier.fillMaxWidth()
            isMedium -> Modifier.fillMaxWidth().padding(horizontal = CollectionHeaderHorizontalPadding, vertical = 16.dp)
            else -> Modifier.fillMaxWidth().padding(horizontal = CollectionHeaderHorizontalPadding).padding(vertical = 8.dp)
        }
    Column(modifier.then(headerModifier)) {
        if (isMedium && !isWide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                artwork(Modifier.size(mediumArtworkSize), uiEnhanced(8.dp, 16.dp))
                CollectionInfoCard(Modifier.weight(1f), content)
            }
        } else {
            artwork(Modifier.fillMaxWidth().aspectRatio(1f), uiEnhanced(8.dp, if (isWide) 20.dp else 28.dp))
            Spacer(Modifier.height(16.dp))
            CollectionInfoCard(content = content)
        }
        actions?.let {
            Spacer(Modifier.height(16.dp))
            it()
        }
    }
}

@Composable
fun CollectionInfoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = collectionInfoCardShape(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailBackButton(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onBackClick,
        modifier = modifier.padding(start = 8.dp, top = 8.dp).tvFocusHighlight(CircleShape),
        shapes = IconButtonDefaults.shapes(),
    ) {
        Icon(
            MetrolistIcons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(Res.string.go_back),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CollectionHeaderNavigation(
    onBackClick: (() -> Unit)?,
    onMoreOptions: (() -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    selectionControls: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (selectionControls != null) {
            Column(Modifier.fillMaxSize()) {
                selectionControls()
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        } else {
            Box(Modifier.padding(top = if (onBackClick != null) 64.dp else 0.dp)) { content() }
            if (onBackClick != null) {
                if (title != null) {
                    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.CenterStart) { title() }
                }
                DetailBackButton(onBackClick)
                if (onMoreOptions != null) {
                    IconButton(
                        onClick = onMoreOptions,
                        modifier =
                            Modifier
                                .align(
                                    Alignment.TopEnd,
                                ).padding(end = 8.dp, top = 8.dp)
                                .tvFocusHighlight(CircleShape)
                                .navigationPopupSource(),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.more_options))
                    }
                }
            }
        }
    }
}
