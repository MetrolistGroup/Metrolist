package com.metrolist.shared.ui.settings

internal fun SettingsUiRegistry.settingsPageStack(pageId: SettingsPageId): List<String> {
    val path = mutableListOf<SettingsPageId>()
    var current: SettingsPageId? = pageId
    while (current != null && current !in path) {
        path += current
        if (rootGroups.any { current in it.pages }) break
        current =
            pages
                .firstOrNull { page ->
                    page.groups.any { group -> group.items.any { it.destination == current } }
                }?.id
    }
    return path.asReversed().map { it.name }
}

internal fun SettingsUiRegistry.settingsPageStackOnNavigate(
    pageStack: List<String>,
    pageId: SettingsPageId,
    parentPageId: SettingsPageId? = null,
    externalPages: Set<SettingsPageId> = emptySet(),
): List<String>? =
    if (pageId in externalPages) {
        null
    } else if (parentPageId == null) {
        settingsPageStack(pageId)
    } else {
        pageStack.ifEmpty { listOf(parentPageId.name) } + pageId.name
    }

internal fun canNavigateBackFromSettings(
    pageStack: List<String>,
    expanded: Boolean,
): Boolean = pageStack.size > if (expanded) 1 else 0
