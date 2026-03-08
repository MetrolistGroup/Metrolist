package com.metrolist.shared.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AndroidBatchSettingsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun synchronousReadsUseOneSnapshotAndWritesAreDurableAfterFlush() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { temporary.root.resolve("settings.preferences_pb") }
        var reads = 0
        val counted =
            object : DataStore<Preferences> by store {
                override val data: Flow<Preferences> =
                    flow {
                        reads++
                        emit(store.data.first())
                    }
            }
        try {
            val settings = AndroidBatchSettings(counted)
            repeat(300) { assertEquals("default", settings.getString("missing", "default")) }
            assertEquals(1, reads)
            settings.putString("name", "saved")
            settings.putBoolean("enabled", true)
            settings.putInt("count", 42)
            settings.putLong("position", 1234L)
            settings.putFloat("volume", 0.5f)
            settings.putDouble("precise", 0.25)
            assertEquals("saved", settings.getStringOrNull("name"))
            assertEquals(true, settings.getBooleanOrNull("enabled"))
            assertEquals(42, settings.getIntOrNull("count"))
            assertEquals(1234L, settings.getLongOrNull("position"))
            assertEquals(0.5f, settings.getFloatOrNull("volume"))
            assertEquals(0.25, settings.getDoubleOrNull("precise"))
            assertEquals(6, settings.size)
            settings.remove("count")
            assertEquals(false, settings.hasKey("count"))
            assertEquals(1, reads)
            settings.flush()
            val reopened = AndroidBatchSettings(store)
            assertEquals("saved", reopened.getStringOrNull("name"))
            assertEquals(null, reopened.getIntOrNull("count"))
            settings.clear()
            assertEquals(emptySet(), settings.keys)
            settings.flush()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun migratedLikedSongsStayOutOfOrdinaryPreferenceCommits() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { temporary.root.resolve("settings.preferences_pb") }
        val catalog = "[" + " ".repeat(100_000) + "]"
        val likedSongsKey = stringPreferencesKey("likedSongsJson")
        runBlocking { store.updateData { it.toMutablePreferences().apply { this[likedSongsKey] = catalog } } }
        val writes = mutableListOf<Preferences>()
        val counted =
            object : DataStore<Preferences> by store {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    store.updateData {
                        transform(it).also(writes::add)
                    }
            }
        try {
            val context = RuntimeEnvironment.getApplication()
            val backing = AndroidFileSettings(context, AndroidBatchSettings(counted))
            val settings = Settings(backing)
            settings.flush()
            assertEquals(catalog, settings.likedSongsJson)
            assertFalse(runBlocking { store.data.first() }.contains(likedSongsKey))
            writes.clear()

            repeat(3) { index ->
                settings.equalizerProfilesJson = "profile-$index"
                settings.flush()
            }

            assertEquals(3, writes.size)
            assertTrue(writes.all { !it.contains(likedSongsKey) })
            assertTrue(temporary.root.resolve("settings.preferences_pb").length() < catalog.length)
            val snapshot = settings.exportBackupSettings()
            assertEquals(catalog, snapshot.entries.single { it.key == "likedSongsJson" }.value)
            settings.likedSongsJson = "[]"
            settings.flush()
            settings.importBackupSettings(snapshot)
            assertEquals(catalog, settings.likedSongsJson)
            assertEquals(catalog, Settings(AndroidFileSettings(context, AndroidBatchSettings(counted))).likedSongsJson)
            assertFalse(runBlocking { store.data.first() }.contains(likedSongsKey))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun wrappedImportAndRollbackEachCommitPreferencesOnce() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { temporary.root.resolve("settings.preferences_pb") }
        var writes = 0
        val counted =
            object : DataStore<Preferences> by store {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    writes++
                    return store.updateData(transform)
                }
            }
        try {
            val context = RuntimeEnvironment.getApplication()
            val backing = AndroidFileSettings(context, AndroidSecureSettings(context, AndroidBatchSettings(counted)))
            backing.flush()
            val settings = Settings(backing)
            settings.flush()
            val snapshot = settings.exportBackupSettings()
            writes = 0
            settings.importBackupSettings(
                PortableSettings(
                    entries =
                        listOf(
                            PortableSetting("lastHomePageJson", SettingType.STRING, "home"),
                            PortableSetting("playerCanvasEnabled", SettingType.BOOLEAN, "true"),
                            PortableSetting("enableAnimatedCovers", SettingType.BOOLEAN, "true"),
                        ),
                ),
            )
            assertEquals(1, writes)
            assertEquals("home", settings.lastHomePageJson)
            assertEquals(true, settings.enableAnimatedCovers)
            settings.restoreBackupSettingsSnapshot(snapshot)
            assertEquals(2, writes)
            assertEquals("", settings.lastHomePageJson)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedBatchDoesNotCommitPartialPreferences() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { temporary.root.resolve("settings.preferences_pb") }
        try {
            val settings = AndroidBatchSettings(store)
            settings.putString("value", "old")
            assertFailsWith<NumberFormatException> {
                settings.applyBatch(
                    listOf(
                        PortableSetting("value", SettingType.STRING, "new"),
                        PortableSetting("bad", SettingType.INT, "invalid"),
                    ),
                    setOf("value"),
                )
            }
            assertEquals("old", settings.getStringOrNull("value"))
            settings.flush()
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 10_000)
    fun queuedWritesPreserveOrderAndReadYourWritesUntilFlushIsDurable() =
        runBlocking {
            withTimeout(5_000) {
                val firstStarted = CompletableDeferred<Unit>()
                val secondStarted = CompletableDeferred<Unit>()
                val releaseFirst = CompletableDeferred<Unit>()
                val releaseSecond = CompletableDeferred<Unit>()
                val store =
                    RecordingDataStore { write ->
                        when (write) {
                            1 -> {
                                firstStarted.complete(Unit)
                                releaseFirst.await()
                            }

                            2 -> {
                                secondStarted.complete(Unit)
                                releaseSecond.await()
                            }

                            else -> {}
                        }
                    }
                val settings = AndroidBatchSettings(store)
                try {
                    settings.putString("value", "first")
                    firstStarted.await()
                    settings.putString("value", "second")
                    settings.putInt("count", 1)
                    settings.remove("value")
                    val entries = mutableListOf(PortableSetting("value", SettingType.STRING, "batch"))
                    val removedKeys = mutableSetOf("count")
                    settings.applyBatch(entries, removedKeys)
                    entries.clear()
                    removedKeys.clear()
                    assertEquals("batch", settings.getStringOrNull("value"))
                    assertFalse(settings.hasKey("count"))
                    assertEquals(
                        emptySet(),
                        store.data
                            .first()
                            .asMap()
                            .keys,
                    )

                    val flushStarted = CompletableDeferred<Unit>()
                    val flushing =
                        async(Dispatchers.IO) {
                            flushStarted.complete(Unit)
                            settings.flush()
                        }
                    flushStarted.await()
                    releaseFirst.complete(Unit)
                    secondStarted.await()
                    assertEquals("batch", settings.getStringOrNull("value"))
                    assertFalse(flushing.isCompleted)
                    releaseSecond.complete(Unit)
                    flushing.await()

                    val value = stringPreferencesKey("value")
                    val count = intPreferencesKey("count")
                    assertEquals(listOf("first", "batch"), store.writes.map { it[value] })
                    assertEquals(listOf(null, null), store.writes.map { it[count] })
                    assertEquals("batch", AndroidBatchSettings(store).getStringOrNull("value"))

                    settings.clear()
                    assertEquals(emptySet(), settings.keys)
                    settings.flush()
                    assertTrue(
                        store.data
                            .first()
                            .asMap()
                            .isEmpty(),
                    )
                } finally {
                    releaseFirst.complete(Unit)
                    releaseSecond.complete(Unit)
                }
            }
        }

    @Test(timeout = 10_000)
    fun flushReportsFailureAndRetriesPendingChangesInOrder() {
        var failing = true
        val store = RecordingDataStore { if (failing) throw IOException("disk full") }
        val settings = AndroidBatchSettings(store)
        settings.putString("value", "pending")
        val failure = assertFailsWith<ExecutionException> { settings.flush() }
        assertTrue(failure.cause is IOException)
        assertEquals("pending", settings.getStringOrNull("value"))
        assertTrue(store.writes.isEmpty())

        failing = false
        settings.putInt("count", 2)
        settings.flush()
        assertEquals(1, store.writes.size)
        assertEquals("pending", store.writes.single()[stringPreferencesKey("value")])
        assertEquals(2, store.writes.single()[intPreferencesKey("count")])
    }

    @Test(timeout = 10_000)
    fun persistentFailuresRetainOnlyLatestPreferencesAndPreserveClearRemoveOrdering() {
        var failing = true
        val store = RecordingDataStore { if (failing) throw IOException("disk full") }
        val settings = AndroidBatchSettings(store)
        repeat(100) { index ->
            settings.putString("discarded", "value-$index")
            settings.clear()
            settings.putInt("counter", index)
            settings.putString("removed", "temporary")
            settings.remove("removed")
            assertFailsWith<ExecutionException> { settings.flush() }
        }
        assertEquals(setOf("counter"), settings.keys)
        assertTrue(store.writes.isEmpty())
        failing = false
        settings.flush()
        assertEquals(1, store.writes.size)
        assertEquals(
            1,
            store.writes
                .single()
                .asMap()
                .size,
        )
        assertEquals(99, store.writes.single()[intPreferencesKey("counter")])
    }

    private class RecordingDataStore(
        private val beforeWrite: suspend (Int) -> Unit,
    ) : DataStore<Preferences> {
        private val stored = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = stored
        val writes = mutableListOf<Preferences>()
        private var attempts = 0

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            beforeWrite(++attempts)
            val updated = transform(stored.value).toPreferences()
            writes.add(updated)
            stored.value = updated
            return updated
        }
    }
}
