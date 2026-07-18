package com.metrolist.music.discord

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DiscordTokenStoreTest {

    private lateinit var context: Context
    private lateinit var testKey: SecretKey

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        DiscordTokenStore.AesKeystore.setTestKey(testKey)
        context.getSharedPreferences("discord_token", Context.MODE_PRIVATE).edit().clear().apply()
        DiscordTokenStore.init(context)
    }

    @After
    fun tearDown() {
        DiscordTokenStore.clear()
        DiscordTokenStore.AesKeystore.setTestKey(null)
    }

    @Test
    fun storeAndRetrieveToken() {
        val token = "secret-access-token"
        DiscordTokenStore.store(token)
        assertEquals(token, DiscordTokenStore.retrieve())
    }

    @Test
    fun storeFull_retainsAccessTokenAndRefreshToken() {
        val access = "access-token"
        val refresh = "refresh-token"
        DiscordTokenStore.storeFull(access, refresh, expiresInSec = 3600L)
        assertEquals(access, DiscordTokenStore.retrieve())
        assertEquals(refresh, DiscordTokenStore.getRefreshToken())
        val expiresAt = DiscordTokenStore.getExpiresAt()
        assertTrue("expiresAt should be in the future", expiresAt > System.currentTimeMillis() / 1000L)
    }

    @Test
    fun storeAccessToken_updatesAccessWithoutChangingRefresh() {
        DiscordTokenStore.storeFull("access1", "refresh1", expiresInSec = 3600L)
        DiscordTokenStore.storeAccessToken("access2")
        assertEquals("access2", DiscordTokenStore.retrieve())
        assertEquals("refresh1", DiscordTokenStore.getRefreshToken())
    }

    @Test
    fun clear_removesAllTokens() {
        DiscordTokenStore.storeFull("access", "refresh", expiresInSec = 3600L)
        DiscordTokenStore.clear()
        assertNull(DiscordTokenStore.retrieve())
        assertNull(DiscordTokenStore.getRefreshToken())
        assertEquals(0L, DiscordTokenStore.getExpiresAt())
    }

    @Test
    fun retrieveSuspend_returnsSameAsRetrieve() = runBlocking {
        DiscordTokenStore.store("suspended-token")
        assertEquals(DiscordTokenStore.retrieve(), DiscordTokenStore.retrieveSuspend())
    }

    @Test
    fun generatesAndCachesUuids() {
        val vendor1 = DiscordTokenStore.getDeviceVendorId()
        val vendor2 = DiscordTokenStore.getDeviceVendorId()
        val client1 = DiscordTokenStore.getClientUuid()
        val client2 = DiscordTokenStore.getClientUuid()
        assertNotNull(vendor1)
        assertNotNull(client1)
        assertEquals(vendor1, vendor2)
        assertEquals(client1, client2)
        assertNotEquals(vendor1, client1)
    }

    @Test
    fun aesEncryptionRoundTrip() {
        val plaintext = "plain-token"
        val encrypted = DiscordTokenStore.AesKeystore.encrypt(plaintext)
        assertNotNull(encrypted)
        assertTrue("Encrypted value should not be plaintext", encrypted != plaintext)
        assertEquals(plaintext, DiscordTokenStore.AesKeystore.decrypt(encrypted))
    }
}
