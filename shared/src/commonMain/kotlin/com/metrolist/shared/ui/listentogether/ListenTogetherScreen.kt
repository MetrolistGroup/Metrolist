@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package com.metrolist.shared.ui.listentogether

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.account
import com.metrolist.shared.generated.resources.add
import com.metrolist.shared.generated.resources.advanced
import com.metrolist.shared.generated.resources.approve
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.block
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.clear
import com.metrolist.shared.generated.resources.copy_logs
import com.metrolist.shared.generated.resources.edit
import com.metrolist.shared.generated.resources.invalid_room_code
import com.metrolist.shared.generated.resources.join_room
import com.metrolist.shared.generated.resources.kick_user
import com.metrolist.shared.generated.resources.listen_together
import com.metrolist.shared.generated.resources.listen_together_add_custom_server
import com.metrolist.shared.generated.resources.listen_together_add_queue_title
import com.metrolist.shared.generated.resources.listen_together_add_tracks_to_queue
import com.metrolist.shared.generated.resources.listen_together_auto_approval_joins
import com.metrolist.shared.generated.resources.listen_together_auto_approval_suggestions
import com.metrolist.shared.generated.resources.listen_together_auto_approval_suggestions_desc
import com.metrolist.shared.generated.resources.listen_together_became_host
import com.metrolist.shared.generated.resources.listen_together_blocked_by_host
import com.metrolist.shared.generated.resources.listen_together_blocked_users
import com.metrolist.shared.generated.resources.listen_together_blocked_users_count
import com.metrolist.shared.generated.resources.listen_together_connected
import com.metrolist.shared.generated.resources.listen_together_connection_lost_guest
import com.metrolist.shared.generated.resources.listen_together_copy_code
import com.metrolist.shared.generated.resources.listen_together_copy_link
import com.metrolist.shared.generated.resources.listen_together_create_room_title
import com.metrolist.shared.generated.resources.listen_together_description
import com.metrolist.shared.generated.resources.listen_together_disconnected
import com.metrolist.shared.generated.resources.listen_together_edit_server
import com.metrolist.shared.generated.resources.listen_together_end_session
import com.metrolist.shared.generated.resources.listen_together_enter_code_hint
import com.metrolist.shared.generated.resources.listen_together_enter_code_title
import com.metrolist.shared.generated.resources.listen_together_entry_subtitle
import com.metrolist.shared.generated.resources.listen_together_error
import com.metrolist.shared.generated.resources.listen_together_host
import com.metrolist.shared.generated.resources.listen_together_host_action
import com.metrolist.shared.generated.resources.listen_together_host_card_desc
import com.metrolist.shared.generated.resources.listen_together_host_card_title
import com.metrolist.shared.generated.resources.listen_together_how_it_works
import com.metrolist.shared.generated.resources.listen_together_how_it_works_body
import com.metrolist.shared.generated.resources.listen_together_how_it_works_bullets
import com.metrolist.shared.generated.resources.listen_together_invitation_leave_message
import com.metrolist.shared.generated.resources.listen_together_join_tab
import com.metrolist.shared.generated.resources.listen_together_leave
import com.metrolist.shared.generated.resources.listen_together_lobby_code
import com.metrolist.shared.generated.resources.listen_together_log_host_changed
import com.metrolist.shared.generated.resources.listen_together_log_join_rejected
import com.metrolist.shared.generated.resources.listen_together_log_joined_room
import com.metrolist.shared.generated.resources.listen_together_log_kicked_from_room
import com.metrolist.shared.generated.resources.listen_together_log_reconnected_to_room
import com.metrolist.shared.generated.resources.listen_together_log_room_created
import com.metrolist.shared.generated.resources.listen_together_log_server_error
import com.metrolist.shared.generated.resources.listen_together_log_suggestion_received
import com.metrolist.shared.generated.resources.listen_together_log_user_joined
import com.metrolist.shared.generated.resources.listen_together_log_user_left
import com.metrolist.shared.generated.resources.listen_together_log_waiting_for_buffers
import com.metrolist.shared.generated.resources.listen_together_logs
import com.metrolist.shared.generated.resources.listen_together_no_blocked_users
import com.metrolist.shared.generated.resources.listen_together_no_logs
import com.metrolist.shared.generated.resources.listen_together_not_configured
import com.metrolist.shared.generated.resources.listen_together_now_playing
import com.metrolist.shared.generated.resources.listen_together_participants
import com.metrolist.shared.generated.resources.listen_together_pending_suggestions
import com.metrolist.shared.generated.resources.listen_together_pending_users
import com.metrolist.shared.generated.resources.listen_together_queue_too_large
import com.metrolist.shared.generated.resources.listen_together_reconnecting
import com.metrolist.shared.generated.resources.listen_together_room
import com.metrolist.shared.generated.resources.listen_together_room_code
import com.metrolist.shared.generated.resources.listen_together_room_created
import com.metrolist.shared.generated.resources.listen_together_server
import com.metrolist.shared.generated.resources.listen_together_server_label
import com.metrolist.shared.generated.resources.listen_together_server_maintainer
import com.metrolist.shared.generated.resources.listen_together_server_name
import com.metrolist.shared.generated.resources.listen_together_server_url
import com.metrolist.shared.generated.resources.listen_together_servers
import com.metrolist.shared.generated.resources.listen_together_settings_description
import com.metrolist.shared.generated.resources.listen_together_share_logs
import com.metrolist.shared.generated.resources.listen_together_submitting_join
import com.metrolist.shared.generated.resources.listen_together_suggest_tracks_to_host
import com.metrolist.shared.generated.resources.listen_together_suggestion_approved
import com.metrolist.shared.generated.resources.listen_together_suggestion_auto_approved
import com.metrolist.shared.generated.resources.listen_together_suggestion_rejected
import com.metrolist.shared.generated.resources.listen_together_suggestion_submitted
import com.metrolist.shared.generated.resources.listen_together_suggestion_waiting
import com.metrolist.shared.generated.resources.listen_together_sync_volume
import com.metrolist.shared.generated.resources.listen_together_sync_volume_desc
import com.metrolist.shared.generated.resources.listen_together_track_load_failed
import com.metrolist.shared.generated.resources.listen_together_username_required
import com.metrolist.shared.generated.resources.listen_together_users
import com.metrolist.shared.generated.resources.listen_together_waiting_for_approval
import com.metrolist.shared.generated.resources.listen_together_you_are_blocked
import com.metrolist.shared.generated.resources.not_set
import com.metrolist.shared.generated.resources.ok
import com.metrolist.shared.generated.resources.reject
import com.metrolist.shared.generated.resources.remove_server
import com.metrolist.shared.generated.resources.replace
import com.metrolist.shared.generated.resources.required
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.save
import com.metrolist.shared.generated.resources.scrobble_connecting
import com.metrolist.shared.generated.resources.scrobble_connection_failed
import com.metrolist.shared.generated.resources.server_logs
import com.metrolist.shared.generated.resources.server_logs_desc
import com.metrolist.shared.generated.resources.settings
import com.metrolist.shared.generated.resources.settings_listen_together_overview
import com.metrolist.shared.generated.resources.settings_room_address_summary
import com.metrolist.shared.generated.resources.settings_room_blocks_summary
import com.metrolist.shared.generated.resources.settings_room_help_summary
import com.metrolist.shared.generated.resources.settings_room_server_summary
import com.metrolist.shared.generated.resources.suggest
import com.metrolist.shared.generated.resources.unblock
import com.metrolist.shared.generated.resources.username
import com.metrolist.shared.generated.resources.username_to_block
import com.metrolist.shared.listentogether.ListenTogetherConnectionState
import com.metrolist.shared.listentogether.ListenTogetherEvent
import com.metrolist.shared.listentogether.ListenTogetherJoinRequest
import com.metrolist.shared.listentogether.ListenTogetherLogEntry
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.listentogether.ListenTogetherServer
import com.metrolist.shared.listentogether.ListenTogetherSuggestion
import com.metrolist.shared.listentogether.ListenTogetherText
import com.metrolist.shared.listentogether.listenTogetherControls
import com.metrolist.shared.listentogether.network.RejectionReasons
import com.metrolist.shared.listentogether.network.RoomState
import com.metrolist.shared.listentogether.network.TrackInfo
import com.metrolist.shared.listentogether.network.UserInfo
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.Dialog
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.settings.SettingsChoice
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.settings.SettingsPageScreen
import com.metrolist.shared.ui.settings.SettingsTone
import com.metrolist.shared.ui.settings.SettingsUiRegistryBuilder
import com.metrolist.shared.ui.settings.integrationStatus
import com.metrolist.shared.ui.settings.settingsPageSubtitle
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.carouselScroll
import com.metrolist.shared.ui.utils.toPolygonShape
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.utils.ShareUrls
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val ButtonRadius = RoundedCornerShape(100.dp)
private val GroupShape = RoundedCornerShape(20.dp)
private val ListenTogetherScreenPadding = 16.dp
private const val ROOM_CODE_LENGTH = 8
private val CompactWidth = 360.dp

