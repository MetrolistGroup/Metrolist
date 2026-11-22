/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.discord.DiscordActivity
import com.metrolist.shared.discord.DiscordDefaults
import com.metrolist.shared.discord.DiscordPresence
import com.metrolist.shared.discord.DiscordTemplateRenderer
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.details
import com.metrolist.shared.generated.resources.discord_activity_competing
import com.metrolist.shared.generated.resources.discord_activity_name
import com.metrolist.shared.generated.resources.discord_activity_playing
import com.metrolist.shared.generated.resources.discord_activity_watching
import com.metrolist.shared.generated.resources.discord_button1_label
import com.metrolist.shared.generated.resources.discord_button1_url
import com.metrolist.shared.generated.resources.discord_button2_label
import com.metrolist.shared.generated.resources.discord_button2_url
import com.metrolist.shared.generated.resources.discord_listen_to_metrolist
import com.metrolist.shared.generated.resources.discord_logout
import com.metrolist.shared.generated.resources.discord_presence_competing_in
import com.metrolist.shared.generated.resources.discord_presence_listening_to
import com.metrolist.shared.generated.resources.discord_preview_buttons_adjusted
import com.metrolist.shared.generated.resources.discord_preview_edit_hint
import com.metrolist.shared.generated.resources.discord_state
import com.metrolist.shared.generated.resources.discord_time_left
import com.metrolist.shared.generated.resources.done
import com.metrolist.shared.generated.resources.edit
import com.metrolist.shared.generated.resources.preview
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.focusOutline
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.singleLineMarquee
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.icons.MetrolistIconsBrand
import com.metrolist.shared.utils.MetrolistClock
import com.metrolist.shared.utils.formatDuration
import org.jetbrains.compose.resources.stringResource

internal enum class DiscordEditTarget {
    ACTIVITY_NAME,
    STATE,
    DETAILS,
    BUTTON1,
    BUTTON2,
}

private const val ACTIVITY_TYPE_PLAYING = 0
private const val ACTIVITY_TYPE_LISTENING = 2
private const val ACTIVITY_TYPE_WATCHING = 3
private const val ACTIVITY_TYPE_COMPETING = 5

/** Snapshot of the current song used to render template previews next to the editor. */
internal data class DiscordTemplateContext(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val songId: String = "",
    val artistId: String = "",
    val albumId: String = "",
    val unknownArtist: String = "",
    val unknownAlbum: String = "",
)

internal fun DiscordTemplateContext(
    playback: IntegrationPlaybackState?,
    unknownArtist: String,
    unknownAlbum: String,
): DiscordTemplateContext {
    val song = playback?.song
    return DiscordTemplateContext(
        title = song?.title.orEmpty(),
        artist =
            song
                ?.artists
                ?.joinToString { it.name }
                ?.takeIf { it.isNotBlank() }
                ?: unknownArtist,
        album = song?.album?.name.orEmpty(),
        songId = song?.id.orEmpty(),
        artistId =
            song
                ?.artists
                ?.firstOrNull()
                ?.id
                .orEmpty(),
        albumId = song?.album?.id.orEmpty(),
        unknownArtist = unknownArtist,
        unknownAlbum = unknownAlbum,
    )
}

private fun DiscordTemplateContext.render(template: String): String =
    DiscordTemplateRenderer.render(template, title, artist, album, songId, artistId, albumId, unknownAlbum)

private val TEMPLATE_TOKEN_REGEX = Regex("\\{[^{}]*\\}")
private val TEMPLATE_OPEN_TOKEN_REGEX = Regex("\\{[^{}]*$")

/** Highlights {placeholder} tokens; an unclosed opening brace is tinted as an error. */
private fun highlightDiscordTemplate(
    text: String,
    tokenColor: Color,
    openColor: Color,
) = buildAnnotatedString {
    append(text)
    TEMPLATE_TOKEN_REGEX.findAll(text).forEach { match ->
        addStyle(
            SpanStyle(color = tokenColor, fontWeight = FontWeight.SemiBold),
            match.range.first,
            match.range.last + 1,
        )
    }
    TEMPLATE_OPEN_TOKEN_REGEX.findAll(text).forEach { match ->
        addStyle(SpanStyle(color = openColor), match.range.first, match.range.last + 1)
    }
}

