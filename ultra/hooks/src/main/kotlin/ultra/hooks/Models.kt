package ultra.hooks

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable

/* ---- S7: stream resolution ---- */

/** What upstream is about to play. [mediaId] is the YouTube video id for YouTube tracks. */
data class StreamRequest(
    val mediaId: String,
    val playlistId: String? = null,
)

/**
 * A directly playable stream, e.g. from a homelab server or an alternate API.
 * Deliberately independent of upstream's PlayerResponse/Format classes; the :app glue translates it.
 */
data class ResolvedStream(
    val url: String,
    val mimeType: String,
    /** Human-readable origin, surfaced in diagnostics (e.g. "homelab", "piped"). */
    val sourceName: String,
    val contentLengthBytes: Long? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val loudnessDb: Double? = null,
    val headers: Map<String, String> = emptyMap(),
    /** How long [url] stays valid; upstream re-resolves after this. */
    val ttlSeconds: Int = DEFAULT_TTL_SECONDS,
) {
    companion object {
        const val DEFAULT_TTL_SECONDS = 6 * 60 * 60
    }
}

sealed interface StreamResolution {
    /** Not handled here; the next resolver, then upstream's YouTube path, takes over. */
    data object Pass : StreamResolution

    data class Resolved(val stream: ResolvedStream) : StreamResolution

    /** Handled, but playback must fail with [cause] (e.g. a policy block). Use sparingly. */
    data class Failed(val cause: Throwable) : StreamResolution
}

/* ---- S8 / S9: UI ---- */

/** Lets ultra screens navigate without seeing upstream's NavController. */
interface UltraNavigator {
    fun navigate(route: String)
    fun navigateUp()
}

/**
 * A full-screen destination. The :app glue wraps [content] in the same chrome as upstream's own
 * settings screens (top bar with [titleRes], back button, player-aware insets), so [content]
 * only draws the body and should fill the space it is given.
 */
class UltraDestination(
    val route: String,
    @StringRes val titleRes: Int,
    val content: @Composable (UltraNavigator) -> Unit,
)

/** A row in the main Settings screen that navigates to [route]. */
class UltraSettingsEntry(
    val route: String,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
)

/* ---- diagnostics ---- */

/** How many contributors are registered per seam. */
data class HookStats(
    val installed: Boolean,
    val apiClient: Int,
    val streamClient: Int,
    val audioProcessors: Int,
    val streamResolvers: Int,
    val destinations: Int,
    val settingsEntries: Int,
)