private fun isTwoPane(
    width: Dp,
    height: Dp,
): Boolean = width >= 840.dp || (width >= 480.dp && width > height)

@Composable
fun ListenTogetherScreen(
    manager: ListenTogetherManager?,
    onNavigateBack: (() -> Unit)?,
    onNavigateToSettings: () -> Unit,
    onCopyText: (label: String, text: String) -> Unit,
    onEventMessage: (message: String, isFailure: Boolean) -> Unit = { _, _ -> },
    bottomInset: Dp = 0.dp,
    onDebugUpload: (() -> Unit)? = null,
    initialRoomCode: String? = null,
) {
    if (!Settings.shared.listenTogetherEnabled) {
        LaunchedEffect(Unit) { onNavigateBack?.invoke() }
        return
    }

    if (manager == null) {
        ListenTogetherUnavailable(onNavigateBack, bottomInset)
        return
    }

    val isInRoom by manager.isInRoom.collectAsState()
    val roomState by manager.roomState.collectAsState()
    val role by manager.role.collectAsState()
    val username by manager.username.collectAsState()
    val serverUrl by manager.serverUrl.collectAsState()
    val connectionState by manager.connectionState.collectAsState()
    val joinRequestPending by manager.joinRequestPending.collectAsState()
    val joinRequestSubmitted by manager.joinRequestSubmitted.collectAsState()
    val pendingJoinRequests by manager.pendingJoinRequests.collectAsState()
    val pendingSuggestions by manager.pendingSuggestions.collectAsState()
    val pendingQueueImport by manager.pendingQueueImport.collectAsState()
    var roomCode by remember(manager, initialRoomCode) { mutableStateOf(initialRoomCode.orEmpty()) }
    var invitationHandled by remember(manager, initialRoomCode) { mutableStateOf(false) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var roomCodeError by remember { mutableStateOf<String?>(null) }
    val usernameRequired = stringResource(Res.string.listen_together_username_required)
    val invalidRoomCode = stringResource(Res.string.invalid_room_code)

    ListenTogetherEvents(manager, onEventMessage)

    if (isInRoom && roomState != null) {
        ListenTogetherRoomScreen(
            manager = manager,
            roomState = roomState!!,
            role = role,
            connectionState = connectionState,
            pendingJoinRequests = pendingJoinRequests,
            pendingSuggestions = pendingSuggestions,
            onNavigateBack = onNavigateBack,
            onNavigateToSettings = onNavigateToSettings,
            onCopyText = onCopyText,
            bottomInset = bottomInset,
        )
    } else {
        ListenTogetherCreateJoinScreen(
            username = username,
            roomCode = roomCode,
            initialJoinMode = !initialRoomCode.isNullOrBlank(),
            connectionState = connectionState,
            joinRequestPending = joinRequestPending,
            joinRequestSubmitted = joinRequestSubmitted,
            onCancelJoin = manager::cancelJoinRequest,
            usernameError = usernameError,
            roomCodeError = roomCodeError,
            onUsernameChange = {
                usernameError = null
                manager.setUsername(it)
            },
            onRoomCodeChange = {
                roomCodeError = null
                roomCode = it.filter { char -> char.isLetterOrDigit() }.take(ROOM_CODE_LENGTH).uppercase()
            },
            onCreateRoom = {
                if (username.isBlank()) {
                    usernameError = usernameRequired
                } else {
                    manager.createRoom()
                }
            },
            onJoinRoom = {
                if (username.isBlank()) {
                    usernameError = usernameRequired
                } else if (roomCode.length == ROOM_CODE_LENGTH) {
                    manager.joinRoom(roomCode)
                } else {
                    roomCodeError = invalidRoomCode
                }
            },
            onNavigateBack = onNavigateBack,
            onNavigateToSettings = onNavigateToSettings,
            bottomInset = bottomInset,
            onDebugUpload = onDebugUpload,
        )
    }

    val currentRoomCode = roomState?.roomCode
    if (
        isInRoom && currentRoomCode != null && !initialRoomCode.isNullOrBlank() &&
        !initialRoomCode.equals(currentRoomCode, ignoreCase = true) && !invitationHandled
    ) {
        AlertDialog(
            onDismissRequest = { invitationHandled = true },
            title = { Text(stringResource(Res.string.join_room)) },
            text = {
                Text(stringResource(Res.string.listen_together_invitation_leave_message, currentRoomCode, initialRoomCode))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        invitationHandled = true
                        manager.leaveRoom()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.listen_together_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { invitationHandled = true }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    } else if (isInRoom && pendingQueueImport != null) {
        val pending = pendingQueueImport!!
        val isHost = role == ListenTogetherRoomRole.HOST
        AlertDialog(
            onDismissRequest = manager::cancelQueueImport,
            title = { Text(stringResource(Res.string.listen_together_add_queue_title, pending.title)) },
            text = {
                Text(
                    if (isHost) {
                        pluralStringResource(
                            Res.plurals.listen_together_add_tracks_to_queue,
                            pending.songs.size,
                            pending.songs.size,
                        )
                    } else {
                        pluralStringResource(
                            Res.plurals.listen_together_suggest_tracks_to_host,
                            pending.songs.size,
                            pending.songs.size,
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = manager::addPendingQueueImport, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(if (isHost) Res.string.add else Res.string.suggest))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = manager::cancelQueueImport,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.cancel)) }
                    TextButton(
                        enabled = isHost,
                        onClick = manager::replaceWithPendingQueueImport,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.replace)) }
                }
            },
        )
    }
}

