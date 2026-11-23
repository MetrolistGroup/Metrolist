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
import com.metrolist.shared.discord.resolveDiscordButtonLabel
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album
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
import com.metrolist.shared.generated.resources.discord_example_album_name
import com.metrolist.shared.generated.resources.discord_example_artist_name
import com.metrolist.shared.generated.resources.discord_example_song_title
import com.metrolist.shared.generated.resources.discord_listen_to_metrolist
import com.metrolist.shared.generated.resources.discord_logout
import com.metrolist.shared.generated.resources.discord_position_duration
import com.metrolist.shared.generated.resources.discord_presence_competing_in
import com.metrolist.shared.generated.resources.discord_presence_listening_to
import com.metrolist.shared.generated.resources.discord_preview_buttons_adjusted
import com.metrolist.shared.generated.resources.discord_preview_edit_hint
import com.metrolist.shared.generated.resources.discord_state
import com.metrolist.shared.generated.resources.discord_time_left
import com.metrolist.shared.generated.resources.discord_token_album_name
import com.metrolist.shared.generated.resources.discord_token_album_url
import com.metrolist.shared.generated.resources.discord_token_artist_name
import com.metrolist.shared.generated.resources.discord_token_artist_url
import com.metrolist.shared.generated.resources.discord_token_song_name
import com.metrolist.shared.generated.resources.discord_token_song_url
import com.metrolist.shared.generated.resources.discord_token_video_id
import com.metrolist.shared.generated.resources.done
import com.metrolist.shared.generated.resources.edit
import com.metrolist.shared.generated.resources.preview
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
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
    ALBUM,
    BUTTON1,
    BUTTON2,
}

private const val ACTIVITY_TYPE_PLAYING = 0
private const val ACTIVITY_TYPE_LISTENING = 2
private const val ACTIVITY_TYPE_WATCHING = 3
private const val ACTIVITY_TYPE_COMPETING = 5

@Composable
internal fun discordExamplePlayback(): IntegrationPlaybackState {
    val songId = "dQw4w9WgXcQ"
    val durationMs = 213_000L
    return IntegrationPlaybackState(
        song =
            SongItem(
                id = songId,
                title = stringResource(Res.string.discord_example_song_title),
                thumbnail = "https://i.ytimg.com/vi/$songId/mqdefault.jpg",
                artists = listOf(ArtistTiny(id = null, name = stringResource(Res.string.discord_example_artist_name))),
                album = AlbumTiny(id = null, name = stringResource(Res.string.discord_example_album_name)),
                duration = durationMs / 1000L,
            ),
        durationMs = durationMs,
        isPlaying = true,
    )
}

