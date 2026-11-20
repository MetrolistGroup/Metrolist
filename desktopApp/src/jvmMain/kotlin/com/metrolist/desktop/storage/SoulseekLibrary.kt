package com.metrolist.desktop.storage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.utils.XdgUtils
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A Soulseek download kept on disk for one song. */
@Serializable
data class SoulseekFile(
    val path: String,
    val format: String,
    val extension: String,
    val size: Long,
    val bitrate: Int? = null,
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
)

/** Which Soulseek versions each song has, and the one it plays instead of streaming. */
object SoulseekLibrary {
    @Serializable
    private data class Store(
        val versions: Map<String, List<SoulseekFile>> = emptyMap(),
        /** Song id to the path of the version to play; absent means stream as usual. */
        val selected: Map<String, String> = emptyMap(),
        /** The song as it was when a version was added, so the Soulseek collection can list it offline. */
        val songs: Map<String, SongItem> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Settable so tests keep their store away from the user's state directory. */
    internal var storeFile = File(XdgUtils.getXdgStateHome(), "metrolist/soulseek-files.json")
        set(value) {
            field = value
            store = load()
        }

    private var store by mutableStateOf(load())

    /** The song whose versions [SoulseekVersionsDialog] shows, or null when it is closed. */
    var versionsSong by mutableStateOf<SongItem?>(null)
        private set

    fun openVersions(song: SongItem) {
        versionsSong = song
    }

    fun closeVersions() {
        versionsSong = null
    }

    private fun load(): Store = runCatching { json.decodeFromString<Store>(storeFile.readText()) }.getOrDefault(Store())

    private fun save(next: Store) {
        store = next
        runCatching {
            storeFile.parentFile.mkdirs()
            val temp = File(storeFile.parentFile, "${storeFile.name}.tmp")
            temp.writeText(json.encodeToString(next))
            Files.move(temp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** Versions whose file still exists, in the picker's format order. */
    fun versions(songId: String): List<SoulseekFile> =
        store.versions[songId]
            .orEmpty()
            .filter { File(it.path).isFile }
            .sortedBy { SoulseekDownloader.formatOrder.indexOf(it.format) }

    fun selected(songId: String): SoulseekFile? = store.selected[songId]?.let { path -> versions(songId).firstOrNull { it.path == path } }

    /** Records a new download or linked file and plays it from now on. */
    fun add(
        song: SongItem,
        file: SoulseekFile,
    ) {
        val others = store.versions[song.id].orEmpty().filter { it.path != file.path }
        save(
            store.copy(
                versions = store.versions + (song.id to others + file),
                selected = store.selected + (song.id to file.path),
                songs = store.songs + (song.id to song),
            ),
        )
    }

    /** Ids of songs with at least one version still on disk, newest first. */
    fun songIds(): List<String> =
        store.versions.keys
            .filter { versions(it).isNotEmpty() }
            .reversed()

    /** Songs with a Soulseek version, from the saved snapshot or, for older records, the song database. */
    suspend fun songs(): List<SongItem> {
        val ids = songIds()
        val missing = ids.filter { it !in store.songs }
        val stored =
            if (missing.isEmpty()) {
                emptyMap()
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        GlobalContext
                            .get()
                            .get<MusicDatabase>()
                            .dao()
                            .getSongsByIds(missing)
                            .map { it.toSongItem() }
                            .associateBy { it.id }
                    }.getOrDefault(emptyMap())
                }
            }
        return ids.mapNotNull { store.songs[it] ?: stored[it] }
    }

    /** Where Soulseek downloads land by default, used as the file chooser's start folder. */
    val downloadDirectory: File get() = File(System.getProperty("user.home"), "Music/Soulseek")

    /** A description from the file name alone, for when the helper cannot read the file's headers. */
    fun describeByExtension(file: File): SoulseekFile {
        val extension = file.extension.lowercase()
        return SoulseekFile(
            path = file.absolutePath,
            format = if (extension in LOSSLESS_EXTENSIONS) "lossless" else "320",
            extension = extension,
            size = file.length(),
        )
    }

    val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "aiff", "aif", "ape", "wv")
    private val LOSSLESS_EXTENSIONS = setOf("flac", "wav", "aiff", "aif", "ape", "wv")

    /** Plays [path] for the song, or streams again when null. */
    fun select(
        songId: String,
        path: String?,
    ) {
        save(store.copy(selected = if (path == null) store.selected - songId else store.selected + (songId to path)))
    }

    /** The selected version as a local playback source, checked before downloads and the stream. */
    fun playbackResource(songId: String): PlaybackResource? =
        selected(songId)?.let { file ->
            PlaybackResource(
                videoId = songId,
                url = file.path,
                headers = emptyMap(),
                loudnessDb = null,
                expiresAt = null,
                contentLengthBytes = file.size,
                bitrate = file.bitrate?.times(1000),
                sampleRate = file.sampleRate,
                clientName = "local",
                profileId = "downloaded-audio",
            )
        }
}
