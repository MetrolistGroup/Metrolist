package com.metrolist.shared.state

import com.metrolist.shared.backup.LIKED_SONGS_SETTING_KEY
import com.metrolist.shared.constants.DatabaseLikesRecoveryVersionKey
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.DeferredSecretMigrationSettings
import com.metrolist.shared.settings.FlushableSettings
import com.metrolist.shared.settings.PortableSetting
import com.metrolist.shared.settings.PortableSettings
import com.metrolist.shared.settings.SettingType
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.russhwolf.settings.Settings as KmpSettings

class DatabaseLikesRecoveryTest {
    private var previousSettings: Settings? = null
    private val song = SongItem("old", "Song", null, emptyList(), null)
    private val databaseLikes = listOf(song, song.copy(id = "middle"), song.copy(id = "new"))

    @BeforeTest
    fun setUp() =
        runTest {
            previousSettings = Settings.sharedOverrideForTest()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            SongLikeState.awaitPersistence()
            SongLikeState.refreshFromSettings()
        }

    @AfterTest
    fun tearDown() =
        runTest {
            SongLikeState.awaitPersistence()
            Settings.replaceSharedForTest(previousSettings)
            if (previousSettings != null) SongLikeState.refreshFromSettings()
        }

    @Test
    fun emptyUpgradeCatalogRecoversEveryDatabaseLikeNewestFirst() {
        val settings = Settings.shared
        assertTrue(settings.recoverDatabaseLikes(databaseLikes))
        assertEquals(listOf("new", "middle", "old"), decodedLikes().map(SongItem::id))
        assertEquals("new,middle,old", settings.likedSongIds)
        assertEquals(listOf("new", "middle", "old"), Json.decodeFromString(settings.likedSongsRecencyJson))
        assertFalse(settings.recoverDatabaseLikes(listOf(song.copy(id = "later"))))
    }

    @Test
    fun partialLegacyCatalogKeepsMetadataOrderAndRecency() {
        val customized = listOf(song.copy(id = "middle", title = "Custom"), song.copy(id = "settings-only"))
        val settings = Settings(MapSettings(mutableMapOf(LIKED_SONGS_SETTING_KEY to Json.encodeToString(customized))))
        settings.likedSongsRecencyJson = Json.encodeToString(listOf("settings-only", "middle"))
        settings.likedSongsOrderCustomized = true
        assertTrue(settings.recoverDatabaseLikes(databaseLikes))
        assertEquals(customized + listOf(databaseLikes.last(), song), Json.decodeFromString(settings.likedSongsJson))
        assertEquals(listOf("settings-only", "middle", "new", "old"), Json.decodeFromString(settings.likedSongsRecencyJson))
        assertTrue(settings.likedSongsOrderCustomized)
    }

    @Test
    fun partialRestoreRecoversEvenWhenDestinationAlreadyRanRecovery() {
        val settings = Settings.shared
        settings.recoverDatabaseLikes(emptyList())
        val backup =
            PortableSettings(
                entries = listOf(PortableSetting(LIKED_SONGS_SETTING_KEY, SettingType.STRING, Json.encodeToString(listOf(song)))),
            )
        settings.importBackupSettings(backup)
        settings.restoreDatabaseLikes(databaseLikes, backup.entries.mapTo(mutableSetOf()) { it.key })
        assertEquals(listOf("old", "new", "middle"), decodedLikes().map(SongItem::id))
    }

    @Test
    fun unlikeAndDislikeBeforeRecoveryDoNotResurrectStaleDatabaseRows() =
        runTest {
            SongLikeState.replaceLikesLocally(databaseLikes)
            SongLikeState.removeLike("old")
            SongLikeState.dislike(databaseLikes.last())
            SongLikeState.awaitPersistence()
            SongLikeState.refreshFromSettings()
            assertTrue(Settings.shared.recoverDatabaseLikes(databaseLikes))
            assertEquals(listOf("middle"), decodedLikes().map(SongItem::id))
        }

    @Test
    fun restoreWithoutSettingsAlsoHonorsRecordedUnlikes() =
        runTest {
            SongLikeState.addLike(song)
            SongLikeState.removeLike(song.id)
            SongLikeState.awaitPersistence()
            Settings.shared.restoreDatabaseLikes(databaseLikes, null)
            assertEquals(listOf("new", "middle"), decodedLikes().map(SongItem::id))
        }

    @Test
    fun recoveredBackupDoesNotResurrectLaterUnlikes() {
        val settings = Settings.shared
        settings.recoverDatabaseLikes(databaseLikes)
        settings.likedSongsJson = "[]"
        settings.restoreDatabaseLikes(databaseLikes, setOf(LIKED_SONGS_SETTING_KEY, DatabaseLikesRecoveryVersionKey))
        assertTrue(decodedLikes().isEmpty())
    }