/** Snapshot of the current song used to render template previews next to the editor. */
internal data class DiscordTemplateContext(
    val title: String = "",
    val artist: String = "",
    // Kept nullable so {album.name} renders the unknown-album text like the builder does.
    val album: String? = null,
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
        album = song?.album?.name,
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

/** Renders a template the way the presence builder resolves it, including its defaults. */
internal fun DiscordTemplateContext.renderDiscordTemplate(
    target: DiscordEditTarget,
    template: String,
    button1Default: String,
    button2Default: String,
): String =
    when (target) {
        // The builder sends the raw song title for a cleared activity name without rendering it.
        DiscordEditTarget.ACTIVITY_NAME -> {
            if (template.isEmpty()) title else render(template)
        }

        DiscordEditTarget.STATE -> {
            render(template.ifEmpty { DiscordDefaults.STATE_TEMPLATE })
        }

        DiscordEditTarget.DETAILS -> {
            render(template.ifEmpty { DiscordDefaults.DETAILS_TEMPLATE })
        }

        DiscordEditTarget.ALBUM -> {
            render(template.ifEmpty { DiscordDefaults.ALBUM_TEMPLATE })
        }

        DiscordEditTarget.BUTTON1 -> {
            render(resolveDiscordButtonLabel(template, DiscordDefaults.BUTTON1_LABEL, button1Default))
        }

        DiscordEditTarget.BUTTON2 -> {
            render(resolveDiscordButtonLabel(template, DiscordDefaults.BUTTON2_LABEL, button2Default))
        }
    }

/** Renders a button URL the way the presence builder resolves it. */
internal fun DiscordTemplateContext.renderDiscordButtonUrl(
    target: DiscordEditTarget,
    url: String,
): String =
    render(
        url.ifEmpty {
            if (target == DiscordEditTarget.BUTTON1) DiscordDefaults.BUTTON1_URL_TEMPLATE else DiscordDefaults.BUTTON2_URL
        },
    )

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

@Composable
private fun nameTokens(): List<String> =
    listOf(
        stringResource(Res.string.discord_token_song_name),
        stringResource(Res.string.discord_token_artist_name),
        stringResource(Res.string.discord_token_album_name),
        stringResource(Res.string.discord_token_video_id),
    )

@Composable
private fun urlTokens(): List<String> =
    listOf(
        stringResource(Res.string.discord_token_song_url),
        stringResource(Res.string.discord_token_artist_url),
        stringResource(Res.string.discord_token_album_url),
    )

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
            Row(modifier = Modifier.weight(1f).then(headerModifier), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
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
            }
            Spacer(Modifier.width(8.dp))
            val editInteraction = remember { MutableInteractionSource() }
            Surface(
                onClick = { onToggleEditMode(!editMode) },
                enabled = enabled,
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
                if (editMode || !activity.largeText.isNullOrBlank()) {
                    DiscordEditableLine(
                        text = activity.largeText.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = null,
                        enabled = enabled,
                        editMode = editMode,
                    ) { onEditField(DiscordEditTarget.ALBUM) }
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
                            stringResource(
                                Res.string.discord_position_duration,
                                formatDuration(playback.positionMs),
                                formatDuration(playback.durationMs),
                            )
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
        // Validate each configured button separately so its editor slot survives label truncation.
        val configuredButtons =
            listOfNotNull(
                activity.button1Label?.let { label -> activity.button1Url?.let { url -> DiscordEditTarget.BUTTON1 to (label to url) } },
                activity.button2Label?.let { label -> activity.button2Url?.let { url -> DiscordEditTarget.BUTTON2 to (label to url) } },
            ).filter { (_, pair) -> pair.first.isNotEmpty() && pair.second.isNotEmpty() }
        val buttons =
            configuredButtons.mapNotNull { (slot, pair) ->
                DiscordPresence.validatedButtons(listOf(pair)).firstOrNull()?.let { slot to it }
            }
        buttons.forEach { (slot, button) ->
            Button(
                onClick = {
                    if (editMode) {
                        onEditField(slot)
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
        if (buttons.map { it.second } != configuredButtons.map { it.second }) {
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

/** Settings row whose description shows what the template currently renders to. */
@Composable
internal fun DiscordTemplateEditorRow(
    title: String,
    rendered: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
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
    button1Default: String,
    button2Default: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val button = target == DiscordEditTarget.BUTTON1 || target == DiscordEditTarget.BUTTON2
    val initialName = settings.discordActivityName
    val initialState = settings.discordStateTemplate
    val initialDetails = settings.discordDetailsTemplate
    val initialAlbum = settings.discordAlbumTemplate
    val initialLabel1 = settings.discordButton1Text
    val initialUrl1 = settings.discordButton1Url
    val initialLabel2 = settings.discordButton2Text
    val initialUrl2 = settings.discordButton2Url
    var nameDraft by remember(target) { mutableStateOf(TextFieldValue(initialName)) }
    var stateDraft by remember(target) { mutableStateOf(TextFieldValue(initialState)) }
    var detailsDraft by remember(target) { mutableStateOf(TextFieldValue(initialDetails)) }
    var albumDraft by remember(target) { mutableStateOf(TextFieldValue(initialAlbum)) }
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
            DiscordEditTarget.ALBUM -> stringResource(Res.string.album)
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
                DiscordTemplateChips(nameTokens()) { token ->
                    if (target == DiscordEditTarget.BUTTON1) {
                        label1Draft = label1Draft.insert(token)
                    } else {
                        label2Draft = label2Draft.insert(token)
                    }
                }
                DiscordTemplatePreview(
                    context.renderDiscordTemplate(
                        target,
                        (if (target == DiscordEditTarget.BUTTON1) label1Draft else label2Draft).text,
                        button1Default,
                        button2Default,
                    ),
                )
                Text(buttonUrlTitle, style = MaterialTheme.typography.titleSmall)
                DiscordTemplateTextField(
                    value = if (target == DiscordEditTarget.BUTTON1) url1Draft else url2Draft,
                    placeholder = DiscordDefaults.BUTTON1_URL_TEMPLATE,
                    keyboardUrl = true,
                ) { if (target == DiscordEditTarget.BUTTON1) url1Draft = it else url2Draft = it }
                DiscordTemplateChips(urlTokens()) { token ->
                    if (target == DiscordEditTarget.BUTTON1) url1Draft = url1Draft.insert(token) else url2Draft = url2Draft.insert(token)
                }
                DiscordTemplatePreview(
                    context.renderDiscordButtonUrl(
                        target,
                        (
                            if (target ==
                                DiscordEditTarget.BUTTON1
                            ) {
                                url1Draft
                            } else {
                                url2Draft
                            }
                        ).text,
                    ),
                )
            } else {
                val placeholder =
                    when (target) {
                        DiscordEditTarget.ACTIVITY_NAME -> stringResource(Res.string.discord_listen_to_metrolist)
                        DiscordEditTarget.STATE -> DiscordDefaults.STATE_TEMPLATE
                        DiscordEditTarget.ALBUM -> stringResource(Res.string.discord_token_album_name)
                        else -> DiscordDefaults.DETAILS_TEMPLATE
                    }
                DiscordTemplateTextField(
                    value =
                        when (target) {
                            DiscordEditTarget.ACTIVITY_NAME -> nameDraft
                            DiscordEditTarget.STATE -> stateDraft
                            DiscordEditTarget.ALBUM -> albumDraft
                            else -> detailsDraft
                        },
                    placeholder = placeholder,
                ) {
                    when (target) {
                        DiscordEditTarget.ACTIVITY_NAME -> nameDraft = it
                        DiscordEditTarget.STATE -> stateDraft = it
                        DiscordEditTarget.ALBUM -> albumDraft = it
                        else -> detailsDraft = it
                    }
                }
                DiscordTemplateChips(nameTokens()) { token ->
                    when (target) {
                        DiscordEditTarget.ACTIVITY_NAME -> nameDraft = nameDraft.insert(token)
                        DiscordEditTarget.STATE -> stateDraft = stateDraft.insert(token)
                        DiscordEditTarget.ALBUM -> albumDraft = albumDraft.insert(token)
                        else -> detailsDraft = detailsDraft.insert(token)
                    }
                }
                DiscordTemplatePreview(
                    context.renderDiscordTemplate(
                        target,
                        when (target) {
                            DiscordEditTarget.ACTIVITY_NAME -> nameDraft.text
                            DiscordEditTarget.STATE -> stateDraft.text
                            DiscordEditTarget.ALBUM -> albumDraft.text
                            else -> detailsDraft.text
                        },
                        button1Default,
                        button2Default,
                    ),
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
                        if (target == DiscordEditTarget.ALBUM) settings.discordAlbumTemplate = albumDraft.text
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
