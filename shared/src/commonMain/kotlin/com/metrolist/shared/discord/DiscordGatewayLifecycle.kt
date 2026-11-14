package com.metrolist.shared.discord

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformLatest

suspend fun observeDiscordInitialization(initialize: () -> Unit) {
    snapshotFlow { Settings.shared.enableDiscordIntegration }
        .collect { needed -> if (needed) initialize() }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun discordGatewayConnectionAllowed(
    enabled: Flow<Boolean>,
    active: Flow<Boolean>,
    online: Flow<Boolean>,
    idleGraceMs: Long = 30_000L,
): Flow<Boolean> =
    enabled
        .distinctUntilChanged()
        .flatMapLatest { integrationEnabled ->
            if (!integrationEnabled) {
                flowOf(false)
            } else {
                active.distinctUntilChanged().transformLatest { inUse ->
                    emit(true)
                    if (!inUse) {
                        delay(idleGraceMs)
                        emit(false)
                    }
                }
            }
        }.combine(online) { wanted, connected -> wanted && connected }
        .distinctUntilChanged()
