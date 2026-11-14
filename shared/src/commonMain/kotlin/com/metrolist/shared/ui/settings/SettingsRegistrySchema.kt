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

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class SettingsPlatform {
    ANDROID,
    DESKTOP,
    IOS,
}

enum class SettingsPageId {
    APPEARANCE,
    APPEARANCE_NAVIGATION,
    PLAYER,
    PLAYER_QUEUE,
    CONTENT,
    CONTENT_HOME,
    LYRICS,
    PRIVACY,
    ANDROID_AUTO,
    FONTS,
    PLAYER_STYLE,
    PLAYER_ARTWORK,
    MINI_PLAYER_STYLE,
    LYRICS_SOURCES,
    LYRICS_AI_TRANSLATION,
    LYRICS_AI_SETUP,
    LYRICS_ROMANIZATION,
    HIDDEN_HOME_ITEMS,
    HIDDEN_LIBRARY_ITEMS,
    BLOCKED_ARTISTS,
    STORAGE,
    BACKUP_RESTORE,
    UPDATES,
    UPDATE_DETAILS,
    ABOUT,
    DEVTOOLS,
    PLAYBACK_BENCHMARK,
    SUPPORTED_LINKS,
    DISCORD,
    LAST_FM,
    LISTEN_BRAINZ,
    DEVICE_CONNECT,
    LISTEN_TOGETHER,
    EQUALIZER,
}

enum class SettingsChangeEffect {
    AUDIO,
    CONTENT,
    SENTRY,
    AUTO_DOWNLOAD_ON_LIKE,
    RESTART,
    PLAYBACK_SPEED,
    PLAYBACK_PITCH,
    LOGGING,
    VIDEO_PLAYBACK,
}

enum class SettingsHostChoice {
    CONTENT_LANGUAGE,
    CONTENT_COUNTRY,
    APP_LANGUAGE,
    LYRICS_TARGET_LANGUAGE,
    APP_ICON,
    ARTIST_ICON_SOURCE,
    ARTIST_BANNER_SOURCE,
}