private val NAME_TOKENS = listOf("{song.name}", "{artist.name}", "{album.name}", "{video.id}")
private val URL_TOKENS = listOf("{song.url}", "{artist.url}", "{album.url}")

private fun TextFieldValue.insert(token: String): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    return TextFieldValue(
        text = text.replaceRange(start, end, token),
        selection = TextRange(start + token.length),
    )
}

@Composable
private fun activityVerb(activityType: Int): String =
    when (activityType) {
        ACTIVITY_TYPE_PLAYING -> stringResource(Res.string.discord_activity_playing)
        ACTIVITY_TYPE_WATCHING -> stringResource(Res.string.discord_activity_watching)
        ACTIVITY_TYPE_COMPETING -> stringResource(Res.string.discord_presence_competing_in)
        else -> stringResource(Res.string.discord_presence_listening_to)
    }

@Composable
internal fun DiscordAccountRow(
    username: String,
    status: String,
    avatarUrl: String?,
    enabled: Boolean,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (avatarUrl != null) {
            AsyncImage(
                url = avatarUrl,
                contentDescription = null,
                shape = CircleShape,
                modifier = Modifier.size(44.dp),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    MetrolistIconsBrand.Discord,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                username,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onLogout, enabled = enabled, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(Res.string.discord_logout))
        }
    }
}

