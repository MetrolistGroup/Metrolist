/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.PreferenceEntry
import com.metrolist.music.ui.component.PreferenceGroupTitle
import com.metrolist.music.ui.component.SwitchPreference
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.ListenTogetherViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTogetherSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ListenTogetherViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val connectionState by viewModel.connectionState.collectAsState()
    val roomState by viewModel.roomState.collectAsState()
    val role by viewModel.role.collectAsState()
    val pendingJoinRequests by viewModel.pendingJoinRequests.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val blockedUsernames by viewModel.blockedUsernames.collectAsState()
    
    val servers = remember { ListenTogetherServers.servers }
    var serverUrl by rememberPreference(ListenTogetherServerUrlKey, ListenTogetherServers.defaultServerUrl)
    var username by rememberPreference(ListenTogetherUsernameKey, "")
    var autoApproval by rememberPreference(ListenTogetherAutoApprovalKey, false)
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
            onDismiss = { showServerUrlDialog = false }
        )
    }
    
    if (showUsernameDialog) {
        var tempUsername by rememberSaveable(showUsernameDialog) { mutableStateOf(username) }

        AlertDialog(
            onDismissRequest = { showUsernameDialog = false },
            title = { Text(stringResource(R.string.listen_together_username)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { username = tempUsername.trim(); showUsernameDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { username = ""; showUsernameDialog = false }) {
                    Text(stringResource(R.string.reset))
                }
            }
        )
    }
    
    if (showCreateRoomDialog) {
        var createUsername by rememberSaveable(showCreateRoomDialog) { mutableStateOf(username) }

        AlertDialog(
            onDismissRequest = { showCreateRoomDialog = false },
            title = { Text(stringResource(R.string.listen_together_create_room)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.listen_together_create_room_desc))
                    OutlinedTextField(
                        value = createUsername,
                        onValueChange = { createUsername = it },
                        label = { Text(stringResource(R.string.listen_together_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
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
                    enabled = createUsername.trim().isNotBlank()
                ) {
                    Text(stringResource(R.string.create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateRoomDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
    
    if (showJoinRoomDialog) {
        var joinUsername by rememberSaveable(showJoinRoomDialog) { mutableStateOf(username) }

        AlertDialog(
            onDismissRequest = { showJoinRoomDialog = false },
            title = { Text(stringResource(R.string.listen_together_join_room)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = joinUsername,
                        onValueChange = { joinUsername = it },
                        label = { Text(stringResource(R.string.listen_together_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = roomCodeInput,
                        onValueChange = { roomCodeInput = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8) },
                        label = { Text(stringResource(R.string.listen_together_room_code)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
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
                    enabled = joinUsername.trim().isNotBlank() && roomCodeInput.length == 8
                ) {
                    Text(stringResource(R.string.join))
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoinRoomDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
    
    if (showLogsDialog) {
        LogsDialog(
            logs = logs,
            onClear = { viewModel.clearLogs() },
            onDismiss = { showLogsDialog = false }
        )
    }

    if (showBlockedUsersDialog) {
        BlockedUsersDialog(
            blockedUsernames = blockedUsernames,
            onUnblock = { viewModel.unblockUser(it) },
            onDismiss = { showBlockedUsersDialog = false }
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
            )
        )
        
        // Connection status card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (connectionState) {
                    ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.secondaryContainer
                    ConnectionState.ERROR -> MaterialTheme.colorScheme.errorContainer
                    ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = when (connectionState) {
                        ConnectionState.CONNECTED -> stringResource(R.string.listen_together_connected)
                        ConnectionState.CONNECTING -> stringResource(R.string.listen_together_connecting)
                        ConnectionState.RECONNECTING -> stringResource(R.string.listen_together_reconnecting)
                        ConnectionState.ERROR -> stringResource(R.string.listen_together_error)
                        ConnectionState.DISCONNECTED -> stringResource(R.string.listen_together_disconnected)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                if (connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.RECONNECTING) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.ERROR) {
                        Button(
                            onClick = { viewModel.connect() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.connect))
                        }
                    } else {
                        Button(
                            onClick = { viewModel.disconnect() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.disconnect))
                        }
                    }
                }
            }
        }
        
        // Room status card (when in a room)
        roomState?.let { state ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (role == RoomRole.HOST) 
                        MaterialTheme.colorScheme.tertiaryContainer 
                    else 
                        MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.group),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.roomCode,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (role == RoomRole.HOST) 
                                    stringResource(R.string.listen_together_you_are_host)
                                else 
                                    stringResource(R.string.listen_together_you_are_guest),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Room Code", state.roomCode)
                                cm.setPrimaryClip(clip)
                                Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                            },
                            onLongClick = {}
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.content_copy),
                                contentDescription = stringResource(R.string.copy)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Users in room (filter out disconnected users in grace period)
                    val connectedUsers = state.users.filter { it.isConnected }
                    Text(
                        text = stringResource(R.string.listen_together_users, connectedUsers.size),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        connectedUsers.forEach { user ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (user.isHost) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.surface,
                                tonalElevation = 2.dp
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    if (user.isHost) {
                                        Icon(
                                            painter = painterResource(R.drawable.star),
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = user.username.ifEmpty { "User" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (user.isHost)
                                            MaterialTheme.colorScheme.onPrimary
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Leave room button
                    OutlinedButton(
                        onClick = { viewModel.leaveRoom() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.logout),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.listen_together_leave_room))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Pending join requests (host only)
            if (role == RoomRole.HOST && pendingJoinRequests.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.listen_together_join_requests),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        pendingJoinRequests.forEach { request ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = request.username,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                FilledTonalButton(
                                    onClick = { viewModel.approveJoin(request.userId) },
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) {
                                    Text(stringResource(R.string.approve))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                OutlinedButton(
                                    onClick = { viewModel.rejectJoin(request.userId) },
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) {
                                    Text(stringResource(R.string.reject))
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Room actions (when connected but not in a room)
        if (connectionState == ConnectionState.CONNECTED && roomState == null) {
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Button(
                    onClick = { showCreateRoomDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.add),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.listen_together_create_room))
                }
                
                OutlinedButton(
                    onClick = { showJoinRoomDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.group_add),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.listen_together_join_room))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.listen_together_background_disconnect_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        
        // Settings section
        PreferenceGroupTitle(
            title = stringResource(R.string.settings)
        )
        
        val selectedServer = remember(serverUrl) { ListenTogetherServers.findByUrl(serverUrl) }

        PreferenceEntry(
            title = { Text(stringResource(R.string.listen_together_blocked_users)) },
            description = if (blockedUsernames.isNotEmpty()) 
                stringResource(R.string.listen_together_blocked_users_count, blockedUsernames.size)
            else 
                stringResource(R.string.listen_together_no_blocked_users),
            icon = { Icon(painterResource(R.drawable.person), null) },
            onClick = { if (blockedUsernames.isNotEmpty()) showBlockedUsersDialog = true },
            isEnabled = blockedUsernames.isNotEmpty()
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.listen_together_server_url)) },
            description = selectedServer?.let { server ->
                "${server.name} - ${server.location} - ${server.operator}\n${server.url}"
            } ?: serverUrl,
            icon = { Icon(painterResource(R.drawable.cloud), null) },
            onClick = { showServerUrlDialog = true }
        )
        
        PreferenceEntry(
            title = { Text(stringResource(R.string.listen_together_username)) },
            description = username.ifEmpty { stringResource(R.string.not_set) },
            icon = { Icon(painterResource(R.drawable.person), null) },
            onClick = {
                if (roomState == null) {
                    showUsernameDialog = true
                } else {
                    Toast.makeText(context, context.getString(R.string.listen_together_cannot_edit_username_in_room), Toast.LENGTH_SHORT).show()
                }
            },
            isEnabled = roomState == null
        )
        
        SwitchPreference(
            title = { Text(stringResource(R.string.listen_together_auto_approval)) },
            description = stringResource(R.string.listen_together_auto_approval_desc),
            icon = { Icon(painterResource(R.drawable.done), null) },
            checked = autoApproval,
            onCheckedChange = { autoApproval = it },
            isEnabled = roomState == null || role != RoomRole.GUEST
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.listen_together_sync_volume)) },
            description = stringResource(R.string.listen_together_sync_volume_desc),
            icon = { Icon(painterResource(R.drawable.volume_up), null) },
            checked = syncHostVolume,
            onCheckedChange = { syncHostVolume = it }
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.listen_together_view_logs)) },
            description = stringResource(R.string.listen_together_view_logs_desc),
            icon = { Icon(painterResource(R.drawable.bug_report), null) },
            onClick = { showLogsDialog = true }
        )
        
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
        }
    )
}

@Composable
fun LogsDialog(
    logs: List<LogEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }
    
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.listen_together_logs)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.listen_together_no_logs),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(logs) { log ->
                            LogEntryItem(log)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        val textToCopy = logs.joinToString("\n") { log ->
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
                    enabled = logs.isNotEmpty()
                ) {
                    Text(stringResource(R.string.copy))
                }

                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.clear))
                }
            }
        }
    )
}

