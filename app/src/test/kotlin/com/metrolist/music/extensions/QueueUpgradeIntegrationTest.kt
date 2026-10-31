package com.metrolist.music.extensions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.models.PersistPlayerState
import com.metrolist.music.models.PersistQueue
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.resolvePlaylistRemovalTarget
import com.metrolist.music.utils.QueueObjectInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InvalidClassException
import java.io.ObjectInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QueueUpgradeIntegrationTest {
    // Generated in separate JVMs from main 76fa5a78b and the playlist build before UID pinning.
    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/queue-compat/$name.ser")!!.use { it.readBytes() }

    private fun restore(bytes: ByteArray): PersistQueue = QueueObjectInputStream(ByteArrayInputStream(bytes)).use {
        it.readObject() as PersistQueue
    }

    private fun assertPlaybackPreserved(saved: PersistQueue): ListQueue {
        assertEquals("Saved queue", saved.title)
        assertEquals(1, saved.mediaItemIndex)
        assertEquals(98_765L, saved.position)
        assertEquals(listOf("occurrence-A", "occurrence-B"), saved.items.map { it.setVideoId })
        assertEquals("Saved artist", saved.items[1].artists.single().name)
        assertEquals("Saved album", saved.items[1].album?.title)
        val restored = saved.toQueue() as ListQueue
        assertEquals(1, restored.startIndex)
        assertEquals(98_765L, restored.position)
        assertEquals(listOf("occurrence-A", "occurrence-B"), restored.items.map { it.metadata?.setVideoId })
        return restored
    }

    @Test
    fun `official saved queue upgrades without losing playback or inventing editability`() {
        val saved = restore(fixture("legacy-upstream"))
        val queue = assertPlaybackPreserved(saved)
        assertNull(saved.playlistBrowseId)
        assertFalse(saved.playlistIsEditable)
        assertNull(saved.items[1].playlistBrowseId)
        assertNull(saved.items[1].localPlaylistId)
        assertNull(resolvePlaylistRemovalTarget(queue, "occurrence-B", null))
    }

    @Test
    fun `previous playlist build upgrades with the exact editable occurrence intact`() {
        val saved = restore(fixture("legacy-playlist-context"))
        val queue = assertPlaybackPreserved(saved)
        val item = queue.items[1].metadata!!
        assertEquals("VLplaylist", saved.playlistBrowseId)
        assertTrue(saved.playlistIsEditable)
        assertEquals("local", item.localPlaylistId)
        assertEquals(2, item.playlistSongMapId)
        val target = resolvePlaylistRemovalTarget(queue, item.setVideoId, item.playlistBrowseId)!!
        assertEquals("VLplaylist", target.playlistId)
        assertEquals("occurrence-B", target.setVideoId)
        assertEquals("local", target.localPlaylistId)
    }

    @Test
    fun `intermediate playlist build upgrades without losing source or playback`() {
        val saved = restore(fixture("legacy-playlist-browse-context"))
        val queue = assertPlaybackPreserved(saved)
        val item = queue.items[1].metadata!!
        assertEquals("VLplaylist", item.playlistBrowseId)
        assertNull(item.localPlaylistId)
        val target = resolvePlaylistRemovalTarget(queue, item.setVideoId, item.playlistBrowseId)!!
        assertEquals("occurrence-B", target.setVideoId)
        assertEquals("local", target.localPlaylistId)
    }

    @Test
    fun `old queue automix and player state files all remain readable on upgrade`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queueFile = File(context.cacheDir, "upgrade-queue")
        val automixFile = File(context.cacheDir, "upgrade-automix")
        val stateFile = File(context.cacheDir, "upgrade-state")
        queueFile.writeBytes(fixture("legacy-upstream"))
        automixFile.writeBytes(fixture("legacy-playlist-context"))
        stateFile.writeBytes(fixture("legacy-player-state"))
        assertPlaybackPreserved(QueueObjectInputStream(queueFile.inputStream()).use { it.readObject() as PersistQueue })
        assertPlaybackPreserved(QueueObjectInputStream(automixFile.inputStream()).use { it.readObject() as PersistQueue })
        val state = ObjectInputStream(stateFile.inputStream()).use { it.readObject() as PersistPlayerState }
        assertEquals(98_765L, state.currentPosition)
        assertEquals(1, state.currentMediaItemIndex)
        assertEquals(0.75f, state.volume)
        assertTrue(queueFile.exists() && automixFile.exists() && stateFile.exists())
    }

    private fun offsetOf(bytes: ByteArray, text: String): Int {
        val marker = text.toByteArray(Charsets.UTF_8)
        return (0..bytes.size - marker.size).first { offset -> marker.indices.all { bytes[offset + it] == marker[it] } }
    }

    @Test(expected = InvalidClassException::class)
    fun `unknown historical UID is rejected even with a matching layout`() {
        val bytes = fixture("legacy-playlist-context")
        val name = PersistQueue::class.java.name
        val lastUidByte = offsetOf(bytes, name) + name.toByteArray().size + 7
        bytes[lastUidByte] = (bytes[lastUidByte].toInt() xor 1).toByte()
        restore(bytes)
    }

    @Test(expected = InvalidClassException::class)
    fun `known UID with an incompatible layout is rejected`() {
        val bytes = fixture("legacy-playlist-context")
        val lastFieldByte = offsetOf(bytes, "playlistId") + "playlistId".length - 1
        bytes[lastFieldByte] = 'X'.code.toByte()
        restore(bytes)
    }
}
