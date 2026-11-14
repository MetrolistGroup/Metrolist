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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.metrolist.shared.api.artwork.CanvasContentSource
import com.metrolist.shared.api.artwork.isEnabled
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.SliderStyle
import com.metrolist.shared.settings.ThemeMode
import com.metrolist.shared.ui.components.ExpressiveSwitch
import com.metrolist.shared.ui.components.ReorderMenu
import com.metrolist.shared.ui.components.focusOutline
import com.metrolist.shared.ui.components.singleLineMarquee
import com.metrolist.shared.ui.components.sliders.PlaybackProgressSlider
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.parseThemeSeedColor
import com.metrolist.shared.ui.theme.themeColorScheme
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableColumn
import kotlin.math.roundToInt

@Composable
fun settingsPageSubtitle(
    summary: String,
    status: String?,
): String = if (status.isNullOrBlank()) summary else stringResource(Res.string.settings_page_status_summary, summary, status)

@Composable
internal fun CanvasContentOrderSetting(
    platform: SettingsPlatform,
    canvasEnabled: Boolean,
    animatedCoversEnabled: Boolean,
    onSpotifySignIn: (() -> Unit)?,
) {
    val settings = Settings.shared
    val contentOrder = CanvasContentSource.parseOrder(settings.canvasContentOrder)
    val availableSources =
        contentOrder.filter {
            when (platform) {
                SettingsPlatform.ANDROID -> true
                SettingsPlatform.DESKTOP -> it != CanvasContentSource.YOUTUBE_MUSIC_VIDEO
                SettingsPlatform.IOS -> it == CanvasContentSource.APPLE_MUSIC
            }
        }
    val enabledSources = CanvasContentSource.parseEnabled(settings.canvasEnabledSources)

    fun move(
        source: CanvasContentSource,
        other: CanvasContentSource,
    ) {
        val updated = contentOrder.toMutableList()
        val sourceIndex = updated.indexOf(source)
        val otherIndex = updated.indexOf(other)
        updated[sourceIndex] = other
        updated[otherIndex] = source
        settings.canvasContentOrder = CanvasContentSource.serialize(updated)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        availableSources.forEachIndexed { index, source ->
            val label = canvasContentSourceLabel(source)
            val sourceEnabled =
                source.isEnabled(canvasEnabled, animatedCoversEnabled) &&
                    (source != CanvasContentSource.YOUTUBE_MUSIC_VIDEO || settings.musicVideoPlaybackEnabled)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (sourceEnabled) 1f else 0.38f),
                    )
                    if (source == CanvasContentSource.SPOTIFY_CANVAS && settings.spotifySession.isBlank()) {
                        if (onSpotifySignIn != null) {
                            TextButton(onClick = onSpotifySignIn, enabled = sourceEnabled, shapes = ButtonDefaults.shapes()) {
                                Text(stringResource(Res.string.spotify_sign_in))
                            }
                        } else {
                            Text(stringResource(Res.string.spotify_session_sign_in), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                IconButton(
                    onClick = {
                        move(source, availableSources[index - 1])
                    },
                    enabled = sourceEnabled && index > 0,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(MetrolistIcons.Outlined.KeyboardArrowUp, stringResource(Res.string.move_up))
                }
                IconButton(
                    onClick = {
                        move(source, availableSources[index + 1])
                    },
                    enabled = sourceEnabled && index < availableSources.lastIndex,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(MetrolistIcons.Outlined.KeyboardArrowDown, stringResource(Res.string.move_down))
                }
                ExpressiveSwitch(
                    checked = source in enabledSources,
                    enabled = sourceEnabled,
                    modifier = Modifier.semantics { contentDescription = label },
                    onCheckedChange = { enabled ->
                        val updated = if (enabled) enabledSources + source else enabledSources - source
                        settings.canvasEnabledSources =
                            if (updated.isEmpty()) "NONE" else contentOrder.filter { it in updated }.joinToString(",") { it.name }
                    },
                )
            }
            if (index < availableSources.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun canvasContentSourceLabel(source: CanvasContentSource): String =
    when (source) {
        CanvasContentSource.SPOTIFY_CANVAS -> stringResource(Res.string.canvas_source_spotify)
        CanvasContentSource.YOUTUBE_MUSIC_VIDEO -> stringResource(Res.string.canvas_source_youtube_music_video)
        CanvasContentSource.APPLE_MUSIC -> stringResource(Res.string.canvas_source_apple_music)
    }

@Composable
internal fun LyricsProviderOrderSetting() {
    val settings = Settings.shared
    val haptics = rememberMetrolistHaptics()
    val providerOrder = LyricsProvider.parseUserOrderFromStoredCsv(settings.lyricsProviderOrder)
    val enabledProviders = LyricsProvider.parseEnabledFromStoredCsv(settings.lyricsEnabledProviders)

    fun move(
        from: Int,
        to: Int,
        feedback: Boolean = true,
    ) {
        if (from == to) return
        if (feedback) haptics.perform(HapticEvent.DRAG_SNAP)
        settings.lyricsProviderOrder = providerOrder.toMutableList().apply { add(to, removeAt(from)) }.joinToString(",") { it.name }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(Res.string.lyrics_provider_user), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(Res.string.lyrics_provider_user_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ReorderableColumn(
        list = providerOrder,
        onSettle = { from, to -> move(from, to, feedback = false) },
        onMove = { haptics.perform(HapticEvent.DRAG_SNAP) },
        modifier = Modifier.fillMaxWidth(),
    ) { index, provider, _ ->
        key(provider) {
            val label = lyricsProviderLabel(provider)
            ReorderableItem {
                Column(Modifier.background(MaterialTheme.colorScheme.settingsRowContainerColor())) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReorderMenu(
                            onMoveUp = if (index > 0) ({ move(index, index - 1) }) else null,
                            onMoveDown = if (index < providerOrder.lastIndex) ({ move(index, index + 1) }) else null,
                            onMoveToTop = if (index > 0) ({ move(index, 0) }) else null,
                            onMoveToBottom = if (index < providerOrder.lastIndex) ({ move(index, providerOrder.lastIndex) }) else null,
                            dragHandleModifier = Modifier.draggableHandle(onDragStarted = { haptics.perform(HapticEvent.DRAG_START) }),
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                lyricsProviderDescription(provider),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ExpressiveSwitch(
                            checked = provider in enabledProviders,
                            modifier = Modifier.semantics { contentDescription = label },
                            onCheckedChange = { enabled ->
                                val updated = if (enabled) enabledProviders + provider else enabledProviders - provider
                                settings.lyricsEnabledProviders =
                                    if (updated.isEmpty()) {
                                        "NONE"
                                    } else {
                                        providerOrder.filter { it in updated }.joinToString(",") { it.name }
                                    }
                            },
                        )
                    }
                    if (index < providerOrder.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun lyricsProviderLabel(provider: LyricsProvider): String =
    when (provider) {
        LyricsProvider.LYPRIO -> stringResource(Res.string.brand_lyprio)
        LyricsProvider.BETTERLYRICS -> stringResource(Res.string.brand_better_lyrics)
        LyricsProvider.LRCRED -> stringResource(Res.string.brand_lrcred)
        LyricsProvider.UNISON -> stringResource(Res.string.brand_unison)
        LyricsProvider.LRCLIB -> stringResource(Res.string.brand_lrclib)
        LyricsProvider.KUGOU -> stringResource(Res.string.brand_kugou)
        LyricsProvider.PAXSENIX -> stringResource(Res.string.brand_paxsenix)
        else -> provider.name
    }

@Composable
private fun lyricsProviderDescription(provider: LyricsProvider): String =
    stringResource(
        when (provider) {
            LyricsProvider.LYPRIO -> Res.string.lyrics_provider_lyrics_plus_description
            LyricsProvider.BETTERLYRICS -> Res.string.lyrics_provider_better_lyrics_description
            LyricsProvider.LRCRED -> Res.string.lyrics_provider_lrcred_description
            LyricsProvider.UNISON -> Res.string.lyrics_provider_unison_description
            LyricsProvider.LRCLIB -> Res.string.lyrics_provider_lrclib_description
            LyricsProvider.KUGOU -> Res.string.lyrics_provider_kugou_description
            LyricsProvider.PAXSENIX -> Res.string.lyrics_provider_paxsenix_description
            else -> Res.string.lyrics_provider_unknown_description
        },
    )

@Composable
internal fun lyricsAiProviderLabel(provider: String): String =
    when (provider) {
        "OpenRouter" -> stringResource(Res.string.brand_openrouter)
        "OpenAI" -> stringResource(Res.string.brand_openai)
        "OpenAI-compatible" -> stringResource(Res.string.ai_provider_compatible, stringResource(Res.string.brand_openai))
        "Anthropic" -> stringResource(Res.string.brand_anthropic)
        "Anthropic-compatible" -> stringResource(Res.string.ai_provider_compatible, stringResource(Res.string.brand_anthropic))
        "Gemini" -> stringResource(Res.string.brand_gemini)
        "Gemini-compatible" -> stringResource(Res.string.ai_provider_compatible, stringResource(Res.string.brand_gemini))
        "Perplexity" -> stringResource(Res.string.brand_perplexity)
        "Mistral" -> stringResource(Res.string.brand_mistral)
        "XAi" -> stringResource(Res.string.brand_xai)
        "OpenCodeZen" -> stringResource(Res.string.brand_opencode_zen)
        "OpenCodeGo" -> stringResource(Res.string.brand_opencode_go)
        "Groq" -> stringResource(Res.string.brand_groq)
        "DeepSeek" -> stringResource(Res.string.brand_deepseek)
        "Inception" -> stringResource(Res.string.brand_inception)
        "DeepL" -> stringResource(Res.string.brand_deepl)
        "DeepL (keyless)" -> stringResource(Res.string.ai_provider_keyless, stringResource(Res.string.brand_deepl))
        "Google Translate" -> stringResource(Res.string.brand_google_translate)
        "Custom" -> stringResource(Res.string.ai_provider_compatible, stringResource(Res.string.brand_openai))
        else -> provider
    }

internal fun parseRomanizationLanguages(value: String): Map<String, Boolean> =
    value
        .split(',')
        .mapNotNull { part ->
            val pieces = part.split(':', limit = 2)
            val language = pieces.firstOrNull()?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            language to (pieces.getOrNull(1)?.toBooleanStrictOrNull() ?: true)
        }.toMap(LinkedHashMap())

@Composable
internal fun romanizationLanguageLabel(language: String): String =
    when (language) {
        "Japanese" -> stringResource(Res.string.language_japanese)
        "Korean" -> stringResource(Res.string.language_korean)
        "Chinese" -> stringResource(Res.string.language_chinese)
        "Arabic" -> stringResource(Res.string.language_arabic)
        "Hindi" -> stringResource(Res.string.language_hindi)
        "Punjabi" -> stringResource(Res.string.language_punjabi)
        "Thai" -> stringResource(Res.string.language_thai)
        "Russian" -> stringResource(Res.string.language_russian)
        "Ukrainian" -> stringResource(Res.string.language_ukrainian)
        "Serbian" -> stringResource(Res.string.language_serbian)
        "Bulgarian" -> stringResource(Res.string.language_bulgarian)
        "Belarusian" -> stringResource(Res.string.language_belarusian)
        "Kyrgyz" -> stringResource(Res.string.language_kyrgyz)
        "Macedonian" -> stringResource(Res.string.language_macedonian)
        else -> language
    }

@Composable
internal fun ThemeModePreview(
    mode: ThemeMode,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val light = Color(0xFFF4F1EC)
    val dark = Color(0xFF292725)
    Row(modifier.width(72.dp).height(32.dp).clip(shape)) {
        when (mode) {
            ThemeMode.AUTO -> {
                Box(Modifier.weight(1f).fillMaxHeight().background(light))
                Box(Modifier.weight(1f).fillMaxHeight().background(dark))
            }

            ThemeMode.LIGHT -> {
                Box(Modifier.fillMaxSize().background(light))
            }

            ThemeMode.DARK -> {
                Box(Modifier.fillMaxSize().background(dark))
            }

            ThemeMode.PURE_BLACK -> {
                Box(Modifier.fillMaxSize().background(Color.Black))
            }
        }
    }
}

@Composable
internal fun sliderStyleChoices(): List<SettingsChoice> =
    listOf(
        SettingsChoice(
            SliderStyle.DEFAULT.name,
            stringResource(Res.string.slider_standard),
            preview = { SliderStylePreview(SliderStyle.DEFAULT) },
        ),
        SettingsChoice(
            SliderStyle.WAVY.name,
            stringResource(Res.string.wavy),
            preview = { SliderStylePreview(SliderStyle.WAVY) },
        ),
        SettingsChoice(
            SliderStyle.SLIM.name,
            stringResource(Res.string.slim),
            preview = { SliderStylePreview(SliderStyle.SLIM) },
        ),
        SettingsChoice(
            SliderStyle.SQUIGGLY.name,
            stringResource(Res.string.squiggly),
            preview = { SliderStylePreview(SliderStyle.SQUIGGLY) },
        ),
    )

@Composable
internal fun SliderStylePreview(
    style: SliderStyle,
    modifier: Modifier = Modifier.width(72.dp),
    trackThicknessDp: Float = Settings.shared.sliderTrackThicknessDp,
) {
    val defaultColors = SliderDefaults.colors()
    val colors =
        SliderDefaults.colors(
            disabledThumbColor = defaultColors.thumbColor,
            disabledActiveTrackColor = defaultColors.activeTrackColor,
            disabledActiveTickColor = defaultColors.activeTickColor,
            disabledInactiveTrackColor = defaultColors.inactiveTrackColor,
            disabledInactiveTickColor = defaultColors.inactiveTickColor,
        )
    PlaybackProgressSlider(
        value = 0.6f,
        onValueChange = {},
        onValueChangeFinished = {},
        modifier = modifier.clearAndSetSemantics {},
        style = style,
        colors = colors,
        isPlaying = true,
        trackThicknessDp = trackThicknessDp,
        enabled = false,
    )
}

@Composable
internal fun ThemeSeedColorSetting(
    title: String,
    description: String,
    selectedValue: Long,
    onColorSelected: (Long) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val selectedColor = parseThemeSeedColor(selectedValue)
    var hex by remember(selectedValue) { mutableStateOf(selectedColor.toHexRgb()) }
    val presets =
        remember {
            listOf(
                Color(0xFF6750A4) to Res.string.theme_color_purple,
                Color(0xFF006A6A) to Res.string.theme_color_teal,
                Color(0xFF386A20) to Res.string.theme_color_green,
                Color(0xFF8C4A60) to Res.string.theme_color_rose,
                Color(0xFF9C4146) to Res.string.theme_color_red,
                Color(0xFF765A00) to Res.string.theme_color_gold,
            )
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        val paletteStyle = Settings.shared.themePaletteStyle
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            presets.forEach { (color, labelResource) ->
                val label = stringResource(labelResource)
                val isSelected = color.toHexRgb() == selectedColor.toHexRgb()
                val scheme = remember(color, isDark, paletteStyle) { themeColorScheme(color, isDark, paletteStyle) }
                Surface(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = label
                                selected = isSelected
                            }.border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            ),
                    shape = CircleShape,
                    color = scheme.surfaceContainerHighest,
                    onClick = {
                        hex = color.toHexRgb()
                        onColorSelected(color.value.toLong())
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    },
                ) {
                    Canvas(Modifier.fillMaxSize().padding(if (isSelected) 6.dp else 4.dp)) {
                        drawArc(scheme.primary, 180f, 180f, useCenter = true)
                        drawArc(scheme.secondary, 90f, 90f, useCenter = true)
                        drawArc(scheme.tertiary, 0f, 90f, useCenter = true)
                    }
                }
            }
        }
        val isFullValidHex = hex.length == 6 && hex.toLongOrNull(16) != null

        OutlinedTextField(
            value = hex,
            onValueChange = { input ->
                val normalized =
                    input
                        .filter { character -> character in '0'..'9' || character.lowercaseChar() in 'a'..'f' }
                        .take(6)
                        .uppercase()
                hex = normalized
                normalized.toLongOrNull(16)?.takeIf { normalized.length == 6 }?.let { rgb ->
                    val color = Color(color = 0xFF000000L or rgb)
                    onColorSelected(color.value.toLong())
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.settings_color_hex)) },
            prefix = { Text("#") },
            trailingIcon = {
                if (isFullValidHex) {
                    IconButton(
                        onClick = {
                            hex.toLongOrNull(16)?.let { rgb ->
                                val color = Color(color = 0xFF000000L or rgb)
                                onColorSelected(color.value.toLong())
                            }
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Filled.Check,
                            contentDescription = stringResource(Res.string.done),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        hex.toLongOrNull(16)?.takeIf { hex.length == 6 }?.let { rgb ->
                            val color = Color(color = 0xFF000000L or rgb)
                            onColorSelected(color.value.toLong())
                        }
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    },
                ),
        )
    }
}

private fun Color.toHexRgb(): String {
    if (red.isNaN() || green.isNaN() || blue.isNaN()) return "000000"
    val redComponent = (red.coerceIn(0f, 1f) * 255).roundToInt().toString(16).padStart(2, '0')
    val greenComponent = (green.coerceIn(0f, 1f) * 255).roundToInt().toString(16).padStart(2, '0')
    val blueComponent = (blue.coerceIn(0f, 1f) * 255).roundToInt().toString(16).padStart(2, '0')
    return "$redComponent$greenComponent$blueComponent".uppercase()
}

@Composable
fun SettingsMainSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = rememberMetrolistHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val labelHeight =
        with(density) {
            MaterialTheme.typography.titleLarge.lineHeight
                .toDp()
        }
    val height = if (density.fontScale > 1f) (labelHeight + 32.dp).coerceAtLeast(64.dp) else 64.dp
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .clip(CircleShape)
                .background(if (checked) colors.primaryContainer else colors.settingsRowContainerColor())
                .focusOutline(CircleShape)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    interactionSource = interactionSource,
                    indication = ripple(),
                    onValueChange = {
                        haptics.toggle(it)
                        onCheckedChange(it)
                    },
                ).padding(start = 32.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(23.dp),
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f).singleLineMarquee(),
            maxLines = 1,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = (if (checked) colors.onPrimaryContainer else colors.onSurface).copy(alpha = if (enabled) 1f else 0.38f),
        )
        ExpressiveSwitch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.height(32.dp).clearAndSetSemantics {},
        )
    }
}
