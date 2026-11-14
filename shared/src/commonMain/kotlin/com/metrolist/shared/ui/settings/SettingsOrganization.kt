package com.metrolist.shared.ui.settings

import androidx.compose.runtime.Composable
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.queue
import com.metrolist.shared.generated.resources.settings_home_library
import com.metrolist.shared.generated.resources.settings_home_library_description
import com.metrolist.shared.generated.resources.settings_more_options
import com.metrolist.shared.generated.resources.settings_navigation_gestures
import com.metrolist.shared.generated.resources.settings_navigation_gestures_description
import com.metrolist.shared.generated.resources.settings_queue_description
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun organizeSettingsRegistry(registry: SettingsUiRegistry): SettingsUiRegistry {
    val moreSettings = stringResource(Res.string.settings_more_options)
    return registry
        .withSubpage(
            SettingsPageId.APPEARANCE,
            SettingsPageId.APPEARANCE_NAVIGATION,
            stringResource(Res.string.settings_navigation_gestures),
            stringResource(Res.string.settings_navigation_gestures_description),
            moreSettings,
            groupIds = setOf("tabs", "gestures", "misc"),
        ).withSubpage(
            SettingsPageId.PLAYER,
            SettingsPageId.PLAYER_QUEUE,
            stringResource(Res.string.queue),
            stringResource(Res.string.settings_queue_description),
            moreSettings,
            groupIds = setOf("queue"),
        ).withSubpage(
            SettingsPageId.CONTENT,
            SettingsPageId.CONTENT_HOME,
            stringResource(Res.string.settings_home_library),
            stringResource(Res.string.settings_home_library_description),
            moreSettings,
            groupIds = setOf("homeSections", "autoPlaylists"),
        )
}

internal fun SettingsUiRegistry.withSubpage(
    parentId: SettingsPageId,
    id: SettingsPageId,
    title: String,
    subtitle: String,
    navigationGroupTitle: String,
    groupIds: Set<String> = emptySet(),
    itemIds: Set<String> = emptySet(),
): SettingsUiRegistry {
    val parent = get(parentId) ?: return this
    val moved = mutableListOf<SettingsGroupRegistration>()
    val remaining = mutableListOf<SettingsGroupRegistration>()
    parent.groups.forEach { group ->
        val (selected, retained) = group.items.partition { group.id in groupIds || it.id in itemIds }
        if (selected.isNotEmpty()) moved += group.copy(items = selected)
        if (retained.isNotEmpty()) remaining += group.copy(items = retained)
    }
    if (moved.isEmpty()) return this
    require(get(id) == null) { "Settings page already registered: $id" }
    val navigation =
        SettingsItemRegistration(
            id = "subpage:$id",
            title = title,
            description = subtitle,
            control = SettingsControl.NAVIGATION,
            destination = id,
        )
    val navigationGroup = remaining.firstOrNull { it.id == "subpages" }
    remaining.removeAll { it.id == "subpages" }
    remaining +=
        SettingsGroupRegistration(
            id = "subpages",
            title = navigationGroupTitle,
            items = navigationGroup?.items.orEmpty() + navigation,
        )
    val subpage = SettingsPageRegistration(id, title, subtitle, parent.icon, parent.tone, moved)
    return copy(pages = pages.map { if (it.id == parentId) it.copy(groups = remaining) else it } + subpage)
}
