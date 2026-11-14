/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.api.lastfm.LastFmLoginFailure
import com.metrolist.shared.api.lastfm.lastFmLoginFailure
import com.metrolist.shared.api.listenbrainz.ListenBrainz
import com.metrolist.shared.discord.DiscordActivity
import com.metrolist.shared.discord.DiscordActivityBuilder
import com.metrolist.shared.discord.DiscordAuthSession
import com.metrolist.shared.discord.DiscordDefaults
import com.metrolist.shared.discord.DiscordErrorInfo
import com.metrolist.shared.discord.DiscordPlaybackUpdate
import com.metrolist.shared.discord.DiscordPresence
import com.metrolist.shared.discord.DiscordRpcManager
import com.metrolist.shared.discord.DiscordUser
import com.metrolist.shared.discord.discordPlaybackTimestamps
import com.metrolist.shared.discord.discordSettingsVisible
import com.metrolist.shared.discord.resolveDiscordButtonLabel
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.scrobble.ScrobbleProvider
import com.metrolist.shared.scrobble.ScrobbleSessions
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.icons.MetrolistIconsBrand
import com.metrolist.shared.ui.mobile.MobileListenTogetherScreen
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.playback.wholeSecondPosition
import com.metrolist.shared.utils.MetrolistClock
import com.metrolist.shared.utils.formatDuration
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

data class SharedIntegrationsHost(
    val platform: SettingsPlatform,
    val onClose: () -> Unit,
    val bottomContentPadding: Dp = 0.dp,
    val initialPage: SettingsPageId? = null,
    val discordLoginInProgress: Boolean = false,
    val playback: IntegrationPlaybackState? = null,
    val playbackPosition: StateFlow<Long>? = null,
    val currentPositionMs: () -> Long = { playback?.positionMs ?: 0L },
    val onStartDiscordLogin: (DiscordAuthSession) -> Unit,
    val onDiscordSettingsChanged: () -> Unit = {},
    val deviceConnect: DeviceConnectSettingsHost? = null,
    val listenTogetherManager: ListenTogetherManager? = null,
    val onOpenListenTogether: (() -> Unit)? = null,
    val onOpenUrl: (String) -> Unit = {},
    val onCopyText: (String, String) -> Unit = { _, _ -> },
    val onShareText: ((String) -> Unit)? = null,
    val onMessage: (String) -> Unit = {},
)

data class IntegrationPlaybackState(
    val song: SongItem,
    val artworkUrl: String? = null,
    val artistArtworkUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1f,
)

private enum class ScrobbleLoginProvider {
    LAST_FM,
    LISTEN_BRAINZ,
}

@Composable
fun SharedIntegrationsScreen(
    host: SharedIntegrationsHost,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(host.platform, Settings.shared.enableDiscordIntegration) {
        discordSettingsVisible = Settings.shared.enableDiscordIntegration
        onDispose { discordSettingsVisible = false }
    }
    val scope = rememberCoroutineScope()
    val accessToken by DiscordRpcManager.accessTokenFlow.collectAsState()
    val connectionStatus by DiscordRpcManager.connectionStatus.collectAsState()
    val currentUser by DiscordRpcManager.currentUser.collectAsState()
    val discordError by DiscordRpcManager.lastError.collectAsState()
    var accountRevision by remember { mutableIntStateOf(0) }
    var loginProvider by remember { mutableStateOf<ScrobbleLoginProvider?>(null) }

    LaunchedEffect(currentUser, accessToken) {
        if (accessToken.isNullOrBlank()) return@LaunchedEffect
        currentUser?.let { user ->
            Settings.shared.discordUsername = user.username
            Settings.shared.discordName = user.name
            Settings.shared.discordAvatar = user.avatar.orEmpty()
        }
    }

    var showListenTogether by remember { mutableStateOf(host.initialPage == SettingsPageId.LISTEN_TOGETHER) }
    if (showListenTogether && host.listenTogetherManager != null) {
        NavigationBackHandler(onBack = { showListenTogether = false })
        MobileListenTogetherScreen(
            manager = host.listenTogetherManager,
            onBack = { showListenTogether = false },
            onCopyText = host.onCopyText,
            onShareText = host.onShareText,
            onMessage = host.onMessage,
            bottomInset = host.bottomContentPadding,
            initialSettings = true,
        )
        return
    }

    val settingsHost =
        SharedSettingsHost(
            platform = host.platform,
            onClose = host.onClose,
            title = stringResource(Res.string.integrations),
            bottomContentPadding = host.bottomContentPadding,
            initialPage = host.initialPage?.takeUnless { it == SettingsPageId.LISTEN_TOGETHER },
            singlePane = true,
            externalPages = setOf(SettingsPageId.LISTEN_TOGETHER),
            onOpenPage = { page ->
                when (page) {
                    SettingsPageId.LISTEN_TOGETHER -> host.onOpenListenTogether?.invoke() ?: run { showListenTogether = true }
                    else -> Unit
                }
            },
        )

    SharedSettingsRegistryScreen(
        host = settingsHost,
        modifier = modifier,
    ) { settingsRevision ->
        buildIntegrationsRegistry(
            host = host,
            revision = settingsRevision + accountRevision,
            accessToken = accessToken,
            connectionStatus = connectionStatus,
            currentUser = currentUser,
            discordError = discordError,
            onOpenLogin = { loginProvider = it },
            onAccountsChanged = { accountRevision++ },
            onDisconnectScrobble = { provider ->
                scope.launch {
                    ScrobbleSessions.disconnect(provider)
                    accountRevision++
                }
            },
        )
    }

    PopupContent(loginProvider) { provider ->
        ScrobbleLoginDialog(
            provider = provider,
            onOpenUrl = host.onOpenUrl,
            onDismiss = { loginProvider = null },
            onConnected = {
                loginProvider = null
                accountRevision++
            },
        )
    }
}

