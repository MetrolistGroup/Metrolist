package com.metrolist.shared.discord

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordIdleGatewayTest {
    @Test
    fun savedCredentialsDoNotOpenGatewayWhenPresenceIsDisabled() =
        runTest {
            val settings =
                Settings(MapSettings()).apply {
                    enableDiscordIntegration = false
                    discordAccessToken = "saved-token"
                }
            Settings.replaceSharedForTest(settings)
            val requests = AtomicInteger()
            val client =
                HttpClient(
                    MockEngine {
                        requests.incrementAndGet()
                        error("Disabled presence must not contact Discord")
                    },
                )
            try {
                DiscordRpcManager.init(client, StandardTestDispatcher(testScheduler))
                withTimeout(5_000) { DiscordRpcManager.accessTokenFlow.first { it == "saved-token" } }
                DiscordRpcManager.reconnectWithToken("replacement-token")
                runCurrent()
                assertEquals(0, requests.get())
                assertEquals("replacement-token", DiscordRpcManager.accessTokenFlow.value)
                assertEquals(DiscordRpcManager.Status.Disconnected, DiscordRpcManager.connectionStatus.value)
            } finally {
                DiscordRpcManager.destroy()
                client.close()
                Settings.replaceSharedForTest(null)
            }
        }
}
