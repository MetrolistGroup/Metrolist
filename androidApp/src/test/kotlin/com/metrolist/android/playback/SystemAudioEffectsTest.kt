package com.metrolist.android.playback

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class SystemAudioEffectsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val effects = SystemAudioEffects(context)

    @Test
    fun sessionChangesAndReleaseBalanceNotificationsWithoutInvalidOrDuplicateSessions() {
        val player = player()
        effects.attach(player.instance)
        effects.attach(player.instance)
        assertTrue(notifications().isEmpty())

        player.setAudioSessionId(41)
        player.setAudioSessionId(41)
        player.setAudioSessionId(42)
        effects.detach(player.instance)
        effects.detach(player.instance)
        player.setAudioSessionId(43)

        assertEquals(
            listOf("open" to 41, "close" to 41, "open" to 42, "close" to 42),
            notifications(),
        )
    }

    @Test
    fun sharedReplacementSessionStaysOpenUntilItsLastPlayerReleases() {
        val first = player(41)
        val replacement = player(41)
        effects.attach(first.instance)
        effects.attach(replacement.instance)
        effects.detach(first.instance)
        assertEquals(listOf("open" to 41), notifications())

        effects.detach(replacement.instance)
        assertEquals(listOf("open" to 41, "close" to 41), notifications())
    }

    @Test
    fun distinctCrossfadeSessionsCloseIndependentlyAndInvalidationClosesTheOldSession() {
        val first = player(41)
        val next = player(42)
        effects.attach(first.instance)
        effects.attach(next.instance)
        first.setAudioSessionId(0)
        effects.detach(first.instance)
        effects.detach(next.instance)
        assertEquals(listOf("open" to 41, "open" to 42, "close" to 41, "close" to 42), notifications())
    }

    @Test
    fun deniedNotificationsDoNotInterruptPlayerLifecycle() {
        val deniedContext =
            object : ContextWrapper(context) {
                override fun sendBroadcast(intent: Intent): Unit = throw SecurityException("denied")
            }
        val deniedEffects = SystemAudioEffects(deniedContext)
        val player = player(41)
        deniedEffects.attach(player.instance)
        deniedEffects.detach(player.instance)
        assertTrue(notifications().isEmpty())
    }

    @Test
    fun panelLaunchRequiresAValidSessionAndHandlesUnavailableActivities() {
        for (id in listOf(-1, 0)) {
            assertFalse(launchSystemEqualizer(context, id) { error("Invalid session must not launch a panel") })
        }
        assertFalse(launchSystemEqualizer(context, 41) { throw ActivityNotFoundException() })
        assertFalse(launchSystemEqualizer(context, 41) { throw SecurityException() })
        var launched: Intent? = null
        assertTrue(launchSystemEqualizer(context, 41) { launched = it })
        assertEquals(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL, launched?.action)
        assertEquals(41, launched?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0))
        assertEquals(context.packageName, launched?.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))
        assertEquals(AudioEffect.CONTENT_TYPE_MUSIC, launched?.getIntExtra(AudioEffect.EXTRA_CONTENT_TYPE, -1))
    }

    private fun player(sessionId: Int = 0) = TestPlayer(sessionId)

    private class TestPlayer(
        private var sessionId: Int,
    ) {
        private val listeners = mutableSetOf<Player.Listener>()
        val instance: ExoPlayer =
            Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { proxy, method, args ->
                when (method.name) {
                    "getAudioSessionId" -> {
                        sessionId
                    }

                    "addListener" -> {
                        listeners.add(args!![0] as Player.Listener)
                        null
                    }

                    "removeListener" -> {
                        listeners.remove(args!![0] as Player.Listener)
                        null
                    }

                    "hashCode" -> {
                        System.identityHashCode(proxy)
                    }

                    "equals" -> {
                        proxy === args!![0]
                    }

                    "toString" -> {
                        "TestPlayer"
                    }

                    else -> {
                        error("Unexpected player call: ${method.name}")
                    }
                }
            } as ExoPlayer

        fun setAudioSessionId(id: Int) {
            sessionId = id
            listeners.toList().forEach { it.onAudioSessionIdChanged(id) }
        }
    }

    private fun notifications(): List<Pair<String, Int>> =
        shadowOf(context).broadcastIntents.mapNotNull { intent ->
            val action =
                when (intent.action) {
                    AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> "open"
                    AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> "close"
                    else -> return@mapNotNull null
                }
            assertEquals(context.packageName, intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))
            assertEquals(AudioEffect.CONTENT_TYPE_MUSIC, intent.getIntExtra(AudioEffect.EXTRA_CONTENT_TYPE, -1))
            action to intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        }
}
