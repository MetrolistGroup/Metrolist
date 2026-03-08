package com.metrolist.android.ui.screens.settings

import android.content.Intent
import androidx.core.net.toUri
import com.metrolist.shared.discord.DiscordAuthSession
import com.metrolist.shared.discord.DiscordAuthSessionStore
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DiscordLoginFlowTest {
    @Test
    fun callbackFindsPersistedSessionWithoutAnActiveSettingsScreenAndRequiresItsState() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        try {
            DiscordAuthSessionStore().save(DiscordAuthSession("authorize", "metrolist-auth://discord", "verifier", "expected"))
            assertFalse(DiscordLoginFlow.handleCallback(Intent(Intent.ACTION_VIEW, "https://example.org".toUri())))
            assertTrue(
                DiscordLoginFlow.handleCallback(Intent(Intent.ACTION_VIEW, "metrolist-auth://discord?state=wrong&error=denied".toUri())),
            )
            assertTrue(DiscordLoginFlow.isActive)
            assertTrue(
                DiscordLoginFlow.handleCallback(Intent(Intent.ACTION_VIEW, "metrolist-auth://discord?state=expected&error=denied".toUri())),
            )
            assertFalse(DiscordLoginFlow.isActive)
        } finally {
            Settings.replaceSharedForTest(null)
        }
    }
}
