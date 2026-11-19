package com.metrolist.drivebroker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class BrokerConfigurationTest {
    @Test
    fun `requires OAuth credentials and encryption key`() {
        val environment =
            mapOf(
                "DATABASE_URL" to "jdbc:postgresql://localhost/metrolist",
                "DATABASE_USER" to "metrolist",
                "DATABASE_PASSWORD" to "secret",
                "DRIVE_REFRESH_TOKEN_ENCRYPTION_KEY" to Base64.getEncoder().encodeToString(ByteArray(32)),
                "GOOGLE_WEB_CLIENT_ID" to "web-client-id",
                "GOOGLE_WEB_CLIENT_SECRET" to "web-client-secret",
                "GOOGLE_ANDROID_CLIENT_ID" to "android-client-id",
                "GOOGLE_IOS_CLIENT_ID" to "ios-client-id",
                "GOOGLE_DESKTOP_CLIENT_ID" to "desktop-client-id",
            )

        assertEquals(8080, BrokerConfiguration.fromEnvironment(environment).port)
        assertThrows(IllegalArgumentException::class.java) {
            BrokerConfiguration.fromEnvironment(environment - "GOOGLE_IOS_CLIENT_ID")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrokerConfiguration.fromEnvironment(environment + ("DRIVE_REFRESH_TOKEN_ENCRYPTION_KEY" to "invalid"))
        }
    }

    @Test
    fun `parses only supported client platforms`() {
        assertEquals(ClientPlatform.ANDROID, ClientPlatform.parse("ANDROID"))
        assertEquals(ClientPlatform.IOS, ClientPlatform.parse("IOS"))
        assertEquals(ClientPlatform.DESKTOP, ClientPlatform.parse("DESKTOP"))
        assertEquals(null, ClientPlatform.parse("WEB"))
    }
}
