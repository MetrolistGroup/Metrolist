import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.PersistPlayerState
import com.metrolist.music.models.PersistQueue
import java.io.File
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass

// Compile with the historical model sources, in a separate JVM from the current app.
fun main(args: Array<String>) {
    val songs = listOf("occurrence-A", "occurrence-B").mapIndexed { index, occurrence ->
        MediaMetadata(
            id = "same-song", title = "Saved song", duration = 123,
            artists = listOf(MediaMetadata.Artist("artist", "Saved artist")),
            album = MediaMetadata.Album("album", "Saved album"),
            setVideoId = occurrence,
        ).also { song ->
            mapOf("playlistBrowseId" to "VLplaylist", "localPlaylistId" to "local", "playlistSongMapId" to index + 1)
                .forEach { (name, value) ->
                    runCatching { song.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(song, value) }
                }
        }
    }
    val queue = PersistQueue("Saved queue", songs, mediaItemIndex = 1, position = 98_765L)
    mapOf("playlistBrowseId" to "VLplaylist", "playlistId" to "local", "playlistIsEditable" to true)
        .forEach { (name, value) ->
            runCatching { queue.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(queue, value) }
        }
    File(args[0]).outputStream().use { ObjectOutputStream(it).use { out -> out.writeObject(queue) } }
    val state = PersistPlayerState(false, 2, true, 0.75f, 98_765L, 1, 3, timestamp = 123L)
    File(args[1]).outputStream().use { ObjectOutputStream(it).use { out -> out.writeObject(state) } }
    listOf(PersistQueue::class.java, MediaMetadata::class.java, PersistPlayerState::class.java).forEach {
        println("${it.simpleName} serialVersionUID=${ObjectStreamClass.lookup(it).serialVersionUID}")
    }
}
