package com.metrolist.shared.ui.settings

import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsNavigationTest {
    private val registry = SettingsUiRegistry(emptyList(), emptyList())

    @Test
    fun settingsDestinationsStayInTheShellUnlessTheHostExplicitlyOpensThemExternally() {
        val externalPages = setOf(SettingsPageId.SUPPORTED_LINKS)
        val currentStack = listOf(SettingsPageId.APPEARANCE.name, SettingsPageId.FONTS.name)

        SettingsPageId.entries.filterNot { it in externalPages }.forEach { page ->
            assertEquals(
                listOf(page.name),
                registry.settingsPageStackOnNavigate(currentStack, page, externalPages = externalPages),
                "Selecting $page from the list or search must replace the detail stack",
            )
        }
        assertNull(registry.settingsPageStackOnNavigate(currentStack, SettingsPageId.SUPPORTED_LINKS, externalPages = externalPages))
        assertNull(
            registry.settingsPageStackOnNavigate(
                currentStack,
                SettingsPageId.LISTEN_TOGETHER,
                externalPages = setOf(SettingsPageId.LISTEN_TOGETHER),
            ),
        )
    }

    @Test
    fun nestedPagesKeepTheirParentAndReturnToItWithoutClosingExpandedSettings() {
        val destinations =
            listOf(
                SettingsPageId.APPEARANCE to SettingsPageId.FONTS,
                SettingsPageId.APPEARANCE to SettingsPageId.PLAYER_STYLE,
                SettingsPageId.PLAYER to SettingsPageId.EQUALIZER,
                SettingsPageId.PRIVACY to SettingsPageId.BLOCKED_ARTISTS,
                SettingsPageId.UPDATES to SettingsPageId.UPDATE_DETAILS,
                SettingsPageId.DEVTOOLS to SettingsPageId.PLAYBACK_BENCHMARK,
            )
        destinations.forEach { (parent, child) ->
            val rootStack = listOf(parent.name)
            val nested = registry.settingsPageStackOnNavigate(rootStack, child, parent)!!
            assertEquals(rootStack + child.name, nested)
            assertTrue(canNavigateBackFromSettings(nested, expanded = true))
            assertEquals(rootStack, nested.dropLast(1))
            assertFalse(canNavigateBackFromSettings(nested.dropLast(1), expanded = true))
            assertTrue(canNavigateBackFromSettings(nested.dropLast(1), expanded = false))
        }
    }

    @Test
    fun openingFontsFromTheDefaultExpandedPageRetainsAppearanceAsItsParent() {
        assertEquals(
            listOf(SettingsPageId.APPEARANCE.name, SettingsPageId.FONTS.name),
            registry.settingsPageStackOnNavigate(emptyList(), SettingsPageId.FONTS, SettingsPageId.APPEARANCE),
        )
    }

    @Test
    fun switchingCategoriesClearsNestedHistoryAndResizingPreservesTheBackDestination() {
        val stack = listOf(SettingsPageId.ABOUT.name, SettingsPageId.DEVTOOLS.name, SettingsPageId.PLAYBACK_BENCHMARK.name)
        assertTrue(canNavigateBackFromSettings(stack, expanded = true))
        assertTrue(canNavigateBackFromSettings(stack, expanded = false))
        assertEquals(listOf(SettingsPageId.STORAGE.name), registry.settingsPageStackOnNavigate(stack, SettingsPageId.STORAGE))
        assertFalse(canNavigateBackFromSettings(emptyList(), expanded = false))
        assertFalse(canNavigateBackFromSettings(emptyList(), expanded = true))
    }

    @Test
    fun searchAndReopeningNestedPagesRestoreTheirRegisteredParents() {
        val childrenByParent =
            mapOf(
                SettingsPageId.PLAYER to listOf(SettingsPageId.EQUALIZER, SettingsPageId.PLAYER_QUEUE),
                SettingsPageId.APPEARANCE to
                    listOf(
                        SettingsPageId.FONTS,
                        SettingsPageId.APPEARANCE_NAVIGATION,
                        SettingsPageId.PLAYER_STYLE,
                    ),
                SettingsPageId.CONTENT to listOf(SettingsPageId.CONTENT_HOME),
                SettingsPageId.PRIVACY to listOf(SettingsPageId.BLOCKED_ARTISTS, SettingsPageId.HIDDEN_LIBRARY_ITEMS),
                SettingsPageId.LYRICS to listOf(SettingsPageId.LYRICS_SOURCES),
            )
        childrenByParent.forEach { (parent, children) ->
            val registry = navigationRegistry(parent, children)
            children.forEach { child ->
                val expected = listOf(parent.name, child.name)
                assertEquals(expected, registry.settingsPageStack(child))
                assertEquals(expected, registry.settingsPageStackOnNavigate(listOf(SettingsPageId.STORAGE.name), child))
                assertTrue(canNavigateBackFromSettings(expected, expanded = true))
                assertEquals(listOf(parent.name), expected.dropLast(1))
            }
        }
    }

    @Test
    fun reopeningDeeperPagesRestoresTheWholeParentPath() {
        val registry = navigationRegistry(SettingsPageId.APPEARANCE, listOf(SettingsPageId.APPEARANCE_NAVIGATION))
        val nestedRegistry = navigationRegistry(SettingsPageId.APPEARANCE_NAVIGATION, listOf(SettingsPageId.MINI_PLAYER_STYLE))
        val stack = registry.copy(pages = registry.pages + nestedRegistry.pages).settingsPageStack(SettingsPageId.MINI_PLAYER_STYLE)
        assertEquals(
            listOf(SettingsPageId.APPEARANCE.name, SettingsPageId.APPEARANCE_NAVIGATION.name, SettingsPageId.MINI_PLAYER_STYLE.name),
            stack,
        )
        assertTrue(canNavigateBackFromSettings(stack.dropLast(1), expanded = true))
        assertFalse(canNavigateBackFromSettings(stack.dropLast(2), expanded = true))
    }

    @Test
    fun registeredTopLevelPagesHaveNoExpandedBackButtonEvenWhenAnotherPageLinksToThem() {
        val registry =
            navigationRegistry(SettingsPageId.ABOUT, listOf(SettingsPageId.DEVTOOLS)).copy(
                rootGroups = listOf(SettingsRootGroupRegistration("", listOf(SettingsPageId.ABOUT, SettingsPageId.DEVTOOLS))),
            )
        val stack = registry.settingsPageStack(SettingsPageId.DEVTOOLS)
        assertEquals(listOf(SettingsPageId.DEVTOOLS.name), stack)
        assertFalse(canNavigateBackFromSettings(stack, expanded = true))
    }

    private fun navigationRegistry(
        parent: SettingsPageId,
        children: List<SettingsPageId>,
    ): SettingsUiRegistry =
        SettingsUiRegistry(
            pages =
                listOf(
                    SettingsPageRegistration(
                        id = parent,
                        title = "",
                        subtitle = "",
                        icon = MetrolistIcons.Outlined.Settings,
                        tone = SettingsTone.NEUTRAL,
                        groups =
                            listOf(
                                SettingsGroupRegistration(
                                    id = "children",
                                    title = "",
                                    items =
                                        children.map { child ->
                                            SettingsItemRegistration(
                                                id = child.name,
                                                title = "",
                                                control = SettingsControl.NAVIGATION,
                                                destination = child,
                                            )
                                        },
                                ),
                            ),
                    ),
                ),
            rootGroups = listOf(SettingsRootGroupRegistration("", listOf(parent))),
        )
}