@Composable
fun ListenTogetherSettingsScreen(
    manager: ListenTogetherManager?,
    onNavigateBack: () -> Unit,
    onNavigateToServer: () -> Unit,
    onNavigateToBlockedUsers: () -> Unit,
    onNavigateToLogs: () -> Unit,
    bottomInset: Dp = 0.dp,
) {
    if (manager == null) {
        ListenTogetherUnavailable(onNavigateBack, bottomInset)
        return
    }

    val username by manager.username.collectAsState()
    val serverUrl by manager.serverUrl.collectAsState()
    val servers by manager.servers.collectAsState()
    val blockedUsers by manager.blockedUsernames.collectAsState()
    val autoApproveJoinRequests by manager.autoApproveJoinRequests.collectAsState()
    val autoApproveSongSuggestions by manager.autoApproveSongSuggestions.collectAsState()
    val syncHostVolume by manager.syncHostVolume.collectAsState()
    val role by manager.role.collectAsState()
    val inRoom by manager.isInRoom.collectAsState()
    var showHelp by remember { mutableStateOf(false) }
    val enabled = Settings.shared.listenTogetherEnabled
    val settingsEnabled = listenTogetherControls(enabled, role, inRoom).settingsEnabled
    val builder = SettingsUiRegistryBuilder()
    builder.page(
        id = SettingsPageId.LISTEN_TOGETHER,
        title = stringResource(Res.string.listen_together),
        subtitle = settingsPageSubtitle(stringResource(Res.string.settings_listen_together_overview), integrationStatus(enabled, username)),
        icon = MetrolistIcons.Outlined.Headphones,
        tone = SettingsTone.BLUE,
    ) {
        group("listenTogetherMaster", "") {
            mainSwitch(
                id = "listenTogetherEnabled",
                title = stringResource(Res.string.listen_together),
                checked = enabled,
                description = stringResource(Res.string.listen_together_description),
                onCheckedChange = manager::setEnabled,
            )
        }
        group("listenTogetherAccount", stringResource(Res.string.account), enabled = enabled) {
            text(
                id = "listenTogetherUsername",
                title = stringResource(Res.string.username),
                description = username.ifBlank { stringResource(Res.string.not_set) },
                value = username,
                onValueChange = manager::setUsername,
            )
            choice(
                id = "listenTogetherServer",
                title = stringResource(Res.string.listen_together_server),
                valueLabel = servers.firstOrNull { it.url == serverUrl }?.name ?: serverUrl,
                choices =
                    servers.map {
                        SettingsChoice(
                            it.url,
                            stringResource(Res.string.listen_together_server_label, it.name, it.location),
                        )
                    },
                selectedChoiceId = serverUrl,
                onChoiceSelected = manager::setServerUrl,
                description = stringResource(Res.string.settings_room_server_summary),
            )
        }
        group("listenTogetherSettings", stringResource(Res.string.settings), enabled = settingsEnabled) {
            switch(
                id = "listenTogetherAutoApproveJoins",
                title = stringResource(Res.string.listen_together_auto_approval_joins),
                checked = autoApproveJoinRequests,
                onCheckedChange = manager::setAutoApproveJoinRequests,
            )
            switch(
                id = "listenTogetherAutoApproveSuggestions",
                title = stringResource(Res.string.listen_together_auto_approval_suggestions),
                description = stringResource(Res.string.listen_together_auto_approval_suggestions_desc),
                checked = autoApproveSongSuggestions,
                onCheckedChange = manager::setAutoApproveSongSuggestions,
            )
            switch(
                id = "listenTogetherSyncVolume",
                title = stringResource(Res.string.listen_together_sync_volume),
                description = stringResource(Res.string.listen_together_sync_volume_desc),
                checked = syncHostVolume,
                onCheckedChange = manager::setSyncHostVolume,
            )
            action(
                id = "listenTogetherBlockedUsers",
                title = stringResource(Res.string.listen_together_blocked_users),
                description =
                    settingsPageSubtitle(
                        stringResource(Res.string.settings_room_blocks_summary),
                        blockedUsers.size.takeIf { it > 0 }?.let {
                            pluralStringResource(Res.plurals.listen_together_blocked_users_count, it, it)
                        },
                    ),
                onClick = onNavigateToBlockedUsers,
            )
            action(
                id = "listenTogetherHelp",
                title = stringResource(Res.string.listen_together_how_it_works),
                description = stringResource(Res.string.settings_room_help_summary),
            ) { showHelp = true }
        }
        group("listenTogetherAdvanced", stringResource(Res.string.advanced), enabled = settingsEnabled) {
            action(
                id = "listenTogetherServerUrl",
                title = stringResource(Res.string.listen_together_server_url),
                description = settingsPageSubtitle(stringResource(Res.string.settings_room_address_summary), serverUrl),
                onClick = onNavigateToServer,
            )
            action(
                id = "listenTogetherLogs",
                title = stringResource(Res.string.server_logs),
                description = stringResource(Res.string.server_logs_desc),
                onClick = onNavigateToLogs,
            )
        }
    }
    SettingsPageScreen(
        page = builder.build().pages.single(),
        onNavigateBack = onNavigateBack,
        bottomContentPadding = bottomInset,
    )
    PopupVisibility(visible = showHelp && settingsEnabled) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(stringResource(Res.string.listen_together_how_it_works)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(Res.string.listen_together_how_it_works_body))
                    Text(stringResource(Res.string.listen_together_how_it_works_bullets))
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.ok))
                }
            },
        )
    }
}

