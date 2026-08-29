/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.ListenTogetherAutoApprovalKey
import com.metrolist.music.constants.ListenTogetherAutoApproveSuggestionsKey
import com.metrolist.music.constants.ListenTogetherServerUrlKey
import com.metrolist.music.constants.ListenTogetherSyncVolumeKey
import com.metrolist.music.constants.ListenTogetherUsernameKey
import com.metrolist.music.listentogether.ConnectionState
import com.metrolist.music.listentogether.ListenTogetherEvent
import com.metrolist.music.listentogether.ListenTogetherServer
import com.metrolist.music.listentogether.ListenTogetherServers
import com.metrolist.music.listentogether.LogEntry
import com.metrolist.music.listentogether.LogLevel
import com.metrolist.music.listentogether.RoomRole
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.IntegrationCard
import com.metrolist.music.ui.component.IntegrationCardItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.ListenTogetherViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTogetherSettings(
    navController: NavController,
    viewModel: ListenTogetherViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val cannotEditUsernameInRoomStr = stringResource(R.string.listen_together_cannot_edit_username_in_room)
    val coroutineScope = rememberCoroutineScope()

    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val roomState by viewModel.roomState.collectAsStateWithLifecycle()
    val role by viewModel.role.collectAsStateWithLifecycle()
    val pendingJoinRequests by viewModel.pendingJoinRequests.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val blockedUsernames by viewModel.blockedUsernames.collectAsStateWithLifecycle()

    val servers = remember { ListenTogetherServers.servers }
    var serverUrl by rememberPreference(ListenTogetherServerUrlKey, ListenTogetherServers.defaultServerUrl)
    var username by rememberPreference(ListenTogetherUsernameKey, "")
    var autoApprovalJoins by rememberPreference(ListenTogetherAutoApprovalKey, false)
    var autoApproveSuggestions by rememberPreference(ListenTogetherAutoApproveSuggestionsKey, false)
    var syncHostVolume by rememberPreference(ListenTogetherSyncVolumeKey, true)

    var showServerUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showUsernameDialog by rememberSaveable { mutableStateOf(false) }
    var showCreateRoomDialog by rememberSaveable { mutableStateOf(false) }
    var showJoinRoomDialog by rememberSaveable { mutableStateOf(false) }
    var showLogsDialog by rememberSaveable { mutableStateOf(false) }
    var showBlockedUsersDialog by rememberSaveable { mutableStateOf(false) }
    var roomCodeInput by rememberSaveable { mutableStateOf("") }

    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is ListenTogetherEvent.RoomCreated -> {
                    // Room created toast is shown globally by the client
                }

                is ListenTogetherEvent.JoinApproved -> {
                    Toast.makeText(context, "Joined room: ${event.roomCode}", Toast.LENGTH_SHORT).show()
                }

                is ListenTogetherEvent.JoinRejected -> {
                    Toast.makeText(context, "Join rejected: ${event.reason}", Toast.LENGTH_SHORT).show()
                }

                is ListenTogetherEvent.JoinRequestReceived -> {
                    Toast.makeText(context, "${event.username} wants to join", Toast.LENGTH_SHORT).show()
                }

                is ListenTogetherEvent.Kicked -> {
                    Toast.makeText(context, "Kicked: ${event.reason}", Toast.LENGTH_SHORT).show()
                }

                is ListenTogetherEvent.ConnectionError -> {
                    Toast.makeText(context, "Connection error: ${event.error}", Toast.LENGTH_SHORT).show()
                }

                is ListenTogetherEvent.ServerError -> {
                    Toast.makeText(context, "Error: ${event.message}", Toast.LENGTH_SHORT).show()
                }

                else -> {}
            }
        }
    }

    // Dialogs
    if (showServerUrlDialog) {
        ServerChooserDialog(
            servers = servers,
            currentUrl = serverUrl,
            onSelect = { server ->
                serverUrl = server.url
                showServerUrlDialog = false
            },
            onUseCustom = { customUrl ->
                serverUrl = customUrl
                showServerUrlDialog = false
            },
            onDismiss = { showServerUrlDialog = false },
        )
    }

    if (showUsernameDialog) {
        var tempUsername by rememberSaveable(showUsernameDialog) { mutableStateOf(username) }

        DefaultDialog(
            onDismiss = { showUsernameDialog = false },
            icon = { Icon(painterResource(R.drawable.person), contentDescription = null) },
            title = { Text(stringResource(R.string.listen_together_username)) },
            buttons = {
                TextButton(onClick = {
                    username = ""
                    showUsernameDialog = false
                }) {
                    Text(stringResource(R.string.reset))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    username = tempUsername.trim()
                    showUsernameDialog = false
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        ) {
            OutlinedTextField(
                value = tempUsername,
                onValueChange = { tempUsername = it },
                label = { Text(stringResource(R.string.listen_together_username)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.person), contentDescription = null)
                },
                trailingIcon = {
                    if (tempUsername.isNotBlank()) {
                        IconButton(onClick = { tempUsername = "" }, onLongClick = {}) {
                            Icon(painterResource(R.drawable.close), contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showCreateRoomDialog) {
        var createUsername by rememberSaveable(showCreateRoomDialog) { mutableStateOf(username) }

        DefaultDialog(
            onDismiss = { showCreateRoomDialog = false },
            icon = { Icon(painterResource(R.drawable.add), contentDescription = null) },
            title = { Text(stringResource(R.string.listen_together_create_room)) },
            buttons = {
                TextButton(onClick = { showCreateRoomDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val finalUsername = createUsername.trim()
                        if (finalUsername.isNotBlank()) {
                            username = finalUsername
                            viewModel.createRoom(finalUsername)
                            showCreateRoomDialog = false
                        } else {
                            Toast.makeText(context, R.string.error_username_empty, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = createUsername.trim().isNotBlank(),
                ) {
                    Text(stringResource(R.string.create))
                }
            },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.listen_together_create_room_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = createUsername,
                    onValueChange = { createUsername = it },
                    label = { Text(stringResource(R.string.listen_together_username)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.person), contentDescription = null)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showJoinRoomDialog) {
        var joinUsername by rememberSaveable(showJoinRoomDialog) { mutableStateOf(username) }

        DefaultDialog(
            onDismiss = { showJoinRoomDialog = false },
            icon = { Icon(painterResource(R.drawable.group_add), contentDescription = null) },
            title = { Text(stringResource(R.string.listen_together_join_room)) },
            buttons = {
                TextButton(onClick = { showJoinRoomDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val finalUsername = joinUsername.trim()
                        if (finalUsername.isNotBlank() && roomCodeInput.length == 8) {
                            username = finalUsername
                            viewModel.joinRoom(roomCodeInput, finalUsername)
                            showJoinRoomDialog = false
                            roomCodeInput = ""
                        } else {
                            Toast.makeText(context, R.string.error_username_empty, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = joinUsername.trim().isNotBlank() && roomCodeInput.length == 8,
                ) {
                    Text(stringResource(R.string.join))
                }
            },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = joinUsername,
                    onValueChange = { joinUsername = it },
                    label = { Text(stringResource(R.string.listen_together_username)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.person), contentDescription = null)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = roomCodeInput,
                    onValueChange = { roomCodeInput = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8) },
                    label = { Text(stringResource(R.string.listen_together_room_code)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.key), contentDescription = null)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showLogsDialog) {
        LogsDialog(
            logs = logs,
            onClear = { viewModel.clearLogs() },
            onDismiss = { showLogsDialog = false },
        )
    }

    if (showBlockedUsersDialog) {
        BlockedUsersDialog(
            blockedUsernames = blockedUsernames,
            onUnblock = { viewModel.unblockUser(it) },
            onDismiss = { showBlockedUsersDialog = false },
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        // Settings section using IntegrationCard
        val selectedServer = remember(serverUrl) { ListenTogetherServers.findByUrl(serverUrl) }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            IntegrationCard(
                title = stringResource(R.string.settings),
                items =
                    listOf(
                        IntegrationCardItem(
                            icon = painterResource(R.drawable.person),
                            title = { Text(stringResource(R.string.listen_together_blocked_users)) },
                            description = {
                                Text(
                                    if (blockedUsernames.isNotEmpty()) {
                                        stringResource(R.string.listen_together_blocked_users_count, blockedUsernames.size)
                                    } else {
                                        stringResource(R.string.listen_together_no_blocked_users)
                                    },
                                )
                            },
                            onClick =
                                if (blockedUsernames.isNotEmpty()) {
                                    { showBlockedUsersDialog = true }
                                } else {
                                    null
                                },
                        ),
                        IntegrationCardItem(
                            icon = painterResource(R.drawable.cloud),
                            title = { Text(stringResource(R.string.listen_together_server_url)) },
                            description = {
                                Text(
                                    selectedServer?.let { server ->
                                        "${server.name} - ${server.location}"
                                    } ?: serverUrl,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = { showServerUrlDialog = true },
                        ),
                        IntegrationCardItem(
                            icon = painterResource(R.drawable.person),
                            title = { Text(stringResource(R.string.listen_together_username)) },
                            description = {
                                Text(username.ifEmpty { stringResource(R.string.not_set) })
                            },
                            onClick =
                                if (roomState == null) {
                                    { showUsernameDialog = true }
                                } else {
                                    {
                                        Toast
                                            .makeText(
                                                context,
                                                cannotEditUsernameInRoomStr,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                },
                        ),
                        IntegrationCardItem(
                            icon = painterResource(R.drawable.done),
                            title = { Text(stringResource(R.string.listen_together_auto_approval_joins)) },
                            description = {
                                Text(stringResource(R.string.listen_together_auto_approval_joins_desc))
                            },
                            trailingContent = {
                                Switch(
                                    checked = autoApprovalJoins,
                                    onCheckedChange = { autoApprovalJoins = it },
                                    // Only disable for guests in a room (hosts can always change)
                                    enabled = roomState == null || role != RoomRole.GUEST,
                                    thumbContent = {
                                        Icon(
                                            painter =
                                                painterResource(
                                                    id = if (autoApprovalJoins) R.drawable.check else R.drawable.close,
                                                ),
                                            contentDescription = null,
                                            modifier = Modifier.size(SwitchDefaults.IconSize),
                                        )
                                    },
                                )
                            },
                            // Allow clicking to see disabled state, but only change if enabled
                            onClick = { if (roomState == null || role != RoomRole.GUEST) autoApprovalJoins = !autoApprovalJoins },
                        ),
                        IntegrationCardItem(
                            icon = painterResource(R.drawable.done),
                            title = { Text(stringResource(R.string.listen_together_auto_approval_suggestions)) },
                            description = {
                                Text(stringResource(R.string.listen_together_auto_approval_suggestions_desc))
                            },
                            trailingContent = {
                                Switch(
                                    checked = autoApproveSuggestions,
                                    onCheckedChange = { autoApproveSuggestions = it },
                                    // Only disable for guests in a room (hosts can always change)
                                    enabled = roomState == null || role != RoomRole.GUEST,
                                    thumbContent = {
                                        Icon(
                                            painter =
                                                painterResource(
                                                    id = if (autoApproveSuggestions) R.drawable.check else R.drawable.close,
                                                ),
                                            contentDescription = null,
                                            modifier = Modifier.size(SwitchDefaults.IconSize),
                                        )
                                    },
                                )
                            },
                            // Allow clicking to see disabled state, but only change if enabled
                            onClick = { if (roomState == null || role != RoomRole.GUEST) autoApproveSuggestions = !autoApproveSuggestions },
                        ),
                        IntegrationCardItem(
                            icon = painterResource(R.drawable.volume_up),
                            title = { Text(stringResource(R.string.listen_together_sync_volume)) },
                            description = {
                                Text(stringResource(R.string.listen_together_sync_volume_desc))
                            },
                            trailingContent = {
                                Switch(
                                    checked = syncHostVolume,
                                    onCheckedChange = { syncHostVolume = it },
                                    thumbContent = {
                                        Icon(
                                            painter =
                                                painterResource(
                                                    id = if (syncHostVolume) R.drawable.check else R.drawable.close,
                                                ),
                                            contentDescription = null,
                                            modifier = Modifier.size(SwitchDefaults.IconSize),
                                        )
                                    },
                                )
                            },
                            onClick = { syncHostVolume = !syncHostVolume },
                        ),
                        IntegrationCardItem(
                            icon = painterResource(R.drawable.bug_report),
                            title = { Text(stringResource(R.string.listen_together_view_logs)) },
                            description = {
                                Text(stringResource(R.string.listen_together_view_logs_desc))
                            },
                            onClick = { showLogsDialog = true },
                        ),
                    ),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.listen_together)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

@Composable
fun LogsDialog(
    logs: List<LogEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    val context = LocalContext.current

    DefaultDialog(
        onDismiss = onDismiss,
        icon = { Icon(painterResource(R.drawable.bug_report), contentDescription = null) },
        title = { Text(stringResource(R.string.listen_together_logs)) },
        buttons = {
            TextButton(
                onClick = {
                    val textToCopy =
                        logs.joinToString("\n") { log ->
                            buildString {
                                append(log.timestamp)
                                append(" [")
                                append(log.level.name)
                                append("] ")
                                append(log.message)
                                log.details?.let { d -> append(" -- $d") }
                            }
                        }
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("ListenTogetherLogs", textToCopy)
                    cm.setPrimaryClip(clip)
                    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                },
                enabled = logs.isNotEmpty(),
            ) {
                Text(stringResource(R.string.copy))
            }
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.clear))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(350.dp),
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.listen_together_no_logs),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(logs) { log ->
                        LogEntryItem(log)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerChooserDialog(
    servers: List<ListenTogetherServer>,
    currentUrl: String,
    onSelect: (ListenTogetherServer) -> Unit,
    onUseCustom: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var customUrl by rememberSaveable(currentUrl) { mutableStateOf(currentUrl) }
    val trimmedCustomUrl = customUrl.trim()

    DefaultDialog(
        onDismiss = onDismiss,
        icon = { Icon(painterResource(R.drawable.cloud), contentDescription = null) },
        title = { Text(stringResource(R.string.listen_together_choose_server)) },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            servers.forEach { server ->
                val isSelected = server.url == currentUrl
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(server) },
                    shape = RoundedCornerShape(16.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                        ),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = server.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "${server.location} - ${server.operator}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = server.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isSelected) {
                            Icon(
                                painter = painterResource(R.drawable.done),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.listen_together_custom_server),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                label = { Text(stringResource(R.string.listen_together_server_url)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.link), contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onUseCustom(trimmedCustomUrl) },
                enabled = trimmedCustomUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.listen_together_use_custom_server))
            }
        }
    }
}

@Composable
fun LogEntryItem(log: LogEntry) {
    val context = LocalContext.current

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = log.timestamp,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color =
                    when (log.level) {
                        LogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
                        LogLevel.WARNING -> Color(0xFFFFF3CD)
                        LogLevel.DEBUG -> MaterialTheme.colorScheme.surfaceVariant
                        LogLevel.INFO -> MaterialTheme.colorScheme.primaryContainer
                    },
            ) {
                Text(
                    text = log.level.name,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    color =
                        when (log.level) {
                            LogLevel.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                            LogLevel.WARNING -> Color(0xFF856404)
                            LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                            LogLevel.INFO -> MaterialTheme.colorScheme.onPrimaryContainer
                        },
                )
            }
        }

        Text(
            text = log.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        log.details?.let { details ->
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun BlockedUsersDialog(
    blockedUsernames: Set<String>,
    onUnblock: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()

    DefaultDialog(
        onDismiss = onDismiss,
        icon = { Icon(painterResource(R.drawable.person), contentDescription = null) },
        title = { Text(stringResource(R.string.listen_together_blocked_users)) },
        buttons = {
            Button(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(280.dp),
        ) {
            if (blockedUsernames.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.listen_together_no_blocked_users),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(blockedUsernames.toList()) { username ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.person),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = username,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            TextButton(
                                onClick = { onUnblock(username) },
                            ) {
                                Text(stringResource(R.string.unblock))
                            }
                        }
                    }
                }
            }
        }
    }
}
