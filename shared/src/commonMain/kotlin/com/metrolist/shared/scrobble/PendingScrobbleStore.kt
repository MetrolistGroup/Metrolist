package com.metrolist.shared.scrobble

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.daos.PendingScrobbleDao
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import org.koin.mp.KoinPlatform

@Serializable
enum class ScrobbleProvider { LAST_FM, LISTEN_BRAINZ }

@Serializable
@Entity(tableName = "pending_scrobble")
data class PendingScrobble(
    @PrimaryKey val id: String,
    val provider: ScrobbleProvider,
    val account: String,
    val track: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val duration: Long? = null,
    val timestamp: Long,
    val attemptCount: Int = 0,
    val nextAttemptAtMs: Long = 0L,
)

object PendingScrobbleStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val revision = MutableStateFlow(0L)
    internal val changes = revision.asStateFlow()
    private val mutex = Mutex()

    internal var daoForTest: PendingScrobbleDao? = null

    private suspend fun dao(): PendingScrobbleDao {
        val dao = daoForTest ?: KoinPlatform.getKoin().get<MusicDatabase>().pendingScrobbleDao()
        val raw = Settings.shared.pendingScrobblesJson
        if (raw != "[]") {
            val entries = readLegacy(raw)
            dao.importLegacy(entries)
            Settings.shared.pendingScrobblesJson = "[]"
        }
        return dao
    }

    suspend fun all(): List<PendingScrobble> = mutex.withLock { dao().all() }

    suspend fun get(id: String): PendingScrobble? = mutex.withLock { dao().get(id) }

    suspend fun enqueue(scrobble: PendingScrobble) = upsert(scrobble)

    suspend fun upsert(scrobble: PendingScrobble) = upsert(listOf(scrobble))

    suspend fun upsert(scrobbles: List<PendingScrobble>) =
        mutex.withLock {
            if (scrobbles.isEmpty()) return@withLock
            dao().upsert(scrobbles)
            revision.update { it + 1L }
        }

    suspend fun remove(id: String) =
        mutex.withLock {
            dao().remove(id)
            revision.update { it + 1L }
        }

    suspend fun clear(provider: ScrobbleProvider) =
        mutex.withLock {
            dao().clear(provider)
            revision.update { it + 1L }
        }

    private fun readLegacy(raw: String): List<PendingScrobble> =
        runCatching {
            val elements = json.parseToJsonElement(raw).jsonArray
            val valid = elements.mapNotNull { runCatching { json.decodeFromJsonElement<PendingScrobble>(it) }.getOrNull() }
            if (valid.size != elements.size) Settings.shared.corruptPendingScrobblesJson = raw.take(MAX_QUARANTINE_LENGTH)
            valid
        }.getOrElse {
            Settings.shared.corruptPendingScrobblesJson = raw.take(MAX_QUARANTINE_LENGTH)
            emptyList()
        }

    private const val MAX_QUARANTINE_LENGTH = 1_000_000
}