@Composable
private fun buildIntegrationsRegistry(
    host: SharedIntegrationsHost,
    @Suppress("UNUSED_PARAMETER") revision: Int,
    accessToken: String?,
    connectionStatus: DiscordRpcManager.Status,
    currentUser: DiscordUser?,
    discordError: DiscordErrorInfo?,
    onOpenLogin: (ScrobbleLoginProvider) -> Unit,
    onAccountsChanged: () -> Unit,
    onDisconnectScrobble: (ScrobbleProvider) -> Unit,
): SettingsUiRegistry {
    val settings = Settings.shared
    val builder = SettingsUiRegistryBuilder()
    val discordUsername = currentUser?.username?.ifBlank { null } ?: settings.discordUsername
    val discordLoggedIn = !accessToken.isNullOrBlank()
    val discordSettingsEnabled = settings.enableDiscordIntegration && discordLoggedIn
    val showAllDiscordSettings = settings.enableDiscordIntegration && !discordLoggedIn
    val lastFmAccount = ScrobbleSessions.current(ScrobbleProvider.LAST_FM)
    val listenBrainzAccount = ScrobbleSessions.current(ScrobbleProvider.LISTEN_BRAINZ)
    val discordStatus =
        when {
            host.discordLoginInProgress || connectionStatus == DiscordRpcManager.Status.Authorizing -> {
                stringResource(Res.string.discord_status_authorizing)
            }

            connectionStatus == DiscordRpcManager.Status.Connected -> {
                stringResource(Res.string.discord_status_authorized)
            }

            discordLoggedIn -> {
                stringResource(Res.string.discord_disconnected)
            }

            else -> {
                stringResource(Res.string.discord_status_not_initialized)
            }
        }
    val activityTypes =
        listOf(
            SettingsChoice("0", stringResource(Res.string.discord_activity_playing)),
            SettingsChoice("2", stringResource(Res.string.discord_activity_listening)),
            SettingsChoice("3", stringResource(Res.string.discord_activity_watching)),
            SettingsChoice("5", stringResource(Res.string.discord_activity_competing)),
        )
    val refreshSuccess = stringResource(Res.string.discord_refresh_queued)
    val refreshRateLimited = stringResource(Res.string.discord_refresh_rate_limited)
    val refreshError = stringResource(Res.string.discord_refresh_error)

    fun notifyDiscordChanged() {
        DiscordRpcManager.notifySettingsChanged()
        host.onDiscordSettingsChanged()
    }

    builder.page(
        id = SettingsPageId.DISCORD,
        title = stringResource(Res.string.brand_discord),
        subtitle =
            settingsPageSubtitle(
                stringResource(Res.string.settings_discord_overview),
                integrationStatus(
                    settings.enableDiscordIntegration,
                    account =
                        discordUsername.takeIf {
                            discordLoggedIn
                        },
                    requiresAccount = true,
                ),
            ),
        icon = MetrolistIconsBrand.Discord,
        tone = SettingsTone.ORANGE,
    ) {
        group("discordMaster", "") {
            mainSwitch(
                id = "discordEnabled",
                title = stringResource(Res.string.discord_enable_rich_presence),
                checked = settings.enableDiscordIntegration,
                description = stringResource(Res.string.discord_integration_description),
            ) {
                settings.enableDiscordIntegration = it
                notifyDiscordChanged()
            }
        }
        group("discordAccount", stringResource(Res.string.account), enabled = settings.enableDiscordIntegration) {
            if (discordError != null) {
                custom("discordError", stringResource(Res.string.error_title)) {
                    DiscordErrorPanel(discordError, host, enabled = settings.enableDiscordIntegration)
                }
            }
            if (discordLoggedIn) {
                action(
                    id = "discordLogout",
                    title = discordUsername.ifBlank { stringResource(Res.string.discord_logged_in) },
                    description = discordStatus,
                    valueLabel = stringResource(Res.string.discord_logout),
                    confirmationMessage = stringResource(Res.string.account_logout_confirm),
                ) {
                    DiscordRpcManager.logout()
                    settings.discordUsername = ""
                    settings.discordName = ""
                    settings.discordAvatar = ""
                    onAccountsChanged()
                }
            } else {
                action(
                    id = "discordLogin",
                    title = stringResource(Res.string.action_login),
                    description = stringResource(Res.string.discord_login_description),
                    valueLabel = discordStatus,
                    enabled = !host.discordLoginInProgress,
                ) {
                    runCatching { DiscordRpcManager.auth.createSession() }
                        .onSuccess(host.onStartDiscordLogin)
                        .onFailure { DiscordRpcManager.setError("discord_error_no_browser") }
                }
            }
        }
        group(
            "discordPresence",
            stringResource(Res.string.settings),
            description = stringResource(Res.string.discord_presence_status_warning),
            enabled = discordSettingsEnabled,
        ) {
            host.playback?.takeIf { settings.enableDiscordIntegration }?.let { playback ->
                custom("discordPreview", stringResource(Res.string.discord_rich_presence)) {
                    val position =
                        host.playbackPosition?.let { source ->
                            remember(source) { source.wholeSecondPosition() }.collectAsState(source.value).value
                        } ?: playback.positionMs
                    val previewPlayback = playback.copy(positionMs = position)
                    DiscordPresencePreview(
                        activity =
                            buildDiscordActivity(
                                previewPlayback,
                                settings,
                                unknownArtist = stringResource(Res.string.unknown_artist),
                                unknownAlbum = stringResource(Res.string.discord_unknown_album),
                                button1Default = stringResource(Res.string.discord_button1_text_default),
                                button2Default = stringResource(Res.string.discord_button2_text_default),
                            ),
                        playback = previewPlayback,
                        onOpenUrl = host.onOpenUrl,
                        enabled = discordSettingsEnabled,
                    )
                }
            }
            val unknownArtist = stringResource(Res.string.unknown_artist)
            val unknownAlbum = stringResource(Res.string.discord_unknown_album)
            val button1Default = stringResource(Res.string.discord_button1_text_default)
            val button2Default = stringResource(Res.string.discord_button2_text_default)
            action(
                id = "refreshPresence",
                title = stringResource(Res.string.discord_refresh_rpc),
                description = stringResource(Res.string.discord_refresh_rpc_description),
                enabled = discordLoggedIn && settings.enableDiscordIntegration && host.playback != null,
            ) {
                val playback = host.playback?.copy(positionMs = host.currentPositionMs())
                val result =
                    if (playback == null) {
                        DiscordRpcManager.RefreshResult.NotReady
                    } else {
                        DiscordRpcManager.refreshPresence(
                            buildDiscordActivity(
                                playback,
                                settings,
                                unknownArtist = unknownArtist,
                                unknownAlbum = unknownAlbum,
                                button1Default = button1Default,
                                button2Default = button2Default,
                            ),
                            playback.song.id,
                            playback.isPlaying,
                        )
                    }
                host.onMessage(
                    when (result) {
                        DiscordRpcManager.RefreshResult.Queued -> refreshSuccess

                        DiscordRpcManager.RefreshResult.RateLimited -> refreshRateLimited

                        DiscordRpcManager.RefreshResult.NotReady,
                        DiscordRpcManager.RefreshResult.Error,
                        -> refreshError
                    },
                )
            }
        }
        group("discordAdvanced", stringResource(Res.string.advanced), enabled = discordSettingsEnabled) {
            switch(
                id = "discordAdvancedMode",
                title = stringResource(Res.string.discord_advanced_mode),
                checked = settings.discordAdvancedMode,
                description = stringResource(Res.string.settings_discord_advanced_summary),
            ) {
                settings.discordAdvancedMode = it
                notifyDiscordChanged()
            }
            if (settings.discordAdvancedMode || showAllDiscordSettings) {
                choice(
                    id = "discordActivityType",
                    title = stringResource(Res.string.discord_activity_type),
                    valueLabel = activityTypes.firstOrNull { it.id == settings.discordActivityType }?.label,
                    choices = activityTypes,
                    selectedChoiceId = settings.discordActivityType,
                    description = stringResource(Res.string.settings_discord_activity_type_summary),
                ) {
                    settings.discordActivityType = it
                    notifyDiscordChanged()
                }
                text(
                    id = "discordActivityName",
                    title = stringResource(Res.string.discord_activity_name),
                    value = settings.discordActivityName,
                    placeholder = stringResource(Res.string.discord_listen_to_metrolist),
                    description = stringResource(Res.string.settings_discord_activity_name_summary),
                ) {
                    settings.discordActivityName = it
                    notifyDiscordChanged()
                }
                text(
                    id = "discordStateTemplate",
                    title = stringResource(Res.string.discord_state),
                    description = stringResource(Res.string.discord_available_variables_body),
                    value = settings.discordStateTemplate,
                ) {
                    settings.discordStateTemplate = it
                    notifyDiscordChanged()
                }
                text(
                    id = "discordDetailsTemplate",
                    title = stringResource(Res.string.details),
                    description = stringResource(Res.string.discord_available_variables_body),
                    value = settings.discordDetailsTemplate,
                ) {
                    settings.discordDetailsTemplate = it
                    notifyDiscordChanged()
                }
                switch(
                    id = "discordButton1Enabled",
                    title = stringResource(Res.string.discord_button_1),
                    checked = settings.discordButton1Enabled,
                    description = stringResource(Res.string.settings_discord_link_button_summary),
                ) {
                    settings.discordButton1Enabled = it
                    notifyDiscordChanged()
                }
                if (settings.discordButton1Enabled || showAllDiscordSettings) {
                    text("discordButton1Text", stringResource(Res.string.discord_button1_label), value = settings.discordButton1Text) {
                        settings.discordButton1Text = it
                        notifyDiscordChanged()
                    }
                    text(
                        id = "discordButton1Url",
                        title = stringResource(Res.string.discord_button1_url),
                        description = stringResource(Res.string.discord_available_url_variables_body),
                        value = settings.discordButton1Url,
                    ) {
                        settings.discordButton1Url = it
                        notifyDiscordChanged()
                    }
                }

                switch(
                    id = "discordButton2Enabled",
                    title = stringResource(Res.string.discord_button_2),
                    checked = settings.discordButton2Enabled,
                    description = stringResource(Res.string.settings_discord_link_button_summary),
                ) {
                    settings.discordButton2Enabled = it
                    notifyDiscordChanged()
                }
                if (settings.discordButton2Enabled || showAllDiscordSettings) {
                    text("discordButton2Text", stringResource(Res.string.discord_button2_label), value = settings.discordButton2Text) {
                        settings.discordButton2Text = it
                        notifyDiscordChanged()
                    }
                    text(
                        id = "discordButton2Url",
                        title = stringResource(Res.string.discord_button2_url),
                        description = stringResource(Res.string.discord_available_url_variables_body),
                        value = settings.discordButton2Url,
                    ) {
                        settings.discordButton2Url = it
                        notifyDiscordChanged()
                    }
                }
            }
        }
    }

    builder.page(
        id = SettingsPageId.LAST_FM,
        title = stringResource(Res.string.brand_lastfm),
        subtitle =
            settingsPageSubtitle(
                stringResource(Res.string.settings_lastfm_overview),
                integrationStatus(settings.lastFmEnabled, lastFmAccount?.account, requiresAccount = true),
            ),
        icon = MetrolistIconsBrand.LastFm,
        tone = SettingsTone.ROSE,
    ) {
        group("lastFmMaster", "") {
            mainSwitch(
                id = "lastFmEnabled",
                title = stringResource(Res.string.scrobble_enable),
                checked = settings.lastFmEnabled,
                description = stringResource(Res.string.lastfm_enable_description),
            ) { settings.lastFmEnabled = it }
        }
        group("lastFmAccount", stringResource(Res.string.account), enabled = settings.lastFmEnabled) {
            if (lastFmAccount == null) {
                action(
                    id = "lastFmLogin",
                    title = stringResource(Res.string.lastfm_connect_account),
                    description = stringResource(Res.string.lastfm_connect_description),
                ) { onOpenLogin(ScrobbleLoginProvider.LAST_FM) }
            } else {
                action(
                    id = "lastFmLogout",
                    title = stringResource(Res.string.lastfm_logged_in_as, lastFmAccount.account),
                    description = stringResource(Res.string.scrobble_tap_to_disconnect),
                    valueLabel = stringResource(Res.string.logout),
                    confirmationMessage = stringResource(Res.string.account_logout_confirm),
                ) { onDisconnectScrobble(ScrobbleProvider.LAST_FM) }
            }
        }
        group(
            "lastFmScrobbling",
            stringResource(Res.string.settings),
            enabled = settings.lastFmEnabled && lastFmAccount != null,
        ) {
            switch(
                id = "lastFmNowPlaying",
                title = stringResource(Res.string.scrobble_now_playing),
                description = stringResource(Res.string.lastfm_now_playing_description),
                checked = settings.lastFmUseNowPlaying,
                enabled = settings.lastFmEnabled && lastFmAccount != null,
            ) { settings.lastFmUseNowPlaying = it }
        }
        group(
            "lastFmSensitivity",
            stringResource(Res.string.advanced),
            enabled =
                settings.lastFmEnabled && lastFmAccount != null,
        ) {
            slider(
                id = "lastFmDelay",
                title = stringResource(Res.string.scrobble_delay),
                description = stringResource(Res.string.scrobble_delay_description),
                valueLabel =
                    stringResource(
                        Res.string.lastfm_scrobble_percentage,
                        (settings.lastFmDelayPercent * 100).roundToInt(),
                        stringResource(Res.string.percent_symbol),
                    ),
                value = settings.lastFmDelayPercent,
                range = 0.1f..1f,
                steps = 89,
                enabled = settings.lastFmEnabled && lastFmAccount != null,
                valueFormatter = {
                    stringResource(
                        Res.string.lastfm_scrobble_percentage,
                        (it * 100).roundToInt(),
                        stringResource(Res.string.percent_symbol),
                    )
                },
            ) { settings.lastFmDelayPercent = it }
            slider(
                id = "lastFmMinDuration",
                title = stringResource(Res.string.minimum_song_duration),
                description = stringResource(Res.string.scrobble_min_duration_description),
                valueLabel = pluralStringResource(Res.plurals.seconds, settings.lastFmMinDuration, settings.lastFmMinDuration),
                value = settings.lastFmMinDuration.toFloat(),
                range = 10f..600f,
                steps = 589,
                enabled = settings.lastFmEnabled && lastFmAccount != null,
                valueFormatter = { pluralStringResource(Res.plurals.seconds, it.toInt(), it.toInt()) },
            ) { settings.lastFmMinDuration = it.toInt() }
            slider(
                id = "lastFmMaxDelay",
                title = stringResource(Res.string.scrobble_max_delay),
                description = stringResource(Res.string.scrobble_max_delay_description),
                valueLabel = pluralStringResource(Res.plurals.seconds, settings.lastFmMaxDelay, settings.lastFmMaxDelay),
                value = settings.lastFmMaxDelay.toFloat(),
                range = 30f..600f,
                steps = 569,
                enabled = settings.lastFmEnabled && lastFmAccount != null,
                valueFormatter = { pluralStringResource(Res.plurals.seconds, it.toInt(), it.toInt()) },
            ) { settings.lastFmMaxDelay = it.toInt() }
        }
    }

    builder.page(
        id = SettingsPageId.LISTEN_BRAINZ,
        title = stringResource(Res.string.brand_listenbrainz),
        subtitle =
            settingsPageSubtitle(
                stringResource(Res.string.settings_listenbrainz_overview),
                integrationStatus(settings.listenBrainzEnabled, listenBrainzAccount?.account, requiresAccount = true),
            ),
        icon = MetrolistIcons.Outlined.MusicNote,
        tone = SettingsTone.PURPLE,
    ) {
        group("listenBrainzMaster", "") {
            mainSwitch(
                id = "listenBrainzEnabled",
                title = stringResource(Res.string.scrobble_enable),
                checked = settings.listenBrainzEnabled,
                description = stringResource(Res.string.listenbrainz_enable_description),
            ) { settings.listenBrainzEnabled = it }
        }
        group("listenBrainzAccount", stringResource(Res.string.account), enabled = settings.listenBrainzEnabled) {
            if (listenBrainzAccount == null) {
                action(
                    id = "listenBrainzLogin",
                    title = stringResource(Res.string.listenbrainz_connect_token),
                    description = stringResource(Res.string.listenbrainz_description),
                ) { onOpenLogin(ScrobbleLoginProvider.LISTEN_BRAINZ) }
            } else {
                action(
                    id = "listenBrainzLogout",
                    title = stringResource(Res.string.listenbrainz_connected_as, listenBrainzAccount.account),
                    description = stringResource(Res.string.scrobble_tap_to_disconnect),
                    valueLabel = stringResource(Res.string.logout),
                    confirmationMessage = stringResource(Res.string.account_logout_confirm),
                ) { onDisconnectScrobble(ScrobbleProvider.LISTEN_BRAINZ) }
            }
        }
        group(
            "listenBrainzScrobbling",
            stringResource(Res.string.settings),
            enabled = settings.listenBrainzEnabled && listenBrainzAccount != null,
        ) {
            switch(
                id = "listenBrainzNowPlaying",
                title = stringResource(Res.string.scrobble_now_playing),
                description = stringResource(Res.string.listenbrainz_now_playing_description),
                checked = settings.listenBrainzUseNowPlaying,
                enabled = settings.listenBrainzEnabled && listenBrainzAccount != null,
            ) { settings.listenBrainzUseNowPlaying = it }
        }
    }

    host.deviceConnect?.let { builder.deviceConnectPage(it) }
    if (host.listenTogetherManager != null || host.onOpenListenTogether != null) {
        builder.customPage(
            SettingsPageId.LISTEN_TOGETHER,
            stringResource(Res.string.listen_together),
            settingsPageSubtitle(
                stringResource(Res.string.settings_listen_together_overview),
                integrationStatus(settings.listenTogetherEnabled, settings.listenTogetherUsername, requiresAccount = true),
            ),
            MetrolistIcons.Outlined.Headphones,
            SettingsTone.BLUE,
            searchTerms =
                listOf(
                    stringResource(Res.string.listen_together_description),
                    stringResource(Res.string.username),
                    stringResource(Res.string.listen_together_server),
                    stringResource(Res.string.listen_together_blocked_users),
                    stringResource(Res.string.listen_together_auto_approval_joins),
                    stringResource(Res.string.listen_together_auto_approval_suggestions),
                    stringResource(Res.string.listen_together_sync_volume),
                    stringResource(Res.string.server_logs),
                ),
        )
    }
    builder.rootGroup(
        stringResource(Res.string.integrations),
        *listOfNotNull(
            SettingsPageId.DISCORD,
            SettingsPageId.LAST_FM,
            SettingsPageId.LISTEN_BRAINZ,
            SettingsPageId.DEVICE_CONNECT.takeIf { host.deviceConnect != null },
            SettingsPageId.LISTEN_TOGETHER.takeIf { host.listenTogetherManager != null || host.onOpenListenTogether != null },
        ).toTypedArray(),
    )
    return builder.build()
}

