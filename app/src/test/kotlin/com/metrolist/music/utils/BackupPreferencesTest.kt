package com.metrolist.music.utils

import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupPreferencesTest {
    @Test
    fun exportedSettingsExcludeCredentialsWithoutChangingLivePreferences() = runBlocking {
        val credentialNames = listOf(
            "innerTubeCookie", "visitorData", "dataSyncId", "innerTubeAuthUser",
            "accountName", "accountEmail", "accountChannelHandle",
            "listenTogetherSessionToken", "listenTogetherRoomCode", "listenTogetherUserId",
            "lastfmSession", "lastfmUsername", "openRouterApiKey", "deeplApiKey",
            "proxyUsername", "proxyPassword",
        )
        val live = mutablePreferencesOf(
            stringPreferencesKey("darkMode") to "ON",
            booleanPreferencesKey("dynamicTheme") to true,
            intPreferencesKey("selectedThemeColor") to 42,
            longPreferencesKey("alarmNextTriggerAt") to 1234L,
            floatPreferencesKey("playerVolume") to 0.75f,
            doublePreferencesKey("customDouble") to 1.25,
            stringSetPreferencesKey("customSet") to setOf("first", "second"),
            byteArrayPreferencesKey("customBytes") to byteArrayOf(1, 2, 3),
            booleanPreferencesKey("listenTogetherIsHost") to true,
            longPreferencesKey("listenTogetherSessionTimestamp") to 5678L,
        )
        credentialNames.forEach { live[stringPreferencesKey(it)] = "private-$it" }
        val before = live.toPreferences()
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("settings.preferences_pb"))
            writeBackupPreferences(live, zip)
            zip.putNextEntry(ZipEntry("library"))
            zip.write("library-data".encodeToByteArray())
        }

        ZipInputStream(archive.toByteArray().inputStream()).use { zip ->
            assertEquals("settings.preferences_pb", zip.nextEntry.name)
            val settingsBytes = zip.readBytes()
            val restored = PreferencesSerializer.readFrom(Buffer().write(settingsBytes))
            credentialNames.forEach {
                assertNull(it, restored[stringPreferencesKey(it)])
                assertFalse(settingsBytes.decodeToString().contains("private-$it"))
            }
            assertNull(restored[booleanPreferencesKey("listenTogetherIsHost")])
            assertNull(restored[longPreferencesKey("listenTogetherSessionTimestamp")])
            assertEquals("ON", restored[stringPreferencesKey("darkMode")])
            assertEquals(true, restored[booleanPreferencesKey("dynamicTheme")])
            assertEquals(42, restored[intPreferencesKey("selectedThemeColor")])
            assertEquals(1234L, restored[longPreferencesKey("alarmNextTriggerAt")])
            assertEquals(0.75f, restored[floatPreferencesKey("playerVolume")]!!, 0.0f)
            assertEquals(1.25, restored[doublePreferencesKey("customDouble")]!!, 0.0)
            assertEquals(setOf("first", "second"), restored[stringSetPreferencesKey("customSet")])
            assertArrayEquals(byteArrayOf(1, 2, 3), restored[byteArrayPreferencesKey("customBytes")])
            assertEquals("library", zip.nextEntry.name)
            assertEquals("library-data", zip.readBytes().decodeToString())
            assertNull(zip.nextEntry)
        }
        assertEquals(before, live)
    }

    @Test
    fun emptyPreferencesProduceReadableSettings() = runBlocking {
        val output = ByteArrayOutputStream()
        writeBackupPreferences(emptyPreferences(), output)
        val restored = PreferencesSerializer.readFrom(Buffer().write(output.toByteArray()))
        assertTrue(restored.asMap().isEmpty())
    }

    @Test(expected = IOException::class)
    fun exportPropagatesWriteFailures() = runBlocking {
        val output = object : OutputStream() {
            override fun write(value: Int) {
                throw IOException("Destination unavailable")
            }
        }
        writeBackupPreferences(mutablePreferencesOf(stringPreferencesKey("darkMode") to "ON"), output)
    }
}
