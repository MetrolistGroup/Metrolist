package meow.nyx.spotlib

import com.metrolist.shared.network.sharedHttpEngine
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifyHttpTest {
    @AfterTest
    fun tearDown() {
        PlaybackEventLog.clear()
    }

    @Test
    fun sharedEngineRecordsAnHttpFailureOnlyOnce() =
        runBlocking {
            PlaybackEventLog.clear()
            val client = SpotifyHttp.create(SpotifyConfig(engine = sharedHttpEngine, logLevel = LogLevel.NONE))
            try {
                runCatching { client.get("http://127.0.0.1:1/private?token=secret") }

                val events = PlaybackEventLog.events.value
                assertEquals(1, events.size)
                assertEquals(PlaybackEventKind.NETWORK, events.single().kind)
            } finally {
                client.close()
            }
        }
}