@Composable
internal fun integrationStatus(
    enabled: Boolean,
    account: String? = null,
    requiresAccount: Boolean = false,
): String =
    when {
        !enabled -> stringResource(Res.string.integration_off)
        !account.isNullOrBlank() -> stringResource(Res.string.integration_on_account, account)
        requiresAccount -> stringResource(Res.string.integration_on_signed_out)
        else -> stringResource(Res.string.integration_on)
    }

internal fun buildMobileDiscordPlaybackUpdate(
    playback: IntegrationPlaybackState?,
    foreground: Boolean,
    settings: Settings,
    unknownArtist: String,
    unknownAlbum: String,
    button1Default: String,
    button2Default: String,
    now: Long = MetrolistClock.nowMs(),
    settingsVisible: Boolean = false,
): DiscordPlaybackUpdate =
    DiscordPlaybackUpdate(
        keepConnected =
            settings.enableDiscordIntegration && ((foreground && settingsVisible) || foreground || playback?.isPlaying == true),
        songId = playback?.song?.id,
        activity =
            if (settings.enableDiscordIntegration && playback?.isPlaying == true) {
                buildDiscordActivity(playback, settings, unknownArtist, unknownAlbum, button1Default, button2Default, now)
            } else {
                null
            },
    )

internal fun buildDiscordActivity(
    playback: IntegrationPlaybackState,
    settings: Settings,
    unknownArtist: String = "",
    unknownAlbum: String = "",
    button1Default: String,
    button2Default: String,
    now: Long = MetrolistClock.nowMs(),
): DiscordActivity {
    val song = playback.song
    val artist = song.artists.joinToString { it.name }.ifBlank { unknownArtist }
    val duration = playback.durationMs.takeIf { it > 0 } ?: song.duration?.times(1000L).orEmptyDuration()
    val timestamps = discordPlaybackTimestamps(now, playback.positionMs, duration, playback.playbackSpeed, playback.isPlaying)
    return DiscordActivityBuilder.build(
        song = song,
        artistName = artist,
        albumName = song.album?.name,
        artistThumbnail =
            if (settings.discordRpcShowArtistIcon) {
                playback.artistArtworkUrl ?: song.artists.firstOrNull()?.thumbnail
            } else {
                null
            },
        songTitle = song.title,
        startTimestamp = timestamps.start,
        endTimestamp = timestamps.end,
        advancedMode = settings.discordAdvancedMode,
        activityType = settings.discordActivityType.toIntOrNull() ?: 2,
        activityName = settings.discordActivityName,
        stateTemplate = settings.discordStateTemplate,
        detailsTemplate = settings.discordDetailsTemplate,
        btn1Enabled = settings.discordButton1Enabled,
        btn1Label = resolveDiscordButtonLabel(settings.discordButton1Text, DiscordDefaults.BUTTON1_LABEL, button1Default),
        btn1Url = settings.discordButton1Url,
        btn2Enabled = settings.discordButton2Enabled,
        btn2Label = resolveDiscordButtonLabel(settings.discordButton2Text, DiscordDefaults.BUTTON2_LABEL, button2Default),
        btn2Url = settings.discordButton2Url,
        basicBtn1Label = button1Default,
        basicBtn2Label = button2Default,
        unknownAlbum = unknownAlbum,
    )
}

