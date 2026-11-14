package com.metrolist.shared.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.ui.listentogether.ListenTogetherBlockedUsersScreen
import com.metrolist.shared.ui.listentogether.ListenTogetherLogsScreen
import com.metrolist.shared.ui.listentogether.ListenTogetherScreen
import com.metrolist.shared.ui.listentogether.ListenTogetherServerScreen
import com.metrolist.shared.ui.listentogether.ListenTogetherSettingsScreen
import com.metrolist.shared.ui.navigation.NavigationBackHandler

private enum class ListenTogetherPage { ROOM, SETTINGS, SERVER, BLOCKED_USERS, LOGS }

@Composable
internal fun MobileListenTogetherScreen(
    manager: ListenTogetherManager,
    onBack: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onShareText: ((String) -> Unit)?,
    onMessage: (String) -> Unit,
    bottomInset: Dp,
    initialRoomCode: String? = null,
    initialSettings: Boolean = false,
) {
    val rootPage = if (initialSettings) ListenTogetherPage.SETTINGS else ListenTogetherPage.ROOM
    var page by remember(manager, initialRoomCode, initialSettings) { mutableStateOf(rootPage) }
    val back = {
        when (page) {
            ListenTogetherPage.ROOM -> onBack()
            ListenTogetherPage.SETTINGS -> if (initialSettings) onBack() else page = ListenTogetherPage.ROOM
            else -> page = ListenTogetherPage.SETTINGS
        }
    }
    NavigationBackHandler(enabled = page != rootPage, onBack = back)
    when (page) {
        ListenTogetherPage.ROOM -> {
            ListenTogetherScreen(
                manager = manager,
                onNavigateBack = back,
                onNavigateToSettings = { page = ListenTogetherPage.SETTINGS },
                onCopyText = onCopyText,
                onEventMessage = { message, _ -> onMessage(message) },
                bottomInset = bottomInset,
                initialRoomCode = initialRoomCode,
            )
        }

        ListenTogetherPage.SETTINGS -> {
            ListenTogetherSettingsScreen(
                manager = manager,
                onNavigateBack = back,
                onNavigateToServer = { page = ListenTogetherPage.SERVER },
                onNavigateToBlockedUsers = { page = ListenTogetherPage.BLOCKED_USERS },
                onNavigateToLogs = { page = ListenTogetherPage.LOGS },
                bottomInset = bottomInset,
            )
        }

        ListenTogetherPage.SERVER -> {
            ListenTogetherServerScreen(manager, back, bottomInset)
        }

        ListenTogetherPage.BLOCKED_USERS -> {
            ListenTogetherBlockedUsersScreen(manager, back, bottomInset)
        }

        ListenTogetherPage.LOGS -> {
            ListenTogetherLogsScreen(
                manager = manager,
                onNavigateBack = back,
                onCopyText = onCopyText,
                onShareText = onShareText,
                bottomInset = bottomInset,
            )
        }
    }
}
