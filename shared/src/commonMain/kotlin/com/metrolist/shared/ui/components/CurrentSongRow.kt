package com.metrolist.shared.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.queue_current_paused
import com.metrolist.shared.generated.resources.queue_current_playing
import org.jetbrains.compose.resources.stringResource

data class CurrentSongColors(
    val container: Color,
    val content: Color,
    val subtitle: Color,
    val link: Color,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun animateCurrentSongColors(
    isCurrent: Boolean,
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    container: Color = Color.Transparent,
    content: Color = colorScheme.onSurface,
    subtitle: Color = colorScheme.onSurfaceVariant,
): CurrentSongColors {
    val progress by animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "currentSongColors",
    )
    return CurrentSongColors(
        container = lerp(container, colorScheme.primaryContainer, progress),
        content = lerp(content, colorScheme.onPrimaryContainer, progress),
        subtitle = lerp(subtitle, colorScheme.onPrimaryContainer, progress),
        link = lerp(colorScheme.primary, colorScheme.onPrimaryContainer, progress),
    )
}

@Composable
fun Modifier.currentSongSemantics(
    isCurrent: Boolean,
    isPlaying: Boolean,
): Modifier {
    if (!isCurrent) return this
    val description = stringResource(if (isPlaying) Res.string.queue_current_playing else Res.string.queue_current_paused)
    return semantics { stateDescription = description }
}
