package com.metrolist.shared.listentogether.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListenTogetherServerUrlPolicyTest {
    @Test
    fun allowsEncryptedRemoteServers() {
        assertTrue(isAllowedListenTogetherServerUrl("wss://example.com/ws"))
    }

    @Test
    fun rejectsAllCleartextServers() {
        assertFalse(isAllowedListenTogetherServerUrl("ws://127.0.0.1:8080/ws"))
        assertFalse(isAllowedListenTogetherServerUrl("ws://192.168.1.10/ws"))
        assertFalse(isAllowedListenTogetherServerUrl("ws://[::1]:8080/ws"))
        assertFalse(isAllowedListenTogetherServerUrl("ws://example.com/ws"))
        assertFalse(isAllowedListenTogetherServerUrl("ws://8.8.8.8/ws"))
    }

    @Test
    fun rejectsInvalidPortsAndSchemes() {
        assertFalse(isAllowedListenTogetherServerUrl("https://example.com/ws"))
        assertFalse(isAllowedListenTogetherServerUrl("wss://example.com:99999/ws"))
    }
}