@Composable
private fun ServerChooserDialog(
    servers: List<ListenTogetherServer>,
    currentUrl: String,
    onSelect: (ListenTogetherServer) -> Unit,
    onUseCustom: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var customUrl by rememberSaveable(currentUrl) { mutableStateOf(currentUrl) }
    val trimmedCustomUrl = customUrl.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.listen_together_choose_server)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                servers.forEach { server ->
                    val isSelected = server.url == currentUrl
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(server) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = server.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${server.location} - ${server.operator}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = server.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    painter = painterResource(R.drawable.done),
                                    contentDescription = null
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    text = stringResource(R.string.listen_together_custom_server),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text(stringResource(R.string.listen_together_server_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onUseCustom(trimmedCustomUrl) },
                    enabled = trimmedCustomUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.listen_together_use_custom_server))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
fun LogEntryItem(log: LogEntry) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = log.timestamp,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = when (log.level) {
                    LogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
                    LogLevel.WARNING -> Color(0xFFFFF3CD)
                    LogLevel.DEBUG -> MaterialTheme.colorScheme.surfaceVariant
                    LogLevel.INFO -> MaterialTheme.colorScheme.primaryContainer
                }
            ) {
                Text(
                    text = log.level.name,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    color = when (log.level) {
                        LogLevel.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                        LogLevel.WARNING -> Color(0xFF856404)
                        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                        LogLevel.INFO -> MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
            }
        }

        Text(
            text = log.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
        log.details?.let { details ->
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun BlockedUsersDialog(
    blockedUsernames: Set<String>,
    onUnblock: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.listen_together_blocked_users)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                if (blockedUsernames.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.listen_together_no_blocked_users),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(blockedUsernames.toList()) { username ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = username,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { onUnblock(username) },
                                    modifier = Modifier.padding(0.dp)
                                ) {
                                    Text(stringResource(R.string.unblock))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

