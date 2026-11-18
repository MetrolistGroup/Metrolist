package com.metrolist.android.ui.glass

import androidx.compose.runtime.staticCompositionLocalOf

/** A role is a real owner, not an additional material layered over a shared surface. */
enum class GlassScope(val areas: Set<GlassArea>) {
    Shared(emptySet()),
    Home(setOf(GlassArea.ActionButtons, GlassArea.MoodCards)),
    Library(setOf(GlassArea.ActionButtons)),
    Search(setOf(GlassArea.ActionButtons)),
    Details(setOf(GlassArea.ActionButtons, GlassArea.SettingsCards)),
    SettingsProfiles(setOf(GlassArea.ActionButtons, GlassArea.SettingsCards)),
    Rewind(setOf(GlassArea.ActionButtons, GlassArea.MoodCards)),
    Onboarding(setOf(GlassArea.ActionButtons, GlassArea.SettingsCards)),
    PlayerHeader(setOf(GlassArea.ActionButtons)),
    PlayerInformation(setOf(GlassArea.ActionButtons)),
    PlayerFavorite(setOf(GlassArea.ActionButtons)),
    PlayerTime(setOf(GlassArea.ActionButtons)),
    PlayerSeek(setOf(GlassArea.ActionButtons)),
    PlayerTransport(setOf(GlassArea.ActionButtons)),
    PlayerActions(setOf(GlassArea.ActionButtons)),
    Lyrics(setOf(GlassArea.ActionButtons)),
    Visualizer(setOf(GlassArea.ActionButtons)),
    Queue(setOf(GlassArea.Menus, GlassArea.ActionButtons)),
    Toast(setOf(GlassArea.Menus));
}

/** Stable keys also make unknown future roles harmless to older versions. */
data class GlassContextArea(val scope: GlassScope, val area: GlassArea) {
    val preferenceKey: String get() = "context.${scope.name}.${area.legacyKey}_mode"
}

val LocalGlassScope = staticCompositionLocalOf { GlassScope.Shared }

/** Explicit role selection starts from the original saved maps, never another role's result. */
fun GlassSettings.inScope(scope: GlassScope): GlassSettings = copy(scope = scope)

fun GlassSettings.contextMode(scope: GlassScope, area: GlassArea): GlassAreaMode =
    copy(scope = scope).mode(area)

fun glassScopeForRoute(route: String?): GlassScope {
    val destination = route.orEmpty().substringBefore('/').substringBefore('?').lowercase()
    return when (destination) {
        "rewind", "rewindhome" -> GlassScope.Rewind
        "settings", "profiles" -> GlassScope.SettingsProfiles
        "search", "searchresults" -> GlassScope.Search
        "home", "mood", "moodspage", "newalbums" -> GlassScope.Home
        "album", "artist", "localplaylist", "playlist", "podcast", "artistalbums", "artistvideos", "artistplaylists", "artistinsights", "albuminsights" -> GlassScope.Details
        "queue" -> GlassScope.Queue
        "history", "statistics" -> GlassScope.Library
        else -> GlassScope.Shared
    }
}