    @Test
    fun signedInUpgradeRecoversBeforeAccountActivationIncludingTheOldSkippedVersion() {
        for (version in listOf(0, 1, 2)) {
            val settings = Settings(MapSettings())
            settings.innerTubeCookie = "SAPISID=test"
            settings.dataSyncId = "account"
            settings.innerTubeAuthUser = "2"
            settings.databaseLikesRecoveryVersion = version
            settings.likedSongsJson = Json.encodeToString(listOf(song))
            settings.pendingSongVotesJson = """{"middle":"INDIFFERENT"}"""
            settings.dislikedSongIds = "new"
            val missing = song.copy(id = "missing")
            assertTrue(settings.recoverDatabaseLikes(databaseLikes + missing))
            assertEquals(listOf(song, missing), Json.decodeFromString(settings.likedSongsJson))
            assertEquals("""{"middle":"INDIFFERENT"}""", settings.pendingSongVotesJson)
            assertEquals(DATABASE_LIKES_RECOVERY_VERSION, settings.databaseLikesRecoveryVersion)
        }
    }

    @Test
    fun activatedAndPreviouslySignedInCatalogsKeepAcknowledgedRemovals() {
        for (version in listOf(0, 1)) {
            val settings = Settings(MapSettings())
            settings.databaseLikesRecoveryVersion = version
            settings.likedSongsOrderAccount = "account|0"
            settings.likedSongsJson = Json.encodeToString(listOf(song))
            val before = settings.likedSongsJson
            assertFalse(settings.recoverDatabaseLikes(databaseLikes))
            assertEquals(before, settings.likedSongsJson)
            settings.innerTubeCookie = ""
            settings.dataSyncId = ""
            settings.likedSongsOrderAccount = "signed-out|0"
            assertFalse(settings.recoverDatabaseLikes(databaseLikes))
            assertEquals(before, settings.likedSongsJson)
        }
    }

    @Test
    fun emptyActivatedAccountCatalogIsSeededAfterAStartupRefreshActivatedItFirst() {
        for (version in listOf(0, 2)) {
            val settings = Settings(MapSettings())
            settings.innerTubeCookie = "SAPISID=test"
            settings.likedSongsOrderAccount = "account|0"
            settings.databaseLikesRecoveryVersion = version
            assertTrue(settings.recoverDatabaseLikes(databaseLikes))
            assertEquals(listOf("new", "middle", "old"), Json.decodeFromString<List<SongItem>>(settings.likedSongsJson).map(SongItem::id))
            assertFalse(settings.recoverDatabaseLikes(databaseLikes))
        }
    }

    @Test
    fun completedSignedOutRecoveryDoesNotRunAgainAfterVersionBump() {
        for (version in listOf(1, 2)) {
            val settings = Settings(MapSettings())
            settings.databaseLikesRecoveryVersion = version
            assertFalse(settings.recoverDatabaseLikes(databaseLikes))
            assertEquals("[]", settings.likedSongsJson)
            assertEquals(DATABASE_LIKES_RECOVERY_VERSION, settings.databaseLikesRecoveryVersion)
        }
    }

    @Test
    fun pendingCredentialsDeferRecoveryUntilTheAccountIsKnown() {
        val storage =
            object : KmpSettings by MapSettings(), DeferredSecretMigrationSettings {
                override var secretRecoveryPending = true
            }
        val settings = Settings(storage)
        assertFalse(settings.recoverDatabaseLikes(databaseLikes))
        assertEquals(0, settings.databaseLikesRecoveryVersion)
        storage.secretRecoveryPending = false
        settings.refreshStoredValues()
        settings.innerTubeCookie = "SAPISID=test"
        assertTrue(settings.recoverDatabaseLikes(databaseLikes))
        assertEquals(listOf("new", "middle", "old"), Json.decodeFromString<List<SongItem>>(settings.likedSongsJson).map(SongItem::id))
    }

    @Test
    fun signedInPartialRestoreKeepsItsSettingsCatalog() {
        val settings = Settings.shared
        settings.innerTubeCookie = "SAPISID=test"
        settings.likedSongsJson = Json.encodeToString(listOf(song))
        val before = settings.likedSongsJson
        settings.restoreDatabaseLikes(databaseLikes, setOf(LIKED_SONGS_SETTING_KEY))
        assertEquals(before, settings.likedSongsJson)
    }

    @Test
    fun recoveryHasNoPageOrBatchLimit() {
        val songs = (1..4303).map { song.copy(id = "song-$it") }
        val settings = Settings.shared
        settings.likedSongsJson = Json.encodeToString(songs.take(403))
        settings.recoverDatabaseLikes(songs)
        assertEquals(4303, decodedLikes().size)
        assertEquals(4303, decodedLikes().map(SongItem::id).toSet().size)
    }

    @Test
    fun failedPayloadFlushLeavesRecoveryRetryable() {
        var fail = true
        val storage =
            object : KmpSettings by MapSettings(), FlushableSettings {
                override fun flush() {
                    check(!fail) { "Write failed" }
                }
            }
        val settings = Settings(storage)
        assertFailsWith<IllegalStateException> { settings.recoverDatabaseLikes(databaseLikes) }
        assertEquals(0, settings.databaseLikesRecoveryVersion)
        fail = false
        assertTrue(settings.recoverDatabaseLikes(databaseLikes))
        assertEquals(3, Json.decodeFromString<List<SongItem>>(settings.likedSongsJson).size)
    }

    private fun decodedLikes(): List<SongItem> = Json.decodeFromString(Settings.shared.likedSongsJson)
}