data class SettingsHostAction(
    val id: String,
    val title: String,
    val description: String? = null,
    val valueLabel: String? = null,
    val confirmationMessage: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

data class SharedSettingsHost(
    val platform: SettingsPlatform,
    val onClose: () -> Unit,
    val onOpenArtist: (String) -> Unit = {},
    val title: String? = null,
    val bottomContentPadding: Dp = 0.dp,
    val storageSummary: String? = null,
    val updateSummary: String? = null,
    val availablePages: Set<SettingsPageId> = emptySet(),
    val externalPages: Set<SettingsPageId> = emptySet(),
    val initialPage: SettingsPageId? = null,
    val singlePane: Boolean = false,
    val onPageChanged: (SettingsPageId?) -> Unit = {},
    val onOpenPage: (SettingsPageId) -> Unit = {},
    val onSettingChanged: (SettingsChangeEffect, Boolean?) -> Unit = { _, _ -> },
    val onFloatSettingChanged: (SettingsChangeEffect, Float) -> Unit = { _, _ -> },
    val hostChoices: Map<SettingsHostChoice, List<SettingsChoice>> = emptyMap(),
    val onHostChoiceSelected: (SettingsHostChoice, String) -> Unit = { _, _ -> },
    val onSeedColorSelected: (Long) -> Unit = {},
    val lyricsPreview: @Composable (Modifier) -> Unit = { LyricsSettingsPreview(it) },
    val playbackSpeedEnabled: Boolean = true,
    val isAudioProcessingAvailable: Boolean = true,
    val playbackSpeedOverride: Float? = null,
    val playbackSpeedDescription: String? = null,
    val homeSections: List<SettingsHomeSection> = emptyList(),
    val onHomeSectionVisibilityChanged: (String, Boolean) -> Unit = { _, _ -> },
    val fontPrimaryActions: List<SettingsHostAction> = emptyList(),
    val fontDownloadActions: List<SettingsHostAction> = emptyList(),
    val privacyDiagnosticActions: List<SettingsHostAction> = emptyList(),
    val onClearSearchHistory: (() -> Unit)? = null,
    val onSpotifySignIn: (() -> Unit)? = null,
    val onSwitchYouTubeChannel: (() -> Unit)? = null,
    val customPageIcons: Map<SettingsPageId, @Composable (Color) -> Unit> = emptyMap(),
    val pageContent: @Composable (SettingsPageId, onBack: () -> Unit, onNavigate: (SettingsPageId) -> Unit) -> Unit = { _, _, _ -> },
    val rememberMetroEqProfilePicker: (@Composable (onPicked: (fileName: String, content: String) -> Unit) -> () -> Unit)? = null,
)

enum class SettingsControl {
    SWITCH,
    MAIN_SWITCH,
    CHOICE,
    SLIDER,
    TEXT,
    NAVIGATION,
    ACTION,
    CUSTOM,
}

data class SettingsChoice(
    val id: String,
    val label: String,
    val preview: (@Composable () -> Unit)? = null,
)

data class SettingsHomeSection(
    val id: String,
    val preferenceKey: String,
    val title: String,
    val description: String,
    val visible: Boolean,
)

data class SettingsItemRegistration(
    val id: String,
    val title: String,
    val description: String? = null,
    val control: SettingsControl,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val valueLabel: String? = null,
    val choices: List<SettingsChoice> = emptyList(),
    val selectedChoiceId: String? = null,
    val sliderValue: Float? = null,
    val sliderRange: ClosedFloatingPointRange<Float>? = null,
    val sliderSteps: Int = 0,
    val sliderValueFormatter: (@Composable (Float) -> String)? = null,
    val sliderPreview: (@Composable (Float) -> Unit)? = null,
    val textValue: String? = null,
    val textPlaceholder: String? = null,
    val secret: Boolean = false,
    val multiline: Boolean = false,
    val confirmationMessage: String? = null,
    val destination: SettingsPageId? = null,
    val onClick: (() -> Unit)? = null,
    val onCheckedChange: ((Boolean) -> Unit)? = null,
    val annotatedDescription: AnnotatedString? = null,
    val onChoiceSelected: ((String) -> Unit)? = null,
    val onSliderChanged: ((Float) -> Unit)? = null,
    val onTextChanged: ((String) -> Unit)? = null,
    val customContent: (@Composable (onSettingValueChanged: () -> Unit) -> Unit)? = null,
)

data class SettingsGroupRegistration(
    val id: String,
    val title: String,
    val description: String? = null,
    val items: List<SettingsItemRegistration>,
    val enabled: Boolean = true,
    val footer: String? = null,
)

data class SettingsPageRegistration(
    val id: SettingsPageId,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector,
    val tone: SettingsTone,
    val groups: List<SettingsGroupRegistration>,
    val custom: Boolean = false,
    val searchTerms: List<String> = emptyList(),
)

data class SettingsRootGroupRegistration(
    val title: String,
    val pages: List<SettingsPageId>,
)

data class SettingsUiRegistry(
    val pages: List<SettingsPageRegistration>,
    val rootGroups: List<SettingsRootGroupRegistration>,
) {
    private val pagesById = pages.associateBy(SettingsPageRegistration::id)

    operator fun get(id: SettingsPageId): SettingsPageRegistration? = pagesById[id]
}

enum class SettingsTone {
    BLUE,
    CYAN,
    GREEN,
    GOLD,
    ORANGE,
    PURPLE,
    ROSE,
    NEUTRAL,
}

class SettingsPageRegistryBuilder internal constructor() {
    private val groups = mutableListOf<SettingsGroupRegistration>()

    @Composable
    fun group(
        id: String,
        title: String,
        description: String? = null,
        enabled: Boolean = true,
        footer: String? = null,
        content: @Composable SettingsGroupRegistryBuilder.() -> Unit,
    ) {
        val builder = SettingsGroupRegistryBuilder(id, title, description, enabled, footer)
        builder.content()
        groups += builder.build()
    }

    internal fun build(): List<SettingsGroupRegistration> = groups.toList()
}

class SettingsGroupRegistryBuilder internal constructor(
    private val id: String,
    private val title: String,
    private val description: String?,
    private val enabled: Boolean,
    private val footer: String?,
) {
    private val items = mutableListOf<SettingsItemRegistration>()

    fun switch(
        id: String,
        title: String,
        description: String? = null,
        checked: Boolean,
        enabled: Boolean = true,
        annotatedDescription: AnnotatedString? = null,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        items +=
            SettingsItemRegistration(
                id = id,
                title = title,
                description = description,
                control = SettingsControl.SWITCH,
                enabled = enabled,
                checked = checked,
                annotatedDescription = annotatedDescription,
                onCheckedChange = onCheckedChange,
            )
    }

    fun mainSwitch(
        id: String,
        title: String,
        checked: Boolean,
        description: String? = null,
        enabled: Boolean = true,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        items +=
            SettingsItemRegistration(
                id = id,
                title = title,
                description = description,
                control = SettingsControl.MAIN_SWITCH,
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
    }

    fun choice(
        id: String,
        title: String,
        description: String? = null,
        valueLabel: String? = null,
        choices: List<SettingsChoice>,
        selectedChoiceId: String,
        enabled: Boolean = true,
        onChoiceSelected: (String) -> Unit,
    ) {
        items +=
            SettingsItemRegistration(
                id = id,
                title = title,
                description = description,
                control = SettingsControl.CHOICE,
                enabled = enabled,
                valueLabel = valueLabel,
                choices = choices,
                selectedChoiceId = selectedChoiceId,
                onChoiceSelected = onChoiceSelected,
            )
    }

    fun slider(
        id: String,
        title: String,
        description: String? = null,
        valueLabel: String? = null,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
        enabled: Boolean = true,
        valueFormatter: (@Composable (Float) -> String)? = null,
        preview: (@Composable (Float) -> Unit)? = null,
        onValueChange: (Float) -> Unit,
    ) {
        items +=
            SettingsItemRegistration(
                id = id,
                title = title,
                description = description,
                control = SettingsControl.SLIDER,
                enabled = enabled,
                valueLabel = valueLabel,
                sliderValue = value,
                sliderRange = range,
                sliderSteps = steps,
                sliderValueFormatter = valueFormatter,
                sliderPreview = preview,
                onSliderChanged = onValueChange,
            )
    }

    fun text(
        id: String,
        title: String,
        description: String? = null,
        value: String,
        placeholder: String? = null,
        enabled: Boolean = true,
        secret: Boolean = false,
        multiline: Boolean = false,
        onValueChange: (String) -> Unit,
    ) {
        items +=
            SettingsItemRegistration(
                id = id,
                title = title,
                description = description,
                control = SettingsControl.TEXT,
                enabled = enabled,
                textValue = value,
                textPlaceholder = placeholder,
                secret = secret,
                multiline = multiline,
                onTextChanged = onValueChange,
            )
    }

    fun navigation(
        id: String,
        title: String,
        destination: SettingsPageId,
        description: String? = null,
        valueLabel: String? = null,
        enabled: Boolean = true,
    ) {
        items +=
            SettingsItemRegistration(
                id = id,
                title = title,
                description = description,
                control = SettingsControl.NAVIGATION,
                enabled = enabled,
                valueLabel = valueLabel,
                destination = destination,
            )
    }

    fun action(
        id: String,
        title: String,
        description: String? = null,
        valueLabel: String? = null,
        confirmationMessage: String? = null,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        items +=
            SettingsItemRegistration(
                id = id,
                title = title,
                description = description,
                control = SettingsControl.ACTION,
                enabled = enabled,
                valueLabel = valueLabel,
                confirmationMessage = confirmationMessage,
                onClick = onClick,
            )
    }

    fun custom(
        id: String,
        title: String,
        description: String? = null,
        enabled: Boolean = true,
        content: @Composable (onSettingValueChanged: () -> Unit) -> Unit,
    ) {
        items +=
            SettingsItemRegistration(
                id = id,
                title = title,
                description = description,
                control = SettingsControl.CUSTOM,
                enabled = enabled,
                customContent = content,
            )
    }

    fun items(registrations: List<SettingsItemRegistration>) {
        items.addAll(registrations)
    }

    internal fun build() = SettingsGroupRegistration(id, title, description, items.toList(), enabled, footer)
}

internal class SettingsUiRegistryBuilder {
    private val pages = mutableListOf<SettingsPageRegistration>()
    private val rootGroups = mutableListOf<SettingsRootGroupRegistration>()

    @Composable
    fun page(
        id: SettingsPageId,
        title: String,
        subtitle: String? = null,
        icon: ImageVector,
        tone: SettingsTone,
        content: @Composable SettingsPageRegistryBuilder.() -> Unit,
    ) {
        val builder = SettingsPageRegistryBuilder()
        builder.content()
        val groups = builder.build()
        val orderedGroups = remember(id, groups) { groups.prioritized(id) }
        pages += SettingsPageRegistration(id, title, subtitle, icon, tone, orderedGroups)
    }

    fun customPage(
        id: SettingsPageId,
        title: String,
        subtitle: String? = null,
        icon: ImageVector,
        tone: SettingsTone,
        searchTerms: List<String> = emptyList(),
    ) {
        pages += SettingsPageRegistration(id, title, subtitle, icon, tone, emptyList(), custom = true, searchTerms = searchTerms)
    }

    fun rootGroup(
        title: String,
        vararg pageIds: SettingsPageId,
    ) {
        rootGroups += SettingsRootGroupRegistration(title, pageIds.toList())
    }

    fun build(): SettingsUiRegistry {
        val rootOrder = rootGroups.flatMap { it.pages }
        val orderedPages = pages.sortedBy { rootOrder.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        return SettingsUiRegistry(orderedPages, rootGroups.toList())
    }
}