@Composable
fun ListenTogetherServerScreen(
    manager: ListenTogetherManager?,
    onNavigateBack: () -> Unit,
    bottomInset: Dp = 0.dp,
) {
    if (!Settings.shared.listenTogetherEnabled) {
        LaunchedEffect(Unit) { onNavigateBack() }
        return
    }

    if (manager == null) {
        ListenTogetherUnavailable(onNavigateBack, bottomInset)
        return
    }

    val servers by manager.servers.collectAsState()
    val selectedServerUrl by manager.serverUrl.collectAsState()
    var showCustomServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ListenTogetherServer?>(null) }

    SettingsSubScreen(
        title = stringResource(Res.string.listen_together_server),
        onNavigateBack = onNavigateBack,
        bottomInset = bottomInset,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.navigationPopupSource(),
                onClick = { showCustomServerDialog = true },
                shape = ButtonRadius,
                icon = { Icon(MetrolistIcons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(Res.string.listen_together_add_custom_server)) },
            )
        },
    ) {
        SettingsSubScreenGroup(title = stringResource(Res.string.listen_together_servers)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                servers.forEach { server ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = GroupShape,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        ServerRow(
                            server = server,
                            selected = server.url == selectedServerUrl,
                            onClick = { manager.setServerUrl(server.url) },
                            onEdit = if (server.isCustom) ({ editingServer = server }) else null,
                            onRemove =
                                if (server.isCustom) {
                                    { manager.removeCustomServer(server.url) }
                                } else {
                                    null
                                },
                        )
                    }
                }
            }
        }
    }

    PopupVisibility(visible = showCustomServerDialog) {
        ListenTogetherTextDialog(
            title = stringResource(Res.string.listen_together_add_custom_server),
            label = stringResource(Res.string.listen_together_server_url),
            value = "",
            confirmText = stringResource(Res.string.save),
            onDismiss = { showCustomServerDialog = false },
            onConfirm = {
                manager.addCustomServer(it)
                showCustomServerDialog = false
            },
        )
    }
    editingServer?.let { server ->
        var name by remember(server.url) { mutableStateOf(server.name) }
        var operator by remember(server.url) { mutableStateOf(server.operator) }
        AlertDialog(
            onDismissRequest = { editingServer = null },
            title = { Text(stringResource(Res.string.listen_together_edit_server)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(Res.string.listen_together_server_name)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = operator,
                        onValueChange = { operator = it },
                        label = { Text(stringResource(Res.string.listen_together_server_maintainer)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && operator.isNotBlank(),
                    onClick = {
                        if (manager.updateCustomServerMetadata(server.url, name, operator)) editingServer = null
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingServer = null }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

@Composable
fun ListenTogetherBlockedUsersScreen(
    manager: ListenTogetherManager?,
    onNavigateBack: () -> Unit,
    bottomInset: Dp = 0.dp,
) {
    if (!Settings.shared.listenTogetherEnabled) {
        LaunchedEffect(Unit) { onNavigateBack() }
        return
    }

    if (manager == null) {
        ListenTogetherUnavailable(onNavigateBack, bottomInset)
        return
    }

    val blockedUsers by manager.blockedUsernames.collectAsState()
    var showBlockDialog by remember { mutableStateOf(false) }

    SettingsSubScreen(
        title = stringResource(Res.string.listen_together_blocked_users),
        onNavigateBack = onNavigateBack,
        bottomInset = bottomInset,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.navigationPopupSource(),
                onClick = { showBlockDialog = true },
                shape = ButtonRadius,
                icon = { Icon(MetrolistIcons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(Res.string.block)) },
            )
        },
    ) {
        if (blockedUsers.isEmpty()) {
            Text(
                text = stringResource(Res.string.listen_together_no_blocked_users),
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SettingsSubScreenGroup(
                title = stringResource(Res.string.listen_together_users),
                items =
                    blockedUsers.sorted().map { username ->
                        SettingsSubScreenItem(
                            title = username,
                            trailingContent = {
                                IconButton(onClick = { manager.unblockUser(username) }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(MetrolistIcons.Rounded.LockOpen, contentDescription = stringResource(Res.string.unblock))
                                }
                            },
                        )
                    },
            )
        }
    }

    PopupVisibility(visible = showBlockDialog) {
        ListenTogetherTextDialog(
            title = stringResource(Res.string.block),
            label = stringResource(Res.string.username_to_block),
            value = "",
            confirmText = stringResource(Res.string.block),
            onDismiss = { showBlockDialog = false },
            onConfirm = {
                manager.blockUser(it)
                showBlockDialog = false
            },
        )
    }
}

@Composable
fun ListenTogetherLogsScreen(
    manager: ListenTogetherManager?,
    onNavigateBack: () -> Unit,
    onCopyText: (label: String, text: String) -> Unit,
    onShareText: ((String) -> Unit)? = null,
    bottomInset: Dp = 0.dp,
) {
    if (!Settings.shared.listenTogetherEnabled) {
        LaunchedEffect(Unit) { onNavigateBack() }
        return
    }

    if (manager == null) {
        ListenTogetherUnavailable(onNavigateBack, bottomInset)
        return
    }

    val logs by manager.logs.collectAsState()
    val logText = remember(logs) { logs.toShareText() }
    val logScrollState = rememberScrollState()

    SettingsSubScreen(
        title = stringResource(Res.string.listen_together_logs),
        onNavigateBack = onNavigateBack,
        bottomInset = bottomInset,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { manager.clearLogs() },
                shape = ButtonRadius,
                icon = { Icon(MetrolistIcons.Filled.Close, contentDescription = null) },
                text = { Text(stringResource(Res.string.clear)) },
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onShareText?.let { shareText ->
                Button(
                    onClick = { shareText(logText) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(MetrolistIcons.Outlined.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.listen_together_share_logs))
                }
            }
            val logsLabel = stringResource(Res.string.listen_together_logs)
            IconButton(
                onClick = { onCopyText(logsLabel, logText) },
                modifier = Modifier.size(48.dp),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(MetrolistIcons.Default.ContentCopy, contentDescription = stringResource(Res.string.copy_logs))
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            shape = GroupShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Text(
                text = logText.ifBlank { stringResource(Res.string.listen_together_no_logs) },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                        .verticalScrollWithDrag(logScrollState),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ListenTogetherCreateJoinScreen(
    username: String,
    roomCode: String,
    initialJoinMode: Boolean,
    connectionState: ListenTogetherConnectionState,
    joinRequestPending: Boolean,
    joinRequestSubmitted: Boolean,
    onCancelJoin: () -> Unit,
    usernameError: String?,
    roomCodeError: String?,
    onUsernameChange: (String) -> Unit,
    onRoomCodeChange: (String) -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onNavigateBack: (() -> Unit)?,
    onNavigateToSettings: () -> Unit,
    bottomInset: Dp,
    onDebugUpload: (() -> Unit)?,
) {
    val isConnecting =
        connectionState == ListenTogetherConnectionState.CONNECTING ||
            connectionState == ListenTogetherConnectionState.RECONNECTING
    val enabled = !isConnecting && !joinRequestPending
    var joinMode by remember(initialJoinMode) { mutableStateOf(initialJoinMode) }
    var showUsernameDialog by remember { mutableStateOf(false) }

    PopupVisibility(visible = showUsernameDialog) {
        ListenTogetherTextDialog(
            title = stringResource(Res.string.username),
            label = stringResource(Res.string.username),
            value = username,
            confirmText = stringResource(Res.string.save),
            onDismiss = { showUsernameDialog = false },
            onConfirm = {
                onUsernameChange(it)
                showUsernameDialog = false
            },
        )
    }

    ListenTogetherScaffold(
        onNavigateBack = onNavigateBack,
        title = "",
    ) { paddingValues ->
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            val twoPane = isTwoPane(maxWidth, maxHeight)
            val paneWidth = if (twoPane) maxWidth / 2 else maxWidth
            val compact = paneWidth < CompactWidth
            val listPadding =
                PaddingValues(
                    start = ListenTogetherScreenPadding,
                    end = ListenTogetherScreenPadding,
                    top = 8.dp,
                    bottom = 24.dp + bottomInset,
                )

            val headerItems: LazyListScope.() -> Unit = {
                item {
                    HeroHeader(compact = compact, onIconDoubleTap = onDebugUpload)
                }
                item {
                    HostJoinTabs(
                        joinSelected = joinMode,
                        onSelectHost = { joinMode = false },
                        onSelectJoin = { joinMode = true },
                    )
                }
            }
            val formItems: LazyListScope.() -> Unit = {
                if (joinMode) {
                    item {
                        TabHeading(
                            title = stringResource(Res.string.listen_together_enter_code_title),
                            hint = stringResource(Res.string.listen_together_enter_code_hint),
                        )
                    }
                    item {
                        UsernameRow(
                            username = username,
                            error = usernameError,
                            onEdit = { showUsernameDialog = true },
                        )
                    }
                    item {
                        RoomCodeInput(
                            value = roomCode,
                            onValueChange = onRoomCodeChange,
                            error = roomCodeError,
                            large = true,
                        )
                    }
                    item {
                        ExpressiveButton(
                            onClick = onJoinRoom,
                            enabled = enabled && roomCode.length == ROOM_CODE_LENGTH,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(Res.string.join_room))
                        }
                    }
                } else {
                    item {
                        TabHeading(
                            title = stringResource(Res.string.listen_together_host_card_title),
                            hint = stringResource(Res.string.listen_together_host_card_desc),
                        )
                    }
                    item {
                        UsernameRow(
                            username = username,
                            error = usernameError,
                            onEdit = { showUsernameDialog = true },
                        )
                    }
                    item {
                        ExpressiveButton(
                            onClick = onCreateRoom,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(Res.string.listen_together_create_room_title))
                        }
                    }
                }
                item {
                    JoinStatusText(connectionState, joinRequestPending, joinRequestSubmitted)
                }
                if (joinRequestPending) {
                    item {
                        TextButton(onClick = onCancelJoin, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(Res.string.cancel))
                        }
                    }
                }
                item {
                    SettingsNavigationCard(onClick = onNavigateToSettings)
                }
            }

            if (twoPane) {
                Row(modifier = Modifier.fillMaxSize()) {
                    DragScrollableColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { headerItems() }
                    DragScrollableColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight().imePadding(),
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { formItems() }
                }
            } else {
                DragScrollableColumn(
                    modifier = Modifier.fillMaxSize().imePadding(),
                    contentPadding = listPadding,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    headerItems()
                    formItems()
                }
            }
        }
    }
}

@Composable
private fun TabHeading(
    title: String,
    hint: String,
) {
    Column(
        modifier = Modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExpressiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonDefaults.MediumContainerHeight).tvFocusHighlight(CircleShape),
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        colors = colors,
        contentPadding = ButtonDefaults.MediumContentPadding,
    ) {
        ProvideTextStyle(MaterialTheme.typography.titleMedium) { content() }
    }
}

@Composable
private fun ExpressiveOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).tvFocusHighlight(CircleShape),
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
    }
}

@Composable
private fun HeroHeader(
    compact: Boolean,
    onIconDoubleTap: (() -> Unit)?,
) {
    val cookie = MaterialShapes.Cookie9Sided.toPolygonShape()
    val currentDoubleTap by rememberUpdatedState(onIconDoubleTap)
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.listen_together).replaceFirst(' ', '\n'),
                style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = if (compact) 30.sp else 36.sp,
            )
            Text(
                text = stringResource(Res.string.listen_together_entry_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier =
                Modifier
                    .size(if (compact) 64.dp else 80.dp)
                    .clip(cookie)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { currentDoubleTap?.invoke() })
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MetrolistIcons.Outlined.Group,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(if (compact) 32.dp else 40.dp),
            )
        }
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun UsernameRow(
    username: String,
    error: String?,
    onEdit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text =
                            username
                                .firstOrNull()
                                ?.uppercaseChar()
                                ?.toString()
                                .orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = username.ifBlank { stringResource(Res.string.not_set) },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(Res.string.username),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.heightIn(min = ButtonDefaults.MinHeight).tvFocusHighlight(CircleShape),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                    Text(
                        text = stringResource(Res.string.edit),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        error?.let { ErrorText(it) }
    }
}

