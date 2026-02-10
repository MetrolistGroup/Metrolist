/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import com.metrolist.music.listentogether.ListenTogetherManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ListenTogetherViewModel @Inject constructor(
    private val manager: ListenTogetherManager
) : ViewModel() {

    val connectionState = manager.connectionState
    val roomState = manager.roomState
    val role = manager.role
    val userId = manager.userId
    val pendingJoinRequests = manager.pendingJoinRequests
    val bufferingUsers = manager.bufferingUsers
    val logs = manager.logs
    val events = manager.events
    val hasPersistedSession = manager.hasPersistedSession
    val blockedUsernames = manager.blockedUsernames

    init {
        manager.initialize()
    }

    fun connect() {
        manager.connect()
    }

    fun disconnect() {
        manager.disconnect()
    }

    fun createRoom(username: String) {
        manager.createRoom(username)
    }

    fun joinRoom(roomCode: String, username: String) {
        manager.joinRoom(roomCode, username)
    }

    fun leaveRoom() {
        manager.leaveRoom()
    }

    fun approveJoin(userId: String) {
        manager.approveJoin(userId)
    }

    fun rejectJoin(userId: String, reason: String? = null) {
        manager.rejectJoin(userId, reason)
    }

    fun kickUser(userId: String, reason: String? = null) {
        manager.kickUser(userId, reason)
    }

    fun blockUser(username: String) {
        manager.blockUser(username)
    }

    fun unblockUser(username: String) {
        manager.unblockUser(username)
    }

    fun clearLogs() {
        manager.clearLogs()
    }

    fun forceReconnect() {
        manager.forceReconnect()
    }
    
    fun reconnect() {
        manager.forceReconnect()
    }
    
    fun getPersistedRoomCode(): String? = manager.getPersistedRoomCode()
    
    fun getSessionAge(): Long = manager.getSessionAge()
}