private fun Long?.orEmptyDuration(): Long = this ?: 0

@Composable
private fun DiscordErrorPanel(
    error: DiscordErrorInfo,
    host: SharedIntegrationsHost,
    enabled: Boolean,
) {
    val message = discordErrorMessage(error.type)
    val errorTitle = stringResource(Res.string.error_title)
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MetrolistIcons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            IconButton(onClick = DiscordRpcManager::clearLastError, enabled = enabled, shapes = IconButtonDefaults.shapes()) {
                Icon(MetrolistIcons.Default.Close, stringResource(Res.string.dismiss))
            }
        }
        TextButton(onClick = { host.onCopyText(errorTitle, message) }, enabled = enabled, shapes = ButtonDefaults.shapes()) {
            Icon(MetrolistIcons.Outlined.ContentCopy, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.copy))
        }
    }
}

@Composable
private fun discordErrorMessage(type: String): String =
    stringResource(
        when (type) {
            "discord_error_denied" -> Res.string.discord_error_denied
            "discord_error_connection_failed" -> Res.string.discord_error_connection_failed
            "discord_error_gateway_configuration" -> Res.string.discord_error_gateway_configuration
            "discord_error_no_browser" -> Res.string.discord_error_no_browser
            "discord_error_token_exchange_failed" -> Res.string.discord_error_token_exchange_failed
            "discord_error_token_refresh_failed" -> Res.string.discord_error_token_refresh_failed
            "discord_error_invalid_scope" -> Res.string.discord_error_invalid_scope
            "discord_error_timeout" -> Res.string.discord_error_timeout
            "discord_error_missing_code" -> Res.string.discord_error_missing_code
            else -> Res.string.discord_refresh_error
        },
    )

