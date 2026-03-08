package com.metrolist.shared.discord

import androidx.compose.runtime.snapshots.Snapshot
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordGatewayLifecycleTest {
    @Test
    fun shortPausesKeepTheGatewayAndRepeatedIdleUpdatesDoNotExtendTheDeadline() =
        runTest {
            val active = MutableStateFlow(true)
            val allowed = mutableListOf<Boolean>()
            backgroundScope.launch {
                discordGatewayConnectionAllowed(MutableStateFlow(true), active, MutableStateFlow(true)).collect(allowed::add)
            }
            runCurrent()
            active.value = false
            runCurrent()
            advanceTimeBy(29_999)
            active.value = true
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(listOf(true), allowed)

            active.value = false
            runCurrent()
            advanceTimeBy(20_000)
            active.value = false
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(listOf(true, false), allowed)
            active.value = true
            runCurrent()
            assertEquals(listOf(true, false, true), allowed)
        }

    @Test
    fun offlineWaitsForConnectivityAndDoesNotReconnectAfterTheIdleDeadline() =
        runTest {
            val enabled = MutableStateFlow(true)
            val active = MutableStateFlow(true)
            val online = MutableStateFlow(false)
            val allowed = mutableListOf<Boolean>()
            backgroundScope.launch { discordGatewayConnectionAllowed(enabled, active, online).collect(allowed::add) }
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(listOf(false), allowed)
            online.value = true
            runCurrent()
            assertEquals(listOf(false, true), allowed)
            online.value = false
            active.value = false
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            online.value = true
            runCurrent()
            assertEquals(listOf(false, true, false), allowed)
            active.value = true
            runCurrent()
            enabled.value = false
            runCurrent()
            assertEquals(listOf(false, true, false, true, false), allowed)
        }

    @Test
    fun disabledIntegrationDoesNotInitializeEvenWhenSettingsAreOpen() =
        runTest {
            val settings = Settings(MapSettings()).apply { enableDiscordIntegration = false }
            val previousVisibility = discordSettingsVisible
            Settings.replaceSharedForTest(settings)
            discordSettingsVisible = false
            var initializations = 0
            val observer = backgroundScope.launch { observeDiscordInitialization { initializations++ } }
            try {
                runCurrent()
                assertEquals(0, initializations)
                discordSettingsVisible = true
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals(0, initializations)
                discordSettingsVisible = false
                Snapshot.sendApplyNotifications()
                runCurrent()
                settings.enableDiscordIntegration = true
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals(1, initializations)
                settings.enableDiscordIntegration = false
                discordSettingsVisible = true
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals(1, initializations)
            } finally {
                observer.cancelAndJoin()
                discordSettingsVisible = previousVisibility
                Settings.replaceSharedForTest(null)
            }
        }
}
