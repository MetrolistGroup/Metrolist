package com.metrolist.shared.discord

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.settings.IntegrationPlaybackState
import com.metrolist.shared.ui.settings.buildMobileDiscordPlaybackUpdate
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordPlaybackSessionTest {
    private val settings = Settings(MapSettings()).apply { enableDiscordIntegration = true }
    private val song =
        SongItem(
            id = "song",
            title = "Title",
            thumbnail = "cover",
            album = null,
            duration = 120,
            artists = listOf(ArtistTiny("artist", "Artist", thumbnail = "portrait")),
        )
    private val playing = IntegrationPlaybackState(song, positionMs = 30_000, isPlaying = true)

    private fun update(
        playback: IntegrationPlaybackState? = playing,
        foreground: Boolean = true,
    ) = buildMobileDiscordPlaybackUpdate(
        playback,
        foreground,
        settings,
        "Unknown artist",
        "Unknown album",
        "Localized listen",
        "Localized visit",
        now = 100_000,
    )

    @Test
    fun playbackSpeedScalesElapsedAndRemainingWallTime() {
        val faster = assertNotNull(update(playing.copy(playbackSpeed = 2f)).activity)
        assertEquals(85_000L, faster.startTimestamp)
        assertEquals(145_000L, faster.endTimestamp)
        val slower = assertNotNull(update(playing.copy(playbackSpeed = 0.5f)).activity)
        assertEquals(40_000L, slower.startTimestamp)
        assertEquals(280_000L, slower.endTimestamp)
        assertNull(update(playing.copy(playbackSpeed = Float.NaN)).activity?.endTimestamp)
    }

    @Test
    fun disabledIntegrationStaysDisconnectedEvenInForegroundSettings() {
        settings.enableDiscordIntegration = false
        val update =
            buildMobileDiscordPlaybackUpdate(
                playing,
                foreground = true,
                settings = settings,
                unknownArtist = "Unknown artist",
                unknownAlbum = "Unknown album",
                button1Default = "Listen",
                button2Default = "Visit",
                settingsVisible = true,
            )
        assertFalse(update.keepConnected)
        assertNull(update.activity)
    }

    @Test
    fun pausedIdleAndBackgroundPlaybackHaveDifferentLifetimes() {
        assertTrue(update(foreground = false).keepConnected)
        assertNotNull(update(foreground = false).activity)
        val paused = update(playing.copy(isPlaying = false))
        assertTrue(paused.keepConnected)
        assertNull(paused.activity)
        val background = update(playing.copy(isPlaying = false), foreground = false)
        assertFalse(background.keepConnected)
        assertNull(background.activity)
        assertFalse(update(null, foreground = false).keepConnected)
        assertTrue(update(null).keepConnected)
    }

    @Test
    fun backgroundShutdownFinishesBeforeReconnectAndCancellationClosesSession() =
        runTest {
            val updates = Channel<DiscordPlaybackUpdate>(Channel.UNLIMITED)
            val shutdown = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val job =
                launch {
                    maintainDiscordPlaybackSession(
                        updates.consumeAsFlow(),
                        connect = { events += "connect" },
                        disconnect = {
                            events += "closing"
                            shutdown.await()
                            delay(1)
                            events += "closed"
                        },
                        publish = { events += if (it.activity == null) "clear" else "publish" },
                    )
                }
            updates.send(update())
            updates.send(update(playing.copy(isPlaying = false)))
            updates.send(update(null, foreground = false))
            updates.send(update())
            runCurrent()
            assertEquals(listOf("connect", "publish", "clear", "closing"), events)
            shutdown.complete(Unit)
            delay(2)
            runCurrent()
            assertEquals(listOf("connect", "publish", "clear", "closing", "closed", "connect", "publish"), events)
            job.cancelAndJoin()
            assertEquals(listOf("closing", "closed"), events.takeLast(2))
        }
}
