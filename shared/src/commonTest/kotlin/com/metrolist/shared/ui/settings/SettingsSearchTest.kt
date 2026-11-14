package com.metrolist.shared.ui.settings

import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsSearchTest {
    @Test
    fun settingsWithoutDescriptionsRemainSearchableByTitle() {
        val appearance =
            SettingsPageRegistration(
                id = SettingsPageId.APPEARANCE,
                title = "Appearance",
                icon = MetrolistIcons.Outlined.Settings,
                tone = SettingsTone.NEUTRAL,
                groups =
                    listOf(
                        SettingsGroupRegistration(
                            id = "player",
                            title = "",
                            items =
                                listOf(
                                    SettingsItemRegistration(
                                        "roundedPlayButton",
                                        "Rounded rectangle play button",
                                        control = SettingsControl.SWITCH,
                                    ),
                                ),
                        ),
                    ),
            )
        val content =
            appearance.copy(
                id = SettingsPageId.CONTENT,
                title = "Content",
                groups =
                    listOf(
                        SettingsGroupRegistration(
                            id = "content",
                            title = "",
                            items =
                                listOf(
                                    "appLanguage" to "App language",
                                    "artistIconSource" to "Artist icon source",
                                    "artistBannerSource" to "Artist banner source",
                                ).map { (id, title) -> SettingsItemRegistration(id, title, control = SettingsControl.CHOICE) },
                        ),
                    ),
            )
        val androidAuto = appearance.copy(id = SettingsPageId.ANDROID_AUTO, title = "Android Auto", groups = emptyList(), custom = true)
        val together =
            androidAuto.copy(
                id = SettingsPageId.LISTEN_TOGETHER,
                title = "Listen Together",
                searchTerms = listOf("Auto-approve join requests"),
            )
        val registry = SettingsUiRegistry(listOf(appearance, androidAuto, content, together), emptyList())
        val queries =
            listOf(
                Triple("Appearance", appearance.id, null),
                Triple("Android Auto", androidAuto.id, null),
                Triple("Rounded rectangle play button", appearance.id, "roundedPlayButton"),
                Triple("App language", content.id, "appLanguage"),
                Triple("Artist icon source", content.id, "artistIconSource"),
                Triple("Artist banner source", content.id, "artistBannerSource"),
                Triple("Auto-approve join requests", together.id, null),
            )

        queries.forEach { (title, pageId, itemId) ->
            assertEquals(
                listOf(pageId to itemId),
                settingsSearchResults(registry, title.lowercase(), true).map { it.page.id to it.item?.id },
                title,
            )
        }
    }

    @Test
    fun disabledListenTogetherIsExcludedFromSearchButRemainsInIntegrations() {
        val together =
            SettingsPageRegistration(
                id = SettingsPageId.LISTEN_TOGETHER,
                title = "Listen Together",
                subtitle = "Rooms",
                icon = MetrolistIcons.Outlined.Group,
                tone = SettingsTone.BLUE,
                groups =
                    listOf(
                        SettingsGroupRegistration(
                            id = "room",
                            title = "Room",
                            items = listOf(SettingsItemRegistration("server", "Room server", control = SettingsControl.NAVIGATION)),
                        ),
                    ),
            )
        val device = together.copy(id = SettingsPageId.DEVICE_CONNECT, title = "Device Connect", groups = emptyList())
        val registry =
            SettingsUiRegistry(
                pages = listOf(together, device),
                rootGroups = listOf(SettingsRootGroupRegistration("Integrations", listOf(together.id, device.id))),
            )

        assertEquals(listOf(device.id), settingsSearchResults(registry, "room", false).map { it.page.id })
        assertEquals(
            listOf(together.id, together.id, device.id),
            settingsSearchResults(registry, "room", true).map { it.page.id },
        )
        assertEquals(together, registry[SettingsPageId.LISTEN_TOGETHER])
        assertEquals(listOf(together.id, device.id), registry.rootGroups.single().pages)
    }
}