@Composable
private fun JoinStatusText(
    connectionState: ListenTogetherConnectionState,
    joinRequestPending: Boolean,
    joinRequestSubmitted: Boolean,
) {
    val statusText =
        when {
            connectionState == ListenTogetherConnectionState.CONNECTING -> stringResource(Res.string.scrobble_connecting)
            connectionState == ListenTogetherConnectionState.RECONNECTING -> stringResource(Res.string.listen_together_reconnecting)
            connectionState == ListenTogetherConnectionState.ERROR -> stringResource(Res.string.listen_together_error)
            joinRequestSubmitted -> stringResource(Res.string.listen_together_waiting_for_approval)
            joinRequestPending -> stringResource(Res.string.listen_together_submitting_join)
            else -> null
        }
    statusText?.let {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (joinRequestPending) {
                LoadingIndicator(modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun HostJoinTabs(
    joinSelected: Boolean,
    onSelectHost: () -> Unit,
    onSelectJoin: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ExpressiveTab(
            text = stringResource(Res.string.listen_together_host_action),
            selected = !joinSelected,
            onClick = onSelectHost,
            modifier = Modifier.weight(1f),
        )
        ExpressiveTab(
            text = stringResource(Res.string.listen_together_join_tab),
            selected = joinSelected,
            onClick = onSelectJoin,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ExpressiveTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        shapes =
            ToggleButtonShapes(
                shape = ToggleButtonDefaults.shape,
                pressedShape = ToggleButtonDefaults.shapesFor(ButtonDefaults.ExtraSmallContainerHeight).pressedShape,
                checkedShape = ToggleButtonDefaults.shape,
            ),
        colors =
            ToggleButtonDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.primary,
                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        modifier =
            modifier
                .heightIn(
                    min = ButtonDefaults.MediumContainerHeight,
                ).tvFocusHighlight(CircleShape)
                .clip(ToggleButtonDefaults.shape),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun RoomCodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    large: Boolean = false,
    length: Int = ROOM_CODE_LENGTH,
) {
    val textFieldState = rememberTextFieldState(initialText = value)
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(value) {
        if (textFieldState.text.toString() != value) {
            textFieldState.setTextAndPlaceCursorAtEnd(value)
        }
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { text ->
            if (text != latestValue) latestOnValueChange(text)
        }
    }

    var focused by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.listen_together_lobby_code)
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // The platform scrolls the hidden input rectangle; reveal the visible code cells instead.
    LaunchedEffect(focused, imeBottom) {
        if (focused) bringIntoViewRequester.bringIntoView()
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 320.dp
        val cellSpacing =
            if (compact) {
                4.dp
            } else if (large) {
                8.dp
            } else {
                6.dp
            }
        val cellHeight =
            if (compact) {
                48.dp
            } else if (large) {
                60.dp
            } else {
                52.dp
            }
        val cellStyle =
            if (large && !compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium
        Column(
            modifier = Modifier.bringIntoViewRequester(bringIntoViewRequester),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicTextField(
                state = textFieldState,
                inputTransformation =
                    InputTransformation.byValue { _, proposed ->
                        proposed
                            .filter { it.isLetterOrDigit() }
                            .take(ROOM_CODE_LENGTH)
                            .toString()
                            .uppercase()
                    },
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                cursorBrush = SolidColor(Color.Transparent),
                textStyle = TextStyle(color = Color.Transparent, fontFamily = FontFamily.Monospace),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = label },
                decorator = { innerTextField ->
                    Box {
                        Box(modifier = Modifier.size(1.dp).alpha(0f)) { innerTextField() }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(cellSpacing),
                        ) {
                            repeat(length) { index ->
                                val isActive = focused && index == value.length.coerceAtMost(length - 1)
                                val borderColor =
                                    when {
                                        error != null -> MaterialTheme.colorScheme.error
                                        isActive || index < value.length -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.outlineVariant
                                    }
                                Box(
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .height(cellHeight)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(
                                                if (isActive) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                                            ).border(if (isActive) 2.dp else 1.dp, borderColor, RoundedCornerShape(14.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = value.getOrNull(index)?.toString().orEmpty(),
                                        style = cellStyle,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }
                },
            )
            error?.let { ErrorText(it) }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun ListenTogetherRoomScreen(
    manager: ListenTogetherManager,
    roomState: RoomState,
    role: ListenTogetherRoomRole,
    connectionState: ListenTogetherConnectionState,
    pendingJoinRequests: List<ListenTogetherJoinRequest>,
    pendingSuggestions: List<ListenTogetherSuggestion>,
    onNavigateBack: (() -> Unit)?,
    onNavigateToSettings: () -> Unit,
    onCopyText: (label: String, text: String) -> Unit,
    bottomInset: Dp,
) {
    val isHost = listenTogetherControls(Settings.shared.listenTogetherEnabled, role, inRoom = true).canModerate
    val authenticated by manager.roomAuthenticated.collectAsState()
    val failedTrack by manager.failedRoomTrack.collectAsState()
    val submittedSuggestions by manager.submittedSuggestions.collectAsState()
    val copyLinkLabel = stringResource(Res.string.listen_together_copy_link)
    val copyCodeLabel = stringResource(Res.string.listen_together_copy_code)
    val hostLabel = stringResource(Res.string.listen_together_host)
    val users = roomState.users.ifEmpty { listOf(UserInfo(roomState.hostId, hostLabel, true)) }
    val status =
        when (connectionState) {
            ListenTogetherConnectionState.CONNECTING -> stringResource(Res.string.scrobble_connecting)
            ListenTogetherConnectionState.RECONNECTING -> stringResource(Res.string.listen_together_reconnecting)
            ListenTogetherConnectionState.ERROR -> stringResource(Res.string.listen_together_error)
            ListenTogetherConnectionState.DISCONNECTED -> stringResource(Res.string.listen_together_disconnected)
            else -> stringResource(Res.string.listen_together_connected)
        }

    ListenTogetherScaffold(
        onNavigateBack = onNavigateBack,
        title = stringResource(Res.string.listen_together_room),
        actions = {
            var isShiftPressed by remember { mutableStateOf(false) }
            val shareParty = {
                val link = manager.roomLink(useParty = true) ?: ShareUrls.party(roomState.roomCode, true)
                onCopyText(copyLinkLabel, link)
            }
            val shareNormal = {
                val link = manager.roomLink(useParty = false) ?: ShareUrls.party(roomState.roomCode, false)
                onCopyText(copyLinkLabel, link)
            }
            IconButton(
                onClick = {
                    if (isShiftPressed) {
                        shareParty()
                    } else {
                        shareNormal()
                    }
                },
                shapes = IconButtonDefaults.shapes(),
                modifier =
                    Modifier
                        .tvFocusHighlight(CircleShape)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    isShiftPressed = event.keyboardModifiers.isShiftPressed
                                }
                            }
                        }.pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (isShiftPressed) {
                                        shareParty()
                                    } else {
                                        shareNormal()
                                    }
                                },
                                onLongPress = {
                                    shareParty()
                                },
                            )
                        },
            ) {
                Icon(MetrolistIcons.Outlined.Share, contentDescription = copyLinkLabel)
            }
        },
    ) { paddingValues ->
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            val twoPane = isTwoPane(maxWidth, maxHeight)
            val listPadding =
                PaddingValues(
                    start = ListenTogetherScreenPadding,
                    end = ListenTogetherScreenPadding,
                    top = 8.dp,
                    bottom = 24.dp + bottomInset,
                )

            val codeItems: LazyListScope.() -> Unit = {
                item {
                    RoomCodeCard(
                        roomCode = roomState.roomCode,
                        onCopyCode = { onCopyText(copyCodeLabel, roomState.roomCode) },
                    )
                }
            }
            val peopleItems: LazyListScope.() -> Unit = {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionTitle(stringResource(Res.string.listen_together_participants))
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        ConnectedUsersCard(
                            users = users,
                            hostId = roomState.hostId,
                            isHost = isHost,
                            currentUserId = manager.userId.collectAsState().value,
                            onKick = manager::kickUser,
                            onBlock = manager::blockUser,
                        )
                    }
                }
                if (isHost && pendingJoinRequests.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SectionTitle(stringResource(Res.string.listen_together_pending_users))
                            Spacer(modifier = Modifier.height(2.dp))
                            pendingJoinRequests.forEach { request ->
                                key(request.userId) {
                                    PendingJoinRequestRow(
                                        request = request,
                                        enabled = authenticated,
                                        onApprove = { manager.approveJoin(request.userId) },
                                        onReject = { manager.rejectJoin(request.userId) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (isHost && pendingSuggestions.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SectionTitle(stringResource(Res.string.listen_together_pending_suggestions))
                            Spacer(modifier = Modifier.height(2.dp))
                            pendingSuggestions.forEach { suggestion ->
                                key(suggestion.id) {
                                    PendingSuggestionRow(
                                        suggestion = suggestion,
                                        enabled = authenticated,
                                        onApprove = { manager.approveSuggestion(suggestion.id) },
                                        onReject = { manager.rejectSuggestion(suggestion.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val playerItems: LazyListScope.() -> Unit = {
                if (connectionState == ListenTogetherConnectionState.ERROR) {
                    item {
                        Column {
                            if (!isHost) Text(stringResource(Res.string.listen_together_connection_lost_guest))
                            TextButton(
                                onClick = manager::retryConnection,
                                shapes = ButtonDefaults.shapes(),
                            ) { Text(stringResource(Res.string.retry)) }
                        }
                    }
                }
                submittedSuggestions.forEach { (id, track) ->
                    item(key = id) {
                        Column {
                            Text(track.title, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(Res.string.listen_together_suggestion_waiting))
                        }
                    }
                }
                failedTrack?.let { track ->
                    item {
                        Column {
                            Text(track.title, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(Res.string.listen_together_track_load_failed))
                            TextButton(onClick = manager::retryRoomTrack, enabled = authenticated, shapes = ButtonDefaults.shapes()) {
                                Text(stringResource(Res.string.retry))
                            }
                        }
                    }
                }
                roomState.currentTrack?.takeIf { it.id != failedTrack?.id }?.let { track ->
                    item {
                        NowPlayingCard(track = track, positionMs = roomState.position)
                    }
                }
                item {
                    LeaveRoomButton(
                        isHost = isHost,
                        onClick = manager::leaveRoom,
                    )
                }
            }
            val settingsItems: LazyListScope.() -> Unit = {
                item {
                    SettingsNavigationCard(onClick = onNavigateToSettings)
                }
            }

            if (twoPane) {
                Row(modifier = Modifier.fillMaxSize()) {
                    DragScrollableColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        codeItems()
                        playerItems()
                    }
                    DragScrollableColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        peopleItems()
                        settingsItems()
                    }
                }
            } else {
                DragScrollableColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listPadding,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    codeItems()
                    peopleItems()
                    playerItems()
                    settingsItems()
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(
    track: TrackInfo,
    positionMs: Long,
) {
    val progress = if (track.duration > 0) (positionMs.toFloat() / track.duration).coerceIn(0f, 1f) else 0f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Res.string.listen_together_now_playing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun LeaveRoomButton(
    isHost: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        ExpressiveButton(
            onClick = onClick,
            modifier = Modifier.widthIn(min = 200.dp),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
        ) {
            Icon(
                imageVector = MetrolistIcons.AutoMirrored.Outlined.Logout,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(
                text = stringResource(if (isHost) Res.string.listen_together_end_session else Res.string.listen_together_leave),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListenTogetherScaffold(
    onNavigateBack: (() -> Unit)?,
    title: String,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val navigationIcon: @Composable () -> Unit = {
        if (onNavigateBack != null) {
            IconButton(onClick = onNavigateBack, modifier = Modifier.tvFocusHighlight(CircleShape), shapes = IconButtonDefaults.shapes()) {
                Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
            }
        }
    }
    val colors =
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (onNavigateBack != null || title.isNotBlank() || actions != null) {
                TopAppBar(
                    title = { Text(title, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = navigationIcon,
                    actions = actions ?: {},
                    colors = colors,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        content = content,
    )
}

@Composable
private fun SettingsNavigationCard(onClick: () -> Unit) {
    SettingsSubScreenGroup(
        items =
            listOf(
                SettingsSubScreenItem(
                    title = stringResource(Res.string.settings),
                    description = stringResource(Res.string.listen_together_settings_description),
                    leadingContent = {
                        Icon(
                            imageVector = MetrolistIcons.Outlined.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = MetrolistIcons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = onClick,
                ),
            ),
        horizontalPadding = 0.dp,
    )
}

@Composable
private fun RoomCodeCard(
    roomCode: String,
    onCopyCode: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(Res.string.listen_together_room_code),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = roomCode.chunked(4).joinToString(" "),
                style = MaterialTheme.typography.headlineLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
            )
            ExpressiveOutlinedButton(onClick = onCopyCode) {
                Text(stringResource(Res.string.listen_together_copy_code))
            }
        }
    }
}

@Composable
private fun ConnectedUsersCard(
    users: List<UserInfo>,
    hostId: String,
    isHost: Boolean,
    currentUserId: String?,
    onKick: (String) -> Unit,
    onBlock: (UserInfo) -> Unit,
) {
    val scrollState = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier =
                Modifier
                    .carouselScroll(scrollState)
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            users.forEach { user ->
                key(user.userId) {
                    val isSelf = user.userId == currentUserId
                    ConnectedUserAvatar(
                        user = user,
                        isHost = user.userId == hostId,
                        showKick = isHost && !isSelf,
                        showBlock = !isSelf,
                        onKick = { onKick(user.userId) },
                        onBlock = { onBlock(user) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedUserAvatar(
    user: UserInfo,
    isHost: Boolean,
    showKick: Boolean,
    showBlock: Boolean,
    onKick: () -> Unit,
    onBlock: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val hasMenu = showKick || showBlock
    val containerColor =
        if (user.isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val contentColor =
        if (user.isConnected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Column(
            modifier =
                Modifier
                    .widthIn(min = 64.dp, max = 80.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = hasMenu) { menuExpanded = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(modifier = Modifier.size(64.dp)) {
                Box(
                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(containerColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text =
                            user.username
                                .firstOrNull()
                                ?.uppercaseChar()
                                ?.toString()
                                .orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = contentColor,
                    )
                }
                if (isHost) {
                    HostCrownBadge(modifier = Modifier.align(Alignment.BottomEnd))
                }
            }
            Text(
                text = user.username,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            when {
                isHost && user.isConnected -> {
                    Text(
                        text = stringResource(Res.string.listen_together_host),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                !user.isConnected -> {
                    Text(
                        text = stringResource(Res.string.listen_together_disconnected),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Text(
                text = user.username,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (showKick) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.kick_user)) },
                    leadingIcon = { Icon(MetrolistIcons.AutoMirrored.Filled.Logout, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onKick()
                    },
                )
            }
            if (showBlock) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.block)) },
                    leadingIcon = { Icon(MetrolistIcons.Outlined.Block, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onBlock()
                    },
                )
            }
        }
    }
}

@Composable
private fun HostCrownBadge(modifier: Modifier = Modifier) {
    val ringColor = MaterialTheme.colorScheme.surfaceContainerLowest
    val chipColor = MaterialTheme.colorScheme.primaryContainer
    val crownColor = MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier =
            modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(ringColor)
                .padding(2.dp)
                .clip(CircleShape)
                .background(chipColor),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(11.dp)) {
            val w = size.width
            val h = size.height
            val crown =
                Path().apply {
                    moveTo(0f, h * 0.9f)
                    lineTo(0f, h * 0.2f)
                    lineTo(w * 0.28f, h * 0.5f)
                    lineTo(w * 0.5f, 0f)
                    lineTo(w * 0.72f, h * 0.5f)
                    lineTo(w, h * 0.2f)
                    lineTo(w, h * 0.9f)
                    close()
                }
            drawPath(crown, crownColor)
        }
    }
}

@Composable
private fun PendingJoinRequestRow(
    request: ListenTogetherJoinRequest,
    enabled: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    PendingActionRow(
        enabled = enabled,
        leading = { AvatarLetter(request.username) },
        title = request.username,
        subtitle = null,
        onApprove = onApprove,
        onReject = onReject,
    )
}

@Composable
private fun PendingSuggestionRow(
    suggestion: ListenTogetherSuggestion,
    enabled: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    PendingActionRow(
        enabled = enabled,
        leading = { TrackThumb(suggestion.trackInfo) },
        title = suggestion.trackInfo.title,
        subtitle = suggestion.trackInfo.artist,
        onApprove = onApprove,
        onReject = onReject,
    )
}

private val PendingOuterRadius = 24.dp
private val PendingInnerRadius = 4.dp

@Composable
private fun PendingActionRow(
    enabled: Boolean,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Surface(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            shape =
                RoundedCornerShape(
                    topStart = PendingOuterRadius,
                    bottomStart = PendingOuterRadius,
                    topEnd = PendingInnerRadius,
                    bottomEnd = PendingInnerRadius,
                ),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                leading()
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        PendingActionButton(
            enabled = enabled,
            onClick = onApprove,
            icon = MetrolistIcons.Filled.Check,
            contentDescription = stringResource(Res.string.approve),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(PendingInnerRadius),
        )
        PendingActionButton(
            enabled = enabled,
            onClick = onReject,
            icon = MetrolistIcons.Filled.Close,
            contentDescription = stringResource(Res.string.reject),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape =
                RoundedCornerShape(
                    topStart = PendingInnerRadius,
                    bottomStart = PendingInnerRadius,
                    topEnd = PendingOuterRadius,
                    bottomEnd = PendingOuterRadius,
                ),
        )
    }
}

@Composable
private fun PendingActionButton(
    enabled: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    containerColor: Color,
    contentColor: Color,
    shape: Shape,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(56.dp).fillMaxHeight(),
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun AvatarLetter(text: String) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text
                .firstOrNull()
                ?.uppercaseChar()
                ?.toString()
                .orEmpty(),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun TrackThumb(trackInfo: TrackInfo) {
    Box(
        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            trackInfo.title
                .firstOrNull()
                ?.uppercaseChar()
                ?.toString()
                .orEmpty(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServerRow(
    server: ListenTogetherServer,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(server.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${server.location} -\u00a0${server.operator}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                server.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        onEdit?.let {
            IconButton(onClick = it, shapes = IconButtonDefaults.shapes()) {
                Icon(MetrolistIcons.Default.Edit, contentDescription = stringResource(Res.string.listen_together_edit_server))
            }
        }
        onRemove?.let {
            IconButton(onClick = it, shapes = IconButtonDefaults.shapes()) {
                Icon(
                    MetrolistIcons.Default.Delete,
                    contentDescription = stringResource(Res.string.remove_server),
                )
            }
        }
    }
}

@Composable
private fun ListenTogetherTextDialog(
    title: String,
    label: String,
    value: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    var error by remember(value) { mutableStateOf<String?>(null) }
    val requiredError = stringResource(Res.string.required)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.verticalScrollWithDrag(rememberScrollState()).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text(label) },
                    isError = error != null,
                    supportingText =
                        error?.let {
                            {
                                Text(
                                    text = it,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                        },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
                    Button(
                        onClick = {
                            if (text.isBlank()) {
                                error = requiredError
                            } else {
                                onConfirm(text.trim())
                            }
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(confirmText) }
                }
            }
        }
    }
}

@Composable
private fun ListenTogetherUnavailable(
    onNavigateBack: (() -> Unit)?,
    bottomInset: Dp,
) {
    SettingsSubScreen(
        title = stringResource(Res.string.listen_together),
        onNavigateBack = onNavigateBack,
        bottomInset = bottomInset,
    ) {
        SettingsSubScreenGroup {
            Text(stringResource(Res.string.listen_together_not_configured), modifier = Modifier.padding(24.dp))
        }
    }
}

@Composable
internal fun listenTogetherPresentationMessages(): Map<ListenTogetherText, String> =
    mapOf(
        ListenTogetherText.CONNECTION_FAILED to stringResource(Res.string.scrobble_connection_failed),
        ListenTogetherText.ROOM_CREATED to stringResource(Res.string.listen_together_log_room_created),
        ListenTogetherText.JOINED_ROOM to stringResource(Res.string.listen_together_log_joined_room),
        ListenTogetherText.JOIN_REJECTED to stringResource(Res.string.listen_together_log_join_rejected),
        ListenTogetherText.KICKED to stringResource(Res.string.listen_together_log_kicked_from_room),
        ListenTogetherText.SERVER_ERROR to stringResource(Res.string.listen_together_log_server_error),
        ListenTogetherText.BLOCKED_BY_HOST to stringResource(Res.string.listen_together_blocked_by_host),
        ListenTogetherText.BUFFER_WAIT to stringResource(Res.string.listen_together_log_waiting_for_buffers),
        ListenTogetherText.HOST_CHANGED to stringResource(Res.string.listen_together_log_host_changed),
        ListenTogetherText.USER_JOINED to stringResource(Res.string.listen_together_log_user_joined),
        ListenTogetherText.USER_LEFT to stringResource(Res.string.listen_together_log_user_left),
        ListenTogetherText.RECONNECTED to stringResource(Res.string.listen_together_log_reconnected_to_room),
        ListenTogetherText.SUGGESTION_SUBMITTED to stringResource(Res.string.listen_together_suggestion_submitted),
        ListenTogetherText.SUGGESTION_APPROVED to stringResource(Res.string.listen_together_suggestion_approved),
        ListenTogetherText.SUGGESTION_REJECTED to stringResource(Res.string.listen_together_suggestion_rejected),
        ListenTogetherText.QUEUE_TOO_LARGE to stringResource(Res.string.listen_together_queue_too_large),
        ListenTogetherText.TRACK_LOAD_FAILED to stringResource(Res.string.listen_together_track_load_failed),
        ListenTogetherText.BECAME_HOST to stringResource(Res.string.listen_together_became_host),
        ListenTogetherText.SUGGESTION_RECEIVED to stringResource(Res.string.listen_together_log_suggestion_received),
    )

@Composable
fun ListenTogetherGlobalEvents(
    manager: ListenTogetherManager,
    onMessage: suspend (String) -> Unit,
) {
    val autoApproved by rememberUpdatedState(stringResource(Res.string.listen_together_suggestion_auto_approved))
    val hostChanged by rememberUpdatedState(stringResource(Res.string.listen_together_log_host_changed))
    val becameHost by rememberUpdatedState(stringResource(Res.string.listen_together_became_host))
    val currentOnMessage by rememberUpdatedState(onMessage)
    LaunchedEffect(manager) {
        manager.events.collect { event ->
            if (event is ListenTogetherEvent.SuggestionAutoApproved) {
                currentOnMessage(autoApproved)
            } else if (event is ListenTogetherEvent.Notice) {
                when (event.key) {
                    ListenTogetherText.HOST_CHANGED -> currentOnMessage(hostChanged)
                    ListenTogetherText.BECAME_HOST -> currentOnMessage(becameHost)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ListenTogetherEvents(
    manager: ListenTogetherManager,
    onMessage: (message: String, isFailure: Boolean) -> Unit,
) {
    val messages = listenTogetherPresentationMessages()
    val roomCode =
        manager.roomState
            .collectAsState()
            .value
            ?.roomCode
    val roomCreatedMessage = roomCode?.let { stringResource(Res.string.listen_together_room_created, it) }
    val blockedByHostMessage = stringResource(Res.string.listen_together_blocked_by_host)
    val userIsBlockedMessage = stringResource(Res.string.listen_together_you_are_blocked)
    val currentMessages by rememberUpdatedState(messages)
    val currentRoomCreatedMessage by rememberUpdatedState(roomCreatedMessage)
    val currentBlockedByHostMessage by rememberUpdatedState(blockedByHostMessage)
    val currentUserIsBlockedMessage by rememberUpdatedState(userIsBlockedMessage)
    val currentOnMessage by rememberUpdatedState(onMessage)

    LaunchedEffect(manager) {
        manager.events.collect { event ->
            when (event) {
                is ListenTogetherEvent.Notice -> {
                    if (event.key == ListenTogetherText.HOST_CHANGED || event.key == ListenTogetherText.BECAME_HOST) return@collect
                    currentOnMessage(
                        if (event.key == ListenTogetherText.ROOM_CREATED) {
                            currentRoomCreatedMessage ?: currentMessages.getValue(event.key)
                        } else {
                            currentMessages.getValue(event.key)
                        },
                        false,
                    )
                }

                is ListenTogetherEvent.Failure -> {
                    val message =
                        when (event.reason) {
                            RejectionReasons.BLOCKED_BY_HOST -> currentBlockedByHostMessage
                            RejectionReasons.USER_IS_BLOCKED -> currentUserIsBlockedMessage
                            else -> currentMessages.getValue(event.key)
                        }
                    currentOnMessage(message, true)
                }

                is ListenTogetherEvent.SuggestionAutoApproved,
                is ListenTogetherEvent.Reconnecting,
                -> {
                    return@collect
                }
            }
        }
    }
}

private fun List<ListenTogetherLogEntry>.toShareText(): String =
    joinToString(separator = "\n") { entry ->
        buildString {
            append(entry.timestamp)
            append(" - ")
            append(entry.level.name)
            append(": ")
            append(entry.message)
        }
    }