@Composable
private fun DiscordPresencePreview(
    activity: DiscordActivity,
    playback: IntegrationPlaybackState,
    onOpenUrl: (String) -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    activity.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                activity.details?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(
                    activity.state,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playback.durationMs > 0) {
                    Text(
                        "${formatDuration(playback.positionMs)} /\u00a0${formatDuration(playback.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val configuredButtons =
            listOfNotNull(
                activity.button1Label?.let { label -> activity.button1Url?.let { label to it } },
                activity.button2Label?.let { label -> activity.button2Url?.let { label to it } },
            ).filter { (label, url) -> label.isNotEmpty() && url.isNotEmpty() }
        val buttons = DiscordPresence.validatedButtons(configuredButtons)
        buttons.forEach { (label, url) ->
            Button(onClick = {
                onOpenUrl(url)
            }, enabled = enabled, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Text(label) }
        }
        if (buttons != configuredButtons) {
            Text(stringResource(Res.string.discord_preview_buttons_adjusted), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ScrobbleLoginDialog(
    provider: ScrobbleLoginProvider,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
    onConnected: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember(provider) { mutableStateOf("") }
    val credentialState = remember(provider) { TextFieldState("") }
    val credential = credentialState.text.toString()
    var connecting by remember(provider) { mutableStateOf(false) }
    var error by remember(provider) { mutableStateOf<String?>(null) }
    val isLastFm = provider == ScrobbleLoginProvider.LAST_FM
    val invalidLastFm = stringResource(Res.string.lastfm_invalid_credentials)
    val lastFmConnectionFailed = stringResource(Res.string.scrobble_connection_failed)
    val lastFmServiceError = stringResource(Res.string.lastfm_service_error)
    val invalidLastFmSession = stringResource(Res.string.lastfm_login_session_invalid)
    val listenBrainzFailed = stringResource(Res.string.listenbrainz_connect_failed)

    AlertDialog(
        onDismissRequest = { if (!connecting) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (isLastFm) Res.string.lastfm_connect_title else Res.string.listenbrainz_connect_title,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.width(IntrinsicSize.Min), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isLastFm) {
                    OutlinedTextField(
                        value = username,
                        modifier = Modifier.semantics { contentType = ContentType.Username },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        onValueChange = { username = it },
                        label = { Text(stringResource(Res.string.username)) },
                        singleLine = true,
                        enabled = !connecting,
                    )
                }
                OutlinedSecureTextField(
                    state = credentialState,
                    modifier = Modifier.semantics { contentType = ContentType.Password },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = {
                        Text(
                            stringResource(
                                if (isLastFm) Res.string.password else Res.string.listenbrainz_user_token,
                            ),
                        )
                    },
                    enabled = !connecting,
                    textObfuscationMode = TextObfuscationMode.Hidden,
                )
                if (!isLastFm) {
                    TextButton(onClick = { onOpenUrl("https://listenbrainz.org/settings/") }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.listenbrainz_user_token))
                        Spacer(Modifier.width(8.dp))
                        Icon(MetrolistIcons.AutoMirrored.Filled.OpenInNew, stringResource(Res.string.open_website))
                    }
                }
                TextButton(
                    onClick = {
                        onOpenUrl(
                            if (isLastFm) {
                                "https://www.last.fm/join"
                            } else {
                                "https://listenbrainz.org/login/musicbrainz/?login_hint=register"
                            },
                        )
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.scrobble_create_account))
                    Spacer(Modifier.width(8.dp))
                    Icon(MetrolistIcons.AutoMirrored.Filled.OpenInNew, stringResource(Res.string.open_website))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !connecting && credential.isNotBlank() && (!isLastFm || username.isNotBlank()),
                onClick = {
                    connecting = true
                    error = null
                    scope.launch {
                        if (isLastFm) {
                            LastFM
                                .getMobileSession(GlobalYouTube.client, username.trim(), credential)
                                .onSuccess { response ->
                                    val session = response.session
                                    if (session == null) {
                                        error = invalidLastFmSession
                                    } else {
                                        ScrobbleSessions.connectLastFm(session.name, session.key)
                                        onConnected()
                                    }
                                }.onFailure {
                                    error =
                                        when (it.lastFmLoginFailure()) {
                                            LastFmLoginFailure.INVALID_CREDENTIALS -> invalidLastFm
                                            LastFmLoginFailure.CONNECTION -> lastFmConnectionFailed
                                            LastFmLoginFailure.SERVICE -> lastFmServiceError
                                        }
                                }
                        } else {
                            ListenBrainz
                                .validateToken(GlobalYouTube.client, credential.trim())
                                .onSuccess { user ->
                                    ScrobbleSessions.connectListenBrainz(user.username, credential.trim())
                                    onConnected()
                                }.onFailure {
                                    error = listenBrainzFailed
                                }
                        }
                        connecting = false
                    }
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                if (connecting) {
                    LoadingIndicator(Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(Res.string.connect))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !connecting,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.cancel)) }
        },
    )
}