@Composable
internal fun DiscordPresenceCard(
    activity: DiscordActivity,
    playback: IntegrationPlaybackState,
    enabled: Boolean,
    editMode: Boolean,
    onToggleEditMode: (Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
    onEditField: (DiscordEditTarget) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val headerModifier =
                if (editMode) {
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .focusOutline(RoundedCornerShape(6.dp))
                        .clickable(enabled = enabled) { onEditField(DiscordEditTarget.ACTIVITY_NAME) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                } else {
                    Modifier
                }
            Column(modifier = Modifier.weight(1f).then(headerModifier)) {
                Text(
                    activityVerb(activity.activityType).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    activity.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.singleLineMarquee(),
                )
            }
            if (editMode) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    MetrolistIcons.Outlined.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            val editInteraction = remember { MutableInteractionSource() }
            Surface(
                onClick = { onToggleEditMode(!editMode) },
                interactionSource = editInteraction,
                shape = CircleShape,
                color =
                    if (editMode) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                contentColor =
                    if (editMode) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        MetrolistIcons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(stringResource(Res.string.edit), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                AsyncImage(
                    url = playback.artworkUrl ?: activity.largeImage,
                    contentDescription = null,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(96.dp),
                )
                Icon(
                    MetrolistIconsBrand.Discord,
                    contentDescription = null,
                    tint = Color.White,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(30.dp)
                            .clip(CircleShape)
                            .padding(5.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DiscordEditableLine(
                    text = activity.details.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    enabled = enabled,
                    editMode = editMode,
                ) { onEditField(DiscordEditTarget.DETAILS) }
                DiscordEditableLine(
                    text = activity.state,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = null,
                    enabled = enabled,
                    editMode = editMode,
                ) { onEditField(DiscordEditTarget.STATE) }
                playback.song.album?.name?.takeIf { it.isNotBlank() }?.let { album ->
                    Text(
                        album,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val now = MetrolistClock.nowMs()
                val timeText =
                    when {
                        activity.endTimestamp != null && activity.endTimestamp > now -> {
                            stringResource(
                                Res.string.discord_time_left,
                                formatDuration(activity.endTimestamp - now),
                            )
                        }

                        playback.durationMs > 0 -> {
                            "${formatDuration(playback.positionMs)} /\u00a0${formatDuration(playback.durationMs)}"
                        }

                        else -> {
                            formatDuration(playback.positionMs)
                        }
                    }
                Text(
                    timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val configuredButtons =
            listOfNotNull(
                activity.button1Label?.let { label -> activity.button1Url?.let { label to it } },
                activity.button2Label?.let { label -> activity.button2Url?.let { label to it } },
            ).filter { (label, url) -> label.isNotEmpty() && url.isNotEmpty() }
        val buttons = DiscordPresence.validatedButtons(configuredButtons)
        buttons.forEach { button ->
            val isFirstConfigured = configuredButtons.firstOrNull() == button
            Button(
                onClick = {
                    if (editMode) {
                        onEditField(if (isFirstConfigured) DiscordEditTarget.BUTTON1 else DiscordEditTarget.BUTTON2)
                    } else {
                        onOpenUrl(button.second)
                    }
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(button.first, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (editMode) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        MetrolistIcons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (buttons != configuredButtons) {
            Text(stringResource(Res.string.discord_preview_buttons_adjusted), style = MaterialTheme.typography.bodySmall)
        }
        if (editMode) {
            Text(
                stringResource(Res.string.discord_preview_edit_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiscordEditableLine(
    text: String,
    style: TextStyle,
    color: Color,
    fontWeight: FontWeight?,
    enabled: Boolean,
    editMode: Boolean,
    onClick: () -> Unit,
) {
    if (!editMode) {
        Text(
            text,
            style = style,
            fontWeight = fontWeight,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.singleLineMarquee(),
        )
        return
    }
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .focusOutline(RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = style,
            fontWeight = fontWeight,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            MetrolistIcons.Outlined.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** Settings row whose description renders the current template against the playing song. */
@Composable
internal fun DiscordTemplateEditorRow(
    title: String,
    template: String,
    context: DiscordTemplateContext,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val rendered = remember(template, context) { context.render(template) }
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(RoundedCornerShape(12.dp))
                .focusOutline(RoundedCornerShape(12.dp))
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = ripple(),
                    onClick = onClick,
                ).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Text(
                rendered,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        Icon(
            MetrolistIcons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DiscordTemplateEditorDialog(
    target: DiscordEditTarget,
    settings: Settings,
    context: DiscordTemplateContext,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val button = target == DiscordEditTarget.BUTTON1 || target == DiscordEditTarget.BUTTON2
    val initialName = settings.discordActivityName
    val initialState = settings.discordStateTemplate
    val initialDetails = settings.discordDetailsTemplate
    val initialLabel1 = settings.discordButton1Text
    val initialUrl1 = settings.discordButton1Url
    val initialLabel2 = settings.discordButton2Text
    val initialUrl2 = settings.discordButton2Url
    var nameDraft by remember(target) { mutableStateOf(TextFieldValue(initialName)) }
    var stateDraft by remember(target) { mutableStateOf(TextFieldValue(initialState)) }
    var detailsDraft by remember(target) { mutableStateOf(TextFieldValue(initialDetails)) }
    var label1Draft by remember(target) { mutableStateOf(TextFieldValue(initialLabel1)) }
    var url1Draft by remember(target) { mutableStateOf(TextFieldValue(initialUrl1)) }
    var label2Draft by remember(target) { mutableStateOf(TextFieldValue(initialLabel2)) }
    var url2Draft by remember(target) { mutableStateOf(TextFieldValue(initialUrl2)) }
    val buttonLabelTitle =
        stringResource(if (target == DiscordEditTarget.BUTTON1) Res.string.discord_button1_label else Res.string.discord_button2_label)
    val buttonUrlTitle =
        stringResource(if (target == DiscordEditTarget.BUTTON1) Res.string.discord_button1_url else Res.string.discord_button2_url)
    val fieldTitle =
        when (target) {
            DiscordEditTarget.ACTIVITY_NAME -> stringResource(Res.string.discord_activity_name)
            DiscordEditTarget.STATE -> stringResource(Res.string.discord_state)
            DiscordEditTarget.DETAILS -> stringResource(Res.string.details)
            else -> buttonLabelTitle
        }

    SettingsDialogSurface(onDismiss) {
        Column(
            modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(fieldTitle, style = MaterialTheme.typography.headlineSmall)
            if (button) {
                Text(buttonLabelTitle, style = MaterialTheme.typography.titleSmall)
                DiscordTemplateTextField(
                    value = if (target == DiscordEditTarget.BUTTON1) label1Draft else label2Draft,
                    placeholder = null,
                ) { if (target == DiscordEditTarget.BUTTON1) label1Draft = it else label2Draft = it }
                DiscordTemplateChips(NAME_TOKENS) { token ->
                    if (target == DiscordEditTarget.BUTTON1) {
                        label1Draft = label1Draft.insert(token)
                    } else {
                        label2Draft = label2Draft.insert(token)
                    }
                }
                DiscordTemplatePreview(context.render((if (target == DiscordEditTarget.BUTTON1) label1Draft else label2Draft).text))
                Text(buttonUrlTitle, style = MaterialTheme.typography.titleSmall)
                DiscordTemplateTextField(
                    value = if (target == DiscordEditTarget.BUTTON1) url1Draft else url2Draft,
                    placeholder = DiscordDefaults.BUTTON1_URL_TEMPLATE,
                    keyboardUrl = true,
                ) { if (target == DiscordEditTarget.BUTTON1) url1Draft = it else url2Draft = it }
                DiscordTemplateChips(URL_TOKENS) { token ->
                    if (target == DiscordEditTarget.BUTTON1) url1Draft = url1Draft.insert(token) else url2Draft = url2Draft.insert(token)
                }
                DiscordTemplatePreview(context.render((if (target == DiscordEditTarget.BUTTON1) url1Draft else url2Draft).text))
            } else {
                val placeholder =
                    when (target) {
                        DiscordEditTarget.ACTIVITY_NAME -> stringResource(Res.string.discord_listen_to_metrolist)
                        DiscordEditTarget.STATE -> DiscordDefaults.STATE_TEMPLATE
                        else -> DiscordDefaults.DETAILS_TEMPLATE
                    }
                DiscordTemplateTextField(
                    value =
                        when (target) {
                            DiscordEditTarget.ACTIVITY_NAME -> nameDraft
                            DiscordEditTarget.STATE -> stateDraft
                            else -> detailsDraft
                        },
                    placeholder = placeholder,
                ) {
                    when (target) {
                        DiscordEditTarget.ACTIVITY_NAME -> nameDraft = it
                        DiscordEditTarget.STATE -> stateDraft = it
                        else -> detailsDraft = it
                    }
                }
                DiscordTemplateChips(NAME_TOKENS) { token ->
                    when (target) {
                        DiscordEditTarget.ACTIVITY_NAME -> nameDraft = nameDraft.insert(token)
                        DiscordEditTarget.STATE -> stateDraft = stateDraft.insert(token)
                        else -> detailsDraft = detailsDraft.insert(token)
                    }
                }
                DiscordTemplatePreview(
                    when (target) {
                        DiscordEditTarget.ACTIVITY_NAME -> context.render(nameDraft.text)
                        DiscordEditTarget.STATE -> context.render(stateDraft.text)
                        else -> context.render(detailsDraft.text)
                    },
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.cancel))
                }
                TextButton(
                    onClick = {
                        if (target == DiscordEditTarget.ACTIVITY_NAME) settings.discordActivityName = nameDraft.text
                        if (target == DiscordEditTarget.STATE) settings.discordStateTemplate = stateDraft.text
                        if (target == DiscordEditTarget.DETAILS) settings.discordDetailsTemplate = detailsDraft.text
                        if (target == DiscordEditTarget.BUTTON1) {
                            settings.discordButton1Text = label1Draft.text
                            settings.discordButton1Url = url1Draft.text
                        }
                        if (target == DiscordEditTarget.BUTTON2) {
                            settings.discordButton2Text = label2Draft.text
                            settings.discordButton2Url = url2Draft.text
                        }
                        onSaved()
                        onDismiss()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.done)) }
            }
        }
    }
}

@Composable
private fun DiscordTemplatePreview(rendered: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(Res.string.preview),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            rendered,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DiscordTemplateTextField(
    value: TextFieldValue,
    placeholder: String?,
    keyboardUrl: Boolean = false,
    onValueChange: (TextFieldValue) -> Unit,
) {
    val tokenColor = MaterialTheme.colorScheme.primary
    val openColor = MaterialTheme.colorScheme.error
    val annotated = remember(value.text, tokenColor, openColor) { highlightDiscordTemplate(value.text, tokenColor, openColor) }
    BasicTextField(
        value = value.copy(annotatedString = annotated),
        onValueChange = { onValueChange(TextFieldValue(it.text, it.selection, it.composition)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (keyboardUrl) KeyboardType.Uri else KeyboardType.Text,
                autoCorrectEnabled = false,
            ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (value.text.isEmpty() && placeholder != null) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun DiscordTemplateChips(
    tokens: List<String>,
    onInsert: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tokens.forEach { token ->
            val insertInteraction = remember { MutableInteractionSource() }
            Surface(
                onClick = { onInsert(token) },
                interactionSource = insertInteraction,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text(
                    token,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}
