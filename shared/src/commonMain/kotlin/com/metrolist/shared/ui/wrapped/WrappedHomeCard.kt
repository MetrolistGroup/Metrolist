@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.wrapped

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.wrapped_home_body
import com.metrolist.shared.generated.resources.wrapped_home_card_dismissed
import com.metrolist.shared.generated.resources.wrapped_home_title
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.utils.toPolygonShape
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** The Home card runs through December until it is swiped away or the recap is watched to its last chapter. */
internal fun wrappedHomeCardDue(today: LocalDate): Boolean =
    today.month == Month.DECEMBER && Settings.shared.wrappedCardDismissedYear != today.year

/**
 * A December Home card announcing this year's Metrolist Wrapped. Tapping opens the recap; swiping it away
 * retires it for the year and reports where Wrapped stays available through [onDismissed].
 */
@Composable
fun WrappedHomeCard(
    onClick: () -> Unit,
    onDismissed: (message: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed || !wrappedHomeCardDue(today)) return
    val message = stringResource(Res.string.wrapped_home_card_dismissed, today.year.toString())
    val state =
        remember {
            SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { it * 0.4f })
        }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {},
        onDismiss = {
            Settings.shared.wrappedCardDismissedYear = today.year
            dismissed = true
            onDismissed(message)
        },
    ) {
        WrappedHomeCardContent(today.year.toString(), onClick)
    }
}

@Composable
private fun WrappedHomeCardContent(
    year: String,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val dark = c.surface.luminance() < 0.5f
    val container = if (dark) c.primaryContainer else c.primaryFixed
    val content = if (dark) c.onPrimaryContainer else c.onPrimaryFixed
    val badge = if (dark) c.tertiary else c.tertiaryFixedDim
    val onBadge = if (dark) c.onTertiary else c.onTertiaryFixed
    val turn: State<Float>? =
        if (wrappedMotionEnabled()) {
            rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)))
        } else {
            null
        }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = container,
        contentColor = content,
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { rotationZ = turn?.value ?: 0f }
                        .background(badge, MaterialShapes.SoftBurst.toPolygonShape()),
                )
                Text(year.takeLast(2), color = onBadge, style = wrappedLabelStyle())
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.wrapped_home_title, year),
                    style = wrappedTitleStyle(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(stringResource(Res.string.wrapped_home_body), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(MetrolistIcons.AutoMirrored.Filled.ArrowForward, null)
        }
    }
}
