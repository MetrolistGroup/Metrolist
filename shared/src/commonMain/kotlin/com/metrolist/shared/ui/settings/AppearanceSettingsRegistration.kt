/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@file:Suppress("FunctionName", "ktlint:standard:no-wildcard-imports")

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.settings.AnimatedCoverResolution
import com.metrolist.shared.settings.AppLayout
import com.metrolist.shared.settings.DensityScale
import com.metrolist.shared.settings.LibraryGridColumnsRange
import com.metrolist.shared.settings.MiniPlayerType
import com.metrolist.shared.settings.MusicVideoQuality
import com.metrolist.shared.settings.PlayerBackgroundStyle
import com.metrolist.shared.settings.PlayerThumbnailStyle
import com.metrolist.shared.settings.PlayerType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.SliderStyle
import com.metrolist.shared.settings.ThemeMode
import com.metrolist.shared.settings.ThemePaletteStyle
import com.metrolist.shared.settings.YouTubeCanvasMode
import com.metrolist.shared.settings.desktopDefaultOpenTab
import com.metrolist.shared.settings.desktopDefaultOpenTabs
import com.metrolist.shared.settings.normalizeDefaultOpenTab
import com.metrolist.shared.settings.updateDefaultOpenTab
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun SettingsUiRegistryBuilder.registerAppearanceSettings(host: SharedSettingsHost) {
    val settings = Settings.shared
    val themeChoices =
        listOf(
            SettingsChoice(
                ThemeMode.AUTO.name,
                stringResource(Res.string.audio_quality_auto),
                preview = { ThemeModePreview(ThemeMode.AUTO) },
            ),
            SettingsChoice(
                ThemeMode.LIGHT.name,
                stringResource(Res.string.theme_mode_light),
                preview = { ThemeModePreview(ThemeMode.LIGHT) },
            ),
            SettingsChoice(
                ThemeMode.DARK.name,
                stringResource(Res.string.theme_mode_dark),
                preview = { ThemeModePreview(ThemeMode.DARK) },
            ),
            SettingsChoice(
                ThemeMode.PURE_BLACK.name,
                stringResource(Res.string.pure_black),
                preview = { ThemeModePreview(ThemeMode.PURE_BLACK) },
            ),
        )
    val selectedThemeMode = settings.themeMode
    val paletteChoices =
        listOf(
            SettingsChoice(ThemePaletteStyle.TONAL_SPOT.name, stringResource(Res.string.theme_palette_tonal_spot)),
            SettingsChoice(ThemePaletteStyle.VIBRANT.name, stringResource(Res.string.theme_palette_vibrant)),
            SettingsChoice(ThemePaletteStyle.EXPRESSIVE.name, stringResource(Res.string.expressive_style)),
            SettingsChoice(ThemePaletteStyle.RAINBOW.name, stringResource(Res.string.theme_palette_rainbow)),
            SettingsChoice(ThemePaletteStyle.FRUIT_SALAD.name, stringResource(Res.string.theme_palette_fruit_salad)),
            SettingsChoice(ThemePaletteStyle.MONOCHROME.name, stringResource(Res.string.theme_palette_monochrome)),
            SettingsChoice(ThemePaletteStyle.NEUTRAL.name, stringResource(Res.string.theme_palette_neutral)),
            SettingsChoice(ThemePaletteStyle.CONTENT.name, stringResource(Res.string.theme_palette_content)),
        )
    val selectedPalette = paletteChoices.first { it.id == settings.themePaletteStyle.name }.label
    page(
        id = SettingsPageId.APPEARANCE,
        title = stringResource(Res.string.appearance_title),
        icon = MetrolistIcons.Outlined.Palette,
        tone = SettingsTone.ORANGE,
        subtitle = stringResource(Res.string.settings_appearance_overview),
    ) {
        group("theme", stringResource(Res.string.appearance_theme)) {
            choice(
                id = "themeMode",
                title = stringResource(Res.string.theme_mode),
                valueLabel = themeChoices.first { it.id == selectedThemeMode.name }.label,
                choices = themeChoices,
                selectedChoiceId = selectedThemeMode.name,
            ) { id ->
                settings.themeMode = ThemeMode.valueOf(id)
            }
            val appIconChoices = host.hostChoices[SettingsHostChoice.APP_ICON].orEmpty()
            if (appIconChoices.isNotEmpty()) {
                val selectedIconLabel = appIconChoices.firstOrNull { it.id == settings.appIcon }?.label
                choice(
                    id = "appIcon",
                    title = stringResource(Res.string.appearance_app_icon),
                    description = stringResource(Res.string.appearance_app_icon_credit),
                    valueLabel = selectedIconLabel,
                    choices = appIconChoices,
                    selectedChoiceId = settings.appIcon,
                ) { host.onHostChoiceSelected(SettingsHostChoice.APP_ICON, it) }
            }
            switch(
                id = "materialYou",
                title = stringResource(Res.string.material_you),
                description = stringResource(Res.string.material_you_description),
                checked = settings.materialYouEnabled,
            ) { settings.materialYouEnabled = it }
            switch(
                id = "uiEnhancements",
                title = stringResource(Res.string.ui_enhancements),
                description = stringResource(Res.string.ui_enhancements_description),
                checked = settings.uiEnhancements,
            ) { settings.uiEnhancements = it }
            if (!settings.materialYouEnabled) {
                custom(
                    id = "themeColor",
                    title = stringResource(Res.string.settings_theme_color),
                    description = stringResource(Res.string.settings_theme_color_description),
                ) {
                    ThemeSeedColorSetting(
                        title = stringResource(Res.string.settings_theme_color),
                        description = stringResource(Res.string.settings_theme_color_description),
                        selectedValue = settings.selectedThemeColor,
                        onColorSelected = { color ->
                            settings.selectedThemeColor = color
                            host.onSeedColorSelected(color)
                        },
                    )
                }
            }
            switch(
                id = "dynamicTheme",
                title = stringResource(Res.string.song_artwork_colors),
                description = stringResource(Res.string.song_artwork_colors_description),
                checked = settings.dynamicTheme,
                enabled = settings.materialYouEnabled,
            ) { settings.dynamicTheme = it }
            choice(
                id = "paletteStyle",
                title = stringResource(Res.string.theme_palette_style),
                valueLabel = selectedPalette,
                choices = paletteChoices,
                selectedChoiceId = settings.themePaletteStyle.name,
                description = stringResource(Res.string.settings_palette_style_summary),
            ) { settings.themePaletteStyle = ThemePaletteStyle.valueOf(it) }
            if (SettingsPageId.FONTS in host.availablePages) {
                navigation(
                    id = "fonts",
                    title = stringResource(Res.string.fonts),
                    destination = SettingsPageId.FONTS,
                    description = stringResource(Res.string.settings_fonts_overview),
                )
            }
        }
        if (host.platform == SettingsPlatform.DESKTOP) {
            group("libraryGrid", stringResource(Res.string.library)) {
                choice(
                    id = "libraryGridColumns",
                    title = stringResource(Res.string.settings_library_grid_columns),
                    valueLabel = settings.libraryGridColumns.toString(),
                    choices = LibraryGridColumnsRange.map { SettingsChoice(it.toString(), it.toString()) },
                    selectedChoiceId = settings.libraryGridColumns.toString(),
                    description = stringResource(Res.string.settings_library_density_summary),
                ) { settings.libraryGridColumns = it.toInt() }
            }
        }
        if (host.platform == SettingsPlatform.ANDROID || host.platform == SettingsPlatform.DESKTOP) {
            val densityChoices =
                DensityScale.entries.map { scale ->
                    SettingsChoice(
                        scale.name,
                        stringResource(
                            when (scale) {
                                DensityScale.LARGE -> Res.string.density_large
                                DensityScale.SLIGHTLY_LARGE -> Res.string.density_slightly_large
                                DensityScale.NATIVE -> Res.string.density_native
                                DensityScale.SLIGHTLY_COMPACT -> Res.string.density_slightly_compact
                                DensityScale.COMPACT -> Res.string.density_compact
                                DensityScale.VERY_COMPACT -> Res.string.density_very_compact
                                DensityScale.ULTRA_COMPACT -> Res.string.density_ultra_compact
                            },
                        ),
                    )
                }
            val selectedDensity = DensityScale.fromValue(settings.densityScaleFactor)
            group("displayDensity", stringResource(Res.string.display_density)) {
                choice(
                    id = "densityScaleFactor",
                    title = stringResource(Res.string.display_density),
                    valueLabel = densityChoices.first { it.id == selectedDensity.name }.label,
                    choices = densityChoices,
                    selectedChoiceId = selectedDensity.name,
                    description = stringResource(Res.string.settings_interface_density_summary),
                ) { settings.densityScaleFactor = DensityScale.valueOf(it).value }
                if (host.platform == SettingsPlatform.ANDROID) {
                    switch(
                        id = "enableLandscapeScaling",
                        title = stringResource(Res.string.enable_landscape_scaling),
                        description = stringResource(Res.string.enable_landscape_scaling_desc),
                        checked = settings.enableLandscapeScaling,
                    ) { settings.enableLandscapeScaling = it }
                }
            }
        }
        if (host.platform == SettingsPlatform.DESKTOP) {
            group("tabs", stringResource(Res.string.appearance_navigation_tabs)) {
                val tabChoices =
                    settings.desktopDefaultOpenTabs().map { tab ->
                        SettingsChoice(
                            tab,
                            stringResource(
                                when (tab) {
                                    "HOME" -> Res.string.home
                                    "SEARCH" -> Res.string.search
                                    "TRACKS" -> Res.string.tracks
                                    "FAVORITES" -> Res.string.favorites
                                    "ARTISTS" -> Res.string.artists
                                    "ALBUMS" -> Res.string.albums
                                    "PLAYLISTS" -> Res.string.playlists
                                    else -> Res.string.library
                                },
                            ),
                        )
                    }
                val selectedTab = settings.desktopDefaultOpenTab()
                choice(
                    id = "defaultOpenTab",
                    title = stringResource(Res.string.appearance_default_open_tab),
                    valueLabel = tabChoices.first { it.id == selectedTab }.label,
                    choices = tabChoices,
                    selectedChoiceId = selectedTab,
                    description = stringResource(Res.string.settings_initial_tab_summary),
                ) { settings.updateDefaultOpenTab(it) }

                switch(
                    id = "forceBottomNavBar",
                    title = stringResource(Res.string.force_bottom_navbar),
                    checked = settings.forceBottomNavBar,
                    description = stringResource(Res.string.settings_bottom_navigation_summary),
                ) { settings.forceBottomNavBar = it }
            }
        }
        if (host.platform == SettingsPlatform.DESKTOP) {
            val appLayoutChoices =
                listOf(
                    SettingsChoice(AppLayout.EXPRESS.name, stringResource(Res.string.settings_layout_canvas)),
                    SettingsChoice(AppLayout.FOCUSED.name, stringResource(Res.string.settings_layout_stage)),
                )
            val selectedAppLayout =
                when (settings.appLayout) {
                    AppLayout.COMFORTABLE -> AppLayout.EXPRESS
                    AppLayout.DYNAMIC -> AppLayout.FOCUSED
                    else -> settings.appLayout
                }
            group("desktopLayouts", stringResource(Res.string.settings_layouts)) {
                choice(
                    id = "appLayout",
                    title = stringResource(Res.string.settings_app_layout),
                    valueLabel = appLayoutChoices.first { it.id == selectedAppLayout.name }.label,
                    choices = appLayoutChoices,
                    selectedChoiceId = selectedAppLayout.name,
                    description = stringResource(Res.string.settings_app_layout_summary),
                ) { settings.appLayout = AppLayout.valueOf(it) }
                switch(
                    id = "floatingSearch",
                    title = stringResource(Res.string.settings_floating_search),
                    description = stringResource(Res.string.settings_floating_search_description),
                    checked = settings.floatingSearch,
                ) { settings.floatingSearch = it }
                switch(
                    id = "systemWindowFrame",
                    title = stringResource(Res.string.settings_system_window_frame),
                    description = stringResource(Res.string.settings_system_window_frame_description),
                    checked = settings.enableSystemWindowFrame,
                ) {
                    settings.enableSystemWindowFrame = it
                    host.onSettingChanged(SettingsChangeEffect.RESTART, null)
                }
                switch(
                    id = "disableWaylandScaleFix",
                    title = stringResource(Res.string.settings_disable_wayland_fix),
                    description = stringResource(Res.string.settings_disable_wayland_fix_description),
                    checked = settings.disableWaylandScaleFix,
                ) {
                    settings.disableWaylandScaleFix = it
                    host.onSettingChanged(SettingsChangeEffect.RESTART, null)
                }
            }
        }
        if (host.platform != SettingsPlatform.DESKTOP) {
            group("playerAppearance", stringResource(Res.string.appearance_player)) {
                if (SettingsPageId.PLAYER_STYLE in host.availablePages) {
                    navigation(
                        id = "playerStyle",
                        title = stringResource(Res.string.appearance_player_style),
                        destination = SettingsPageId.PLAYER_STYLE,
                        description = stringResource(Res.string.appearance_player_style_description),
                    )
                }
                if (SettingsPageId.PLAYER_ARTWORK in host.availablePages) {
                    navigation(
                        id = "playerArtwork",
                        title = stringResource(Res.string.artwork),
                        destination = SettingsPageId.PLAYER_ARTWORK,
                        description = stringResource(Res.string.settings_artwork_overview),
                    )
                }
                if (SettingsPageId.MINI_PLAYER_STYLE in host.availablePages) {
                    navigation(
                        id = "miniPlayerStyle",
                        title = stringResource(Res.string.appearance_mini_player),
                        destination = SettingsPageId.MINI_PLAYER_STYLE,
                        description = stringResource(Res.string.settings_mini_player_overview),
                    )
                }
                switch(
                    id = "playVideosInMiniPlayer",
                    title = stringResource(Res.string.appearance_play_videos_in_mini_player),
                    description =
                        stringResource(
                            if (settings.musicVideoPlaybackEnabled) {
                                Res.string.appearance_play_videos_in_mini_player_desc
                            } else {
                                Res.string.mini_player_video_prerequisite
                            },
                        ),
                    enabled = settings.musicVideoPlaybackEnabled,
                    checked = settings.playVideosInMiniPlayer,
                ) { settings.playVideosInMiniPlayer = it }
                inlineSlider(
                    id = "swipeSensitivity",
                    title = stringResource(Res.string.appearance_mini_player_swipe_sensitivity),
                    valueLabel = "${settings.swipeSensitivity.roundToInt()}${stringResource(Res.string.percent_symbol)}",
                    value = settings.swipeSensitivity,
                    defaultValue = settings.defaultValue(settings::swipeSensitivity),
                    range = 0f..100f,
                    steps = 99,
                    description = stringResource(Res.string.settings_swipe_sensitivity_summary),
                ) { settings.swipeSensitivity = it }
            }
            group("tabs", stringResource(Res.string.appearance_navigation_tabs)) {
                switch(
                    id = "showHomeTab",
                    title = stringResource(Res.string.appearance_show_home_tab),
                    checked = settings.showBottomNavHome,
                ) {
                    settings.showBottomNavHome = it
                    settings.defaultOpenTab =
                        normalizeDefaultOpenTab(settings.defaultOpenTab, it, settings.showBottomNavSearch, settings.showBottomNavLibrary)
                }
                switch(
                    id = "showSearchTab",
                    title = stringResource(Res.string.appearance_show_search_tab),
                    checked = settings.showBottomNavSearch,
                ) {
                    settings.showBottomNavSearch = it
                    settings.defaultOpenTab =
                        normalizeDefaultOpenTab(settings.defaultOpenTab, settings.showBottomNavHome, it, settings.showBottomNavLibrary)
                }
                switch(
                    id = "showLibraryTab",
                    title = stringResource(Res.string.appearance_show_library_tab),
                    checked = settings.showBottomNavLibrary,
                ) {
                    settings.showBottomNavLibrary = it
                    settings.defaultOpenTab =
                        normalizeDefaultOpenTab(settings.defaultOpenTab, settings.showBottomNavHome, settings.showBottomNavSearch, it)
                }
                val tabChoices =
                    buildList {
                        if (settings.showBottomNavHome) add(SettingsChoice("HOME", stringResource(Res.string.home)))
                        if (settings.showBottomNavSearch) add(SettingsChoice("SEARCH", stringResource(Res.string.search)))
                        if (settings.showBottomNavLibrary) {
                            add(SettingsChoice("LIBRARY", stringResource(Res.string.library)))
                            if (host.platform == SettingsPlatform.ANDROID) {
                                add(SettingsChoice("TRACKS", stringResource(Res.string.tracks)))
                                add(SettingsChoice("FAVORITES", stringResource(Res.string.favorites)))
                            }
                            add(SettingsChoice("ARTISTS", stringResource(Res.string.artists)))
                            add(SettingsChoice("ALBUMS", stringResource(Res.string.albums)))
                            add(SettingsChoice("PLAYLISTS", stringResource(Res.string.playlists)))
                        }
                        if (isEmpty()) add(SettingsChoice("MYSTERIOUS", stringResource(Res.string.mysterious_fourth_tab)))
                    }
                val normalized =
                    normalizeDefaultOpenTab(
                        settings.defaultOpenTab,
                        settings.showBottomNavHome,
                        settings.showBottomNavSearch,
                        settings.showBottomNavLibrary,
                    )
                choice(
                    id = "defaultOpenTab",
                    title = stringResource(Res.string.appearance_default_open_tab),
                    valueLabel = tabChoices.firstOrNull { it.id == normalized }?.label,
                    choices = tabChoices,
                    selectedChoiceId = normalized,
                    description = stringResource(Res.string.settings_initial_tab_summary),
                ) { settings.updateDefaultOpenTab(it) }
                switch(
                    id = "slimNavigation",
                    title = stringResource(Res.string.appearance_slim_bottom_nav),
                    description = stringResource(Res.string.appearance_slim_bottom_nav_description),
                    checked = settings.slimNavBar,
                    enabled = !settings.floatingNavBar,
                ) { settings.slimNavBar = it }
                switch(
                    id = "floatingNavigation",
                    title = stringResource(Res.string.appearance_floating_bottom_nav),
                    description = stringResource(Res.string.appearance_floating_bottom_nav_description),
                    checked = settings.floatingNavBar,
                ) { settings.floatingNavBar = it }
                if (host.platform == SettingsPlatform.ANDROID) {
                    switch(
                        id = "floatingTopBar",
                        title = stringResource(Res.string.appearance_floating_top_bar),
                        description = stringResource(Res.string.appearance_floating_top_bar_description),
                        checked = settings.floatingTopBar,
                    ) { settings.floatingTopBar = it }
                }
                if (host.platform == SettingsPlatform.ANDROID) {
                    switch(
                        id = "forceBottomNavBar",
                        title = stringResource(Res.string.force_bottom_navbar),
                        checked = settings.forceBottomNavBar,
                        description = stringResource(Res.string.settings_bottom_navigation_summary),
                    ) { settings.forceBottomNavBar = it }
                    switch(
                        id = "voiceSearch",
                        title = stringResource(Res.string.voice_search),
                        description = stringResource(Res.string.voice_search_setting_desc),
                        checked = settings.voiceSearchEnabled,
                    ) { settings.voiceSearchEnabled = it }
                }
            }
        }
        if (host.platform == SettingsPlatform.DESKTOP) {
            group("playerAppearance", stringResource(Res.string.appearance_player)) {
                if (SettingsPageId.PLAYER_STYLE in host.availablePages) {
                    navigation(
                        id = "playerStyle",
                        title = stringResource(Res.string.appearance_player_style),
                        destination = SettingsPageId.PLAYER_STYLE,
                        description = stringResource(Res.string.appearance_player_style_description),
                    )
                }
                if (SettingsPageId.PLAYER_ARTWORK in host.availablePages) {
                    navigation(
                        id = "playerArtwork",
                        title = stringResource(Res.string.artwork),
                        destination = SettingsPageId.PLAYER_ARTWORK,
                        description = stringResource(Res.string.settings_artwork_overview),
                    )
                }
            }
        }
        if (host.platform != SettingsPlatform.DESKTOP) {
            group("gestures", stringResource(Res.string.song_gestures)) {
                switch(
                    id = "swipeThumbnail",
                    title = stringResource(Res.string.appearance_enable_swipe_to_change_track),
                    checked = settings.swipeThumbnail,
                ) { settings.swipeThumbnail = it }
                switch(
                    id = "swipeToSong",
                    title = stringResource(Res.string.appearance_swipe_song_gesture),
                    description = stringResource(Res.string.appearance_swipe_song_gesture_description),
                    checked = settings.swipeToSong,
                ) { settings.swipeToSong = it }
                if (host.platform == SettingsPlatform.ANDROID) {
                    switch(
                        id = "swipeToRemoveSong",
                        title = stringResource(Res.string.appearance_swipe_song_gesture_playlists),
                        description = stringResource(Res.string.appearance_swipe_song_gesture_playlists_description),
                        checked = settings.swipeToRemoveSong,
                    ) { settings.swipeToRemoveSong = it }
                }
            }
        }
        if (host.platform != SettingsPlatform.DESKTOP) {
            group("misc", stringResource(Res.string.appearance_misc)) {
                switch(
                    id = "haptics",
                    title = stringResource(Res.string.appearance_enable_haptic_feedback),
                    description = stringResource(Res.string.appearance_enable_haptic_feedback_description),
                    checked = settings.enableHapticFeedback,
                ) { settings.enableHapticFeedback = it }
            }
        }
    }

    if (SettingsPageId.FONTS in host.availablePages && (host.fontPrimaryActions.isNotEmpty() || host.fontDownloadActions.isNotEmpty())) {
        page(
            id = SettingsPageId.FONTS,
            title = stringResource(Res.string.fonts),
            subtitle = stringResource(Res.string.settings_fonts_overview),
            icon = MetrolistIcons.Outlined.Tune,
            tone = SettingsTone.ORANGE,
        ) {
            if (host.fontPrimaryActions.isNotEmpty()) {
                group("fonts", stringResource(Res.string.fonts)) {
                    host.fontPrimaryActions.forEach { item ->
                        action(
                            item.id,
                            item.title,
                            item.description,
                            confirmationMessage = item.confirmationMessage,
                            enabled = item.enabled,
                            onClick = item.onClick,
                        )
                    }
                }
            }
            if (host.fontDownloadActions.isNotEmpty()) {
                group("downloads", stringResource(Res.string.app_font_downloads)) {
                    host.fontDownloadActions.forEach { item ->
                        action(
                            item.id,
                            item.title,
                            item.description,
                            confirmationMessage = item.confirmationMessage,
                            enabled = item.enabled,
                            onClick = item.onClick,
                        )
                    }
                }
            }
        }
    }

    if (SettingsPageId.PLAYER_STYLE in host.availablePages) {
        val thumbnailChoices =
            listOf(
                SettingsChoice(PlayerThumbnailStyle.COMPACT.name, stringResource(Res.string.compact_rounded)),
                SettingsChoice(PlayerThumbnailStyle.FULLSCREEN.name, stringResource(Res.string.full_screen)),
            )
        val selectedThumbnailStyle =
            settings.playerThumbnailStyle.takeIf { style -> thumbnailChoices.any { it.id == style.name } }
                ?: PlayerThumbnailStyle.COMPACT
        val playerTypeChoices =
            if (host.platform == SettingsPlatform.DESKTOP) {
                listOf(
                    SettingsChoice(PlayerType.MODERN.name, stringResource(Res.string.player_type_modern)),
                    SettingsChoice(PlayerType.VINTAGE.name, stringResource(Res.string.player_type_vintage)),
                )
            } else {
                listOf(
                    SettingsChoice(PlayerType.MODERN.name, stringResource(Res.string.player_type_modern)),
                    SettingsChoice(PlayerType.VINTAGE.name, stringResource(Res.string.player_type_vintage)),
                    SettingsChoice(PlayerType.CLASSIC.name, stringResource(Res.string.player_type_classic)),
                )
            }
        val selectedPlayerType =
            if (host.platform == SettingsPlatform.DESKTOP && settings.playerType == PlayerType.CLASSIC) {
                PlayerType.VINTAGE
            } else {
                settings.playerType
            }
        val playerBackgroundChoices =
            buildList {
                add(SettingsChoice(PlayerBackgroundStyle.BLURRED_ARTWORK.name, stringResource(Res.string.blurred_artwork)))
                add(SettingsChoice(PlayerBackgroundStyle.SOLID.name, stringResource(Res.string.settings_solid)))
                add(SettingsChoice(PlayerBackgroundStyle.GRADIENT.name, stringResource(Res.string.gradient)))
                add(SettingsChoice(PlayerBackgroundStyle.ANIMATED_GRADIENT.name, stringResource(Res.string.animated_gradient)))
                add(SettingsChoice(PlayerBackgroundStyle.LIQUID.name, stringResource(Res.string.liquid)))
            }
        val selectedBackground = settings.playerBackgroundStyle
        val selectedSliderStyle =
            when (settings.sliderStyle) {
                SliderStyle.LINEAR -> SliderStyle.SLIM
                SliderStyle.LINEAR_WAVY -> SliderStyle.WAVY
                else -> settings.sliderStyle
            }
        val sliderChoices = sliderStyleChoices()
        page(
            id = SettingsPageId.PLAYER_STYLE,
            title = stringResource(Res.string.appearance_player_style),
            subtitle = stringResource(Res.string.appearance_player_style_description),
            icon = MetrolistIcons.Outlined.Tune,
            tone = SettingsTone.ROSE,
        ) {
            group("layout", stringResource(Res.string.layout)) {
                if (host.platform != SettingsPlatform.IOS) {
                    switch(
                        id = "showPlayerCastButton",
                        title = stringResource(Res.string.show_player_cast_button),
                        checked = settings.showPlayerCastButton,
                        enabled = settings.castEnabled,
                        description = stringResource(Res.string.settings_player_control_summary),
                    ) { settings.showPlayerCastButton = it }
                    videoPlaybackSettings(host)
                    switch(
                        id = "showPlayerVideoButton",
                        title = stringResource(Res.string.show_player_video_button),
                        checked = settings.showPlayerVideoButton,
                        enabled = settings.musicVideoPlaybackEnabled,
                        description = stringResource(Res.string.settings_player_control_summary),
                    ) { settings.showPlayerVideoButton = it }
                }

                if (host.platform != SettingsPlatform.DESKTOP) {
                    choice(
                        id = "playerThumbnailStyle",
                        title = stringResource(Res.string.player_thumbnail_style),
                        valueLabel = thumbnailChoices.first { it.id == selectedThumbnailStyle.name }.label,
                        choices = thumbnailChoices,
                        selectedChoiceId = selectedThumbnailStyle.name,
                        description = stringResource(Res.string.settings_thumbnail_shape_summary),
                    ) { selected ->
                        val style = PlayerThumbnailStyle.valueOf(selected)
                        settings.playerThumbnailStyle = style
                    }
                }
                choice(
                    id = "playerType",
                    title = stringResource(Res.string.player_type),
                    description = stringResource(Res.string.settings_player_layout_summary),
                    valueLabel =
                        playerTypeChoices.firstOrNull { it.id == selectedPlayerType.name }?.label ?: playerTypeChoices.first().label,
                    choices = playerTypeChoices,
                    selectedChoiceId = selectedPlayerType.name,
                ) { settings.playerType = PlayerType.valueOf(it) }
                if (host.platform != SettingsPlatform.DESKTOP) {
                    switch(
                        id = "immersivePlayer",
                        title = stringResource(Res.string.immersive_player),
                        description = stringResource(Res.string.immersive_player_description),
                        checked = settings.immersivePlayer,
                    ) { settings.immersivePlayer = it }
                }
                switch(
                    id = "roundedPlayButton",
                    title = stringResource(Res.string.use_rounded_rect_play_button),
                    checked = settings.useRoundedRectPlayButton,
                ) { settings.useRoundedRectPlayButton = it }
                if (host.platform == SettingsPlatform.DESKTOP) {
                    switch(
                        id = "cropAlbumArt",
                        title = stringResource(Res.string.settings_crop_album_art),
                        description = stringResource(Res.string.settings_crop_album_art_description),
                        checked = settings.cropAlbumArt,
                    ) { settings.cropAlbumArt = it }
                    switch(
                        id = "swapPlayerControls",
                        title = stringResource(Res.string.settings_swap_player_controls),
                        description = stringResource(Res.string.settings_swap_player_controls_description),
                        checked = settings.swapPlayerControls,
                    ) { settings.swapPlayerControls = it }
                }
            }
            group("background", stringResource(Res.string.background)) {
                choice(
                    id = "playerBackground",
                    title = stringResource(Res.string.player_background_style),
                    valueLabel = playerBackgroundChoices.first { it.id == selectedBackground.name }.label,
                    choices = playerBackgroundChoices,
                    selectedChoiceId = selectedBackground.name,
                ) { settings.playerBackgroundStyle = PlayerBackgroundStyle.valueOf(it) }
            }
            group("progress", stringResource(Res.string.progress_bar)) {
                choice(
                    id = "sliderStyle",
                    title = stringResource(Res.string.slider_style),
                    valueLabel = sliderChoices.first { it.id == selectedSliderStyle.name }.label,
                    choices = sliderChoices,
                    selectedChoiceId = selectedSliderStyle.name,
                ) { selected ->
                    val newStyle =
                        when (SliderStyle.valueOf(selected)) {
                            SliderStyle.WAVY -> SliderStyle.LINEAR_WAVY
                            SliderStyle.SLIM -> SliderStyle.LINEAR
                            else -> SliderStyle.valueOf(selected)
                        }
                    settings.sliderStyle = newStyle
                }
                inlineSlider(
                    id = "sliderThickness",
                    enabled = selectedSliderStyle != SliderStyle.DEFAULT,
                    title = stringResource(Res.string.slider_track_thickness),
                    valueLabel = settings.sliderTrackThicknessDp.roundToInt().toString(),
                    value = settings.sliderTrackThicknessDp.coerceIn(2f, 10f),
                    defaultValue = settings.defaultValue(settings::sliderTrackThicknessDp),
                    range = 2f..10f,
                    steps = 7,
                ) { settings.sliderTrackThicknessDp = it.roundToInt().toFloat() }
            }
        }
    }

    if (SettingsPageId.PLAYER_ARTWORK in host.availablePages) {
        val resolutionChoices =
            AnimatedCoverResolution.entries.map { resolution ->
                SettingsChoice(
                    resolution.name,
                    stringResource(
                        when (resolution) {
                            AnimatedCoverResolution.LOW_360 -> Res.string.animated_cover_resolution_low
                            AnimatedCoverResolution.MEDIUM_720 -> Res.string.animated_cover_resolution_medium
                            AnimatedCoverResolution.HIGH_1080 -> Res.string.animated_cover_resolution_high
                            AnimatedCoverResolution.ORIGINAL -> Res.string.animated_cover_resolution_original
                        },
                    ),
                )
            }
        page(
            id = SettingsPageId.PLAYER_ARTWORK,
            title = stringResource(Res.string.artwork),
            subtitle = stringResource(Res.string.settings_artwork_overview),
            icon = MetrolistIcons.Outlined.Palette,
            tone = SettingsTone.ROSE,
        ) {
            val isAndroid = host.platform == SettingsPlatform.ANDROID
            val canvasEnabled = settings.isCanvasEnabled
            group("animatedCovers", stringResource(Res.string.settings_playback)) {
                if (isAndroid) {
                    switch(
                        id = "playerCanvasEnabled",
                        title = stringResource(Res.string.canvas),
                        description = stringResource(Res.string.player_canvas_desc),
                        checked = settings.playerCanvasEnabled,
                    ) { settings.playerCanvasEnabled = it }
                }
                switch(
                    id = "animatedCovers",
                    title = stringResource(Res.string.enable_animated_covers),
                    description = stringResource(Res.string.enable_animated_covers_desc),
                    checked = settings.enableAnimatedCovers,
                ) { settings.enableAnimatedCovers = it }
                if (host.platform != SettingsPlatform.DESKTOP) {
                    switch(
                        id = "disableAnimatedArtworkOnMobileData",
                        title = stringResource(Res.string.disable_animated_artwork_on_mobile_data),
                        description = stringResource(Res.string.disable_animated_artwork_on_mobile_data_desc),
                        checked = settings.disableAnimatedArtworkOnMobileData,
                    ) { settings.disableAnimatedArtworkOnMobileData = it }
                }
                choice(
                    id = "animatedCoverResolution",
                    title = stringResource(Res.string.animated_cover_resolution),
                    description = stringResource(Res.string.animated_cover_resolution_desc),
                    valueLabel = resolutionChoices.first { it.id == settings.animatedCoverResolution.name }.label,
                    choices = resolutionChoices,
                    selectedChoiceId = settings.animatedCoverResolution.name,
                    enabled = settings.enableAnimatedCovers,
                ) { settings.animatedCoverResolution = AnimatedCoverResolution.valueOf(it) }
            }
            group(
                "sources",
                stringResource(Res.string.artwork_sources),
                stringResource(Res.string.canvas_content_order_desc),
                enabled = canvasEnabled || settings.enableAnimatedCovers,
            ) {
                custom(
                    id = "canvasContentOrder",
                    title = stringResource(Res.string.canvas_content_order),
                ) {
                    CanvasContentOrderSetting(host.platform, canvasEnabled, settings.enableAnimatedCovers, host.onSpotifySignIn)
                }
            }
            if (host.platform != SettingsPlatform.IOS) {
                group("artistArtwork", stringResource(Res.string.settings_artist_sources)) {
                    switch(
                        id = "appleMusicArtistImages",
                        title = stringResource(Res.string.apple_music_artist_images_title),
                        description = stringResource(Res.string.apple_music_artist_images_description),
                        checked = settings.enableAppleMusicArtistImages,
                    ) { settings.enableAppleMusicArtistImages = it }
                    switch(
                        id = "appleMusicArtistVideos",
                        title = stringResource(Res.string.apple_music_artist_videos_title),
                        description = stringResource(Res.string.apple_music_artist_videos_description),
                        checked = settings.enableAppleMusicArtistVideos,
                    ) { settings.enableAppleMusicArtistVideos = it }
                    choice(
                        id = "appleMusicArtistVideoResolution",
                        title = stringResource(Res.string.apple_music_artist_video_quality_title),
                        description = stringResource(Res.string.apple_music_artist_video_quality_description),
                        valueLabel = resolutionChoices.first { it.id == settings.appleMusicArtistVideoResolution.name }.label,
                        choices = resolutionChoices,
                        selectedChoiceId = settings.appleMusicArtistVideoResolution.name,
                    ) { settings.appleMusicArtistVideoResolution = AnimatedCoverResolution.valueOf(it) }
                }
            }
            if (host.platform == SettingsPlatform.ANDROID) {
                group(
                    "youtubeCanvas",
                    stringResource(Res.string.youtube_video_canvas),
                    enabled = settings.isCanvasEnabled && settings.musicVideoPlaybackEnabled,
                ) {
                    val canvasQualityChoices =
                        MusicVideoQuality.entries.map { quality ->
                            SettingsChoice(
                                quality.name,
                                if (quality == MusicVideoQuality.AUTO) {
                                    stringResource(Res.string.music_video_quality_auto)
                                } else {
                                    stringResource(Res.string.music_video_quality_resolution, quality.name.drop(1))
                                },
                            )
                        }
                    val canvasModeChoices =
                        listOf(
                            SettingsChoice(YouTubeCanvasMode.SHORT_CLIPS.name, stringResource(Res.string.youtube_canvas_mode_short_clips)),
                            SettingsChoice(YouTubeCanvasMode.FULL_VIDEO.name, stringResource(Res.string.youtube_canvas_mode_full_video)),
                        )
                    choice(
                        id = "youtubeCanvasQuality",
                        title = stringResource(Res.string.youtube_canvas_quality),
                        description = stringResource(Res.string.youtube_canvas_quality_desc),
                        valueLabel = canvasQualityChoices.first { it.id == settings.youtubeCanvasQuality.name }.label,
                        choices = canvasQualityChoices,
                        selectedChoiceId = settings.youtubeCanvasQuality.name,
                    ) { settings.youtubeCanvasQuality = MusicVideoQuality.valueOf(it) }
                    choice(
                        id = "youtubeCanvasMode",
                        title = stringResource(Res.string.youtube_canvas_mode),
                        valueLabel = canvasModeChoices.first { it.id == settings.youtubeCanvasMode.name }.label,
                        choices = canvasModeChoices,
                        selectedChoiceId = settings.youtubeCanvasMode.name,
                        description = stringResource(Res.string.settings_canvas_playback_summary),
                    ) { settings.youtubeCanvasMode = YouTubeCanvasMode.valueOf(it) }
                }
            }
        }
    }

    if (SettingsPageId.MINI_PLAYER_STYLE in host.availablePages) {
        val miniPlayerTypeChoices =
            listOf(
                SettingsChoice(MiniPlayerType.EXPRESSIVE.name, stringResource(Res.string.player_type_expressive)),
                SettingsChoice(MiniPlayerType.MODERN.name, stringResource(Res.string.player_type_modern)),
                SettingsChoice(MiniPlayerType.VINTAGE.name, stringResource(Res.string.player_type_vintage)),
            )
        val miniPlayerBackgroundChoices =
            listOf(
                SettingsChoice("DEFAULT", stringResource(Res.string.themed)),
                SettingsChoice("BLURRED_ARTWORK", stringResource(Res.string.blurred_artwork)),
                SettingsChoice("GRADIENT", stringResource(Res.string.gradient)),
                SettingsChoice("SOLID_BLACK", stringResource(Res.string.solid_black)),
            )
        val selectedMiniPlayerBackground =
            settings.miniPlayerBackgroundStyle.takeIf { selected -> miniPlayerBackgroundChoices.any { it.id == selected } } ?: "DEFAULT"
        page(
            id = SettingsPageId.MINI_PLAYER_STYLE,
            title = stringResource(Res.string.appearance_mini_player),
            subtitle = stringResource(Res.string.settings_mini_player_overview),
            icon = MetrolistIcons.Outlined.PlayArrow,
            tone = SettingsTone.ROSE,
        ) {
            group("style", stringResource(Res.string.appearance_style)) {
                choice(
                    id = "miniPlayerType",
                    title = stringResource(Res.string.appearance_mini_player_type),
                    description = stringResource(Res.string.settings_player_layout_summary),
                    valueLabel = miniPlayerTypeChoices.first { it.id == settings.miniPlayerType.name }.label,
                    choices = miniPlayerTypeChoices,
                    selectedChoiceId = settings.miniPlayerType.name,
                ) { settings.miniPlayerType = MiniPlayerType.valueOf(it) }
                if (settings.miniPlayerType != MiniPlayerType.EXPRESSIVE) {
                    choice(
                        id = "miniPlayerBackground",
                        title = stringResource(Res.string.player_background_style),
                        valueLabel = miniPlayerBackgroundChoices.first { it.id == selectedMiniPlayerBackground }.label,
                        choices = miniPlayerBackgroundChoices,
                        selectedChoiceId = selectedMiniPlayerBackground,
                    ) { settings.miniPlayerBackgroundStyle = it }
                }
            }
            if (settings.miniPlayerType == MiniPlayerType.EXPRESSIVE) {
                group("expressiveControls", stringResource(Res.string.menu_controls)) {
                    switch(
                        id = "expressiveMiniPlayerCookieArtwork",
                        title = stringResource(Res.string.mini_player_cookie_artwork),
                        checked = settings.expressiveMiniPlayerCookieArtwork,
                    ) { settings.expressiveMiniPlayerCookieArtwork = it }
                    if (settings.expressiveMiniPlayerCookieArtwork) {
                        switch(
                            id = "expressiveMiniPlayerShapeMotion",
                            title = stringResource(Res.string.mini_player_shape_motion),
                            checked = settings.expressiveMiniPlayerShapeMotion,
                        ) { settings.expressiveMiniPlayerShapeMotion = it }
                    }
                    switch(
                        id = "showExpressiveMiniPlayerProgress",
                        title = stringResource(Res.string.mini_player_progress_overlay),
                        checked = settings.showExpressiveMiniPlayerProgress,
                        description = stringResource(Res.string.settings_mini_player_progress_summary),
                    ) { settings.showExpressiveMiniPlayerProgress = it }
                    switch(
                        id = "expressiveMiniPlayerArtworkPlayback",
                        title = stringResource(Res.string.mini_player_artwork_playback),
                        checked = settings.expressiveMiniPlayerArtworkPlayback,
                    ) { settings.expressiveMiniPlayerArtworkPlayback = it }
                    switch(
                        id = "showExpressiveMiniPlayerPreviousButton",
                        title = stringResource(Res.string.previous),
                        checked = settings.showExpressiveMiniPlayerPreviousButton,
                        description = stringResource(Res.string.settings_mini_player_control_summary),
                    ) { settings.showExpressiveMiniPlayerPreviousButton = it }
                    switch(
                        id = "showExpressiveMiniPlayerPlayPauseButton",
                        title = stringResource(Res.string.mini_player_play_pause_button),
                        checked = settings.showExpressiveMiniPlayerPlayPauseButton,
                        description = stringResource(Res.string.settings_mini_player_control_summary),
                    ) { settings.showExpressiveMiniPlayerPlayPauseButton = it }
                    switch(
                        id = "showExpressiveMiniPlayerNextButton",
                        title = stringResource(Res.string.next),
                        checked = settings.showExpressiveMiniPlayerNextButton,
                        description = stringResource(Res.string.settings_mini_player_control_summary),
                    ) { settings.showExpressiveMiniPlayerNextButton = it }
                    switch(
                        id = "showExpressiveMiniPlayerPlaylistButton",
                        title = stringResource(Res.string.add_to_playlist),
                        checked = settings.showExpressiveMiniPlayerPlaylistButton,
                        description = stringResource(Res.string.settings_mini_player_control_summary),
                    ) { settings.showExpressiveMiniPlayerPlaylistButton = it }
                    switch(
                        id = "showExpressiveMiniPlayerSubscribeButton",
                        title = stringResource(Res.string.subscribe),
                        checked = settings.showExpressiveMiniPlayerSubscribeButton,
                        description = stringResource(Res.string.settings_mini_player_control_summary),
                    ) { settings.showExpressiveMiniPlayerSubscribeButton = it }
                    switch(
                        id = "showExpressiveMiniPlayerLikeButton",
                        title = stringResource(Res.string.action_like),
                        checked = settings.showExpressiveMiniPlayerLikeButton,
                        description = stringResource(Res.string.settings_mini_player_control_summary),
                    ) { settings.showExpressiveMiniPlayerLikeButton = it }
                }
            }
            if (settings.miniPlayerType == MiniPlayerType.MODERN) {
                group("controls", stringResource(Res.string.menu_controls)) {
                    switch(
                        id = "showMiniPlayerPlaylistButton",
                        title = stringResource(Res.string.add_to_playlist),
                        checked = settings.showMiniPlayerPlaylistButton,
                        description = stringResource(Res.string.settings_mini_player_control_summary),
                    ) { settings.showMiniPlayerPlaylistButton = it }
                    switch(
                        id = "showMiniPlayerLikeButton",
                        title = stringResource(Res.string.action_like),
                        checked = settings.showMiniPlayerLikeButton,
                        description = stringResource(Res.string.settings_mini_player_control_summary),
                    ) { settings.showMiniPlayerLikeButton = it }
                    switch(
                        id = "showMiniPlayerSubscribeButton",
                        title = stringResource(Res.string.subscribe),
                        checked = settings.showMiniPlayerSubscribeButton,
                        description = stringResource(Res.string.settings_mini_player_control_summary),
                    ) { settings.showMiniPlayerSubscribeButton = it }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupRegistryBuilder.videoPlaybackSettings(host: SharedSettingsHost) {
    val settings = Settings.shared
    val musicVideoQualityChoices =
        MusicVideoQuality.entries.map { quality ->
            SettingsChoice(
                quality.name,
                if (quality == MusicVideoQuality.AUTO) {
                    stringResource(Res.string.music_video_quality_auto)
                } else {
                    stringResource(Res.string.music_video_quality_resolution, quality.name.drop(1))
                },
            )
        }
    switch(
        id = "musicVideoPlaybackEnabled",
        title = stringResource(Res.string.play_music_videos),
        description = stringResource(Res.string.play_music_videos_description),
        checked = settings.musicVideoPlaybackEnabled,
    ) { enabled ->
        settings.musicVideoPlaybackEnabled = enabled
        host.onSettingChanged(SettingsChangeEffect.VIDEO_PLAYBACK, enabled)
    }
    choice(
        id = "musicVideoQuality",
        title = stringResource(Res.string.music_video_quality),
        description = stringResource(Res.string.music_video_quality_desc),
        valueLabel = musicVideoQualityChoices.firstOrNull { it.id == settings.musicVideoQuality.name }?.label,
        choices = musicVideoQualityChoices,
        selectedChoiceId = settings.musicVideoQuality.name,
        enabled = settings.musicVideoPlaybackEnabled,
    ) { choiceId ->
        runCatching { MusicVideoQuality.valueOf(choiceId) }.getOrNull()?.let {
            settings.musicVideoQuality = it
        }
    }
}
