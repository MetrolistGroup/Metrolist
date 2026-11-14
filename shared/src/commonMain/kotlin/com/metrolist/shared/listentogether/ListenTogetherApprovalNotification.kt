package com.metrolist.shared.listentogether

data class ListenTogetherApprovalNotification(
    val roomCode: String,
    val requestId: String,
    val isSuggestion: Boolean,
    val username: String,
    val trackTitle: String? = null,
)

fun listenTogetherApprovalNotifications(
    role: ListenTogetherRoomRole,
    roomCode: String?,
    screenVisible: Boolean,
    joins: List<ListenTogetherJoinRequest>,
    suggestions: List<ListenTogetherSuggestion>,
): List<ListenTogetherApprovalNotification> {
    if (role != ListenTogetherRoomRole.HOST || roomCode == null || screenVisible) return emptyList()
    return joins.map { ListenTogetherApprovalNotification(roomCode, it.userId, false, it.username) } +
        suggestions.map { ListenTogetherApprovalNotification(roomCode, it.id, true, it.fromUsername, it.trackInfo.title) }
}
