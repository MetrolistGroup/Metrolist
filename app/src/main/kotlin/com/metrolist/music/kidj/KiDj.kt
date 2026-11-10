/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.kidj

import android.content.Context
import android.content.SharedPreferences
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Party mode: guests send wishes over the LAN, the camera measures the dance floor,
 * and the DJ queues the wish that fits the floor best right after the current song.
 */
object KiDj {
    private val brain = DjBrain()
    private val rescue = RescueWatch()
    private val songs = ConcurrentHashMap<String, SongItem>()
    private var scope: CoroutineScope? = null
    private var server: WishServer? = null
    private var player: PlayerConnection? = null
    private var prefs: SharedPreferences? = null

    /** Wish inserted after the current song that has not started yet. */
    @Volatile private var pendingId: String? = null
    private var currentArtist: String? = null
    private var floorSum = 0.0
    private var floorCount = 0

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _url = MutableStateFlow<String?>(null)
    val url = _url.asStateFlow()
    private val _floor = MutableStateFlow(0.0)
    val floor = _floor.asStateFlow()
    private val _wishes = MutableStateFlow<List<Wish>>(emptyList())
    val wishes = _wishes.asStateFlow()
    private val _log = MutableStateFlow("")
    val log = _log.asStateFlow()
    val rescueEnabled = MutableStateFlow(true)
    val sensitivity = MutableStateFlow(12f)

    fun start(context: Context, connection: PlayerConnection) {
        if (_running.value) return
        player = connection
        prefs = context.getSharedPreferences("ki_dj", Context.MODE_PRIVATE).also { p ->
            brain.restoreReactions(
                p.all.mapNotNull { (k, v) -> if (k.startsWith("r:") && v is Float) k.removePrefix("r:") to v.toDouble() else null }.toMap(),
            )
            sensitivity.value = p.getFloat("sensitivity", 12f)
            rescueEnabled.value = p.getBoolean("rescue", true)
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            connection.mediaMetadata.collect { meta -> onSongChanged(meta?.id, meta?.artists?.firstOrNull()?.name) }
        }
        server = WishServer(WishServer.DEFAULT_PORT, handler).also {
            runCatching { it.start() }.onFailure { e ->
                Timber.e(e, "KI-DJ wish server failed")
                _log.value = "Server konnte nicht starten: ${e.message}"
            }
        }
        _url.value = localIp()?.let { "http://$it:${WishServer.DEFAULT_PORT}/" }
        _running.value = true
    }

    fun stop() {
        server?.stop()
        server = null
        scope?.cancel()
        scope = null
        player = null
        _running.value = false
        prefs?.edit()?.apply {
            brain.reactions().forEach { (k, v) -> putFloat("r:$k", v.toFloat()) }
            putFloat("sensitivity", sensitivity.value)
            putBoolean("rescue", rescueEnabled.value)
        }?.apply()
    }

    /** Called on the main thread by the camera analyzer with the current floor movement (0..1). */
    fun onFloor(energy: Double) {
        val smooth = _floor.value * 0.8 + energy * 0.2
        _floor.value = smooth
        floorSum += smooth
        floorCount++
        if (rescueEnabled.value && rescue.feed(smooth, System.currentTimeMillis())) {
            scope?.launch { rescueFloor() }
        }
    }

    /** Puts the wish next and blends into it right away. */
    fun playNow(id: String) {
        val song = songs[id] ?: return
        brain.remove(id)
        scope?.launch {
            player?.playNext(song.toMediaItem())
            pendingId = id
            player?.service?.crossfadeToNext()
            publish()
        }
    }

    fun remove(id: String) {
        brain.remove(id)
        publish()
    }

    private fun onSongChanged(id: String?, artist: String?) {
        // Remember how the floor reacted to the song that just ended.
        currentArtist?.let { if (floorCount > 0) brain.learn(it, floorSum / floorCount) }
        floorSum = 0.0
        floorCount = 0
        rescue.reset()
        currentArtist = artist
        if (id != null && id == pendingId) pendingId = null
        // The host may have removed the queued wish by hand.
        val p = player?.player
        if (pendingId != null && p != null && (0 until p.mediaItemCount).none { p.getMediaItemAt(it).mediaId == pendingId }) pendingId = null
        queueBestWish()
    }

    private fun queueBestWish() {
        if (pendingId != null) return
        val wish = brain.pickNext(_floor.value, currentArtist) ?: return publish()
        val song = songs[wish.id] ?: return publish()
        player?.playNext(song.toMediaItem())
        pendingId = wish.id
        _log.value = "Als Nächstes: ${wish.title} (${wish.votes} Stimme(n))"
        publish()
    }

    private fun rescueFloor() {
        queueBestWish()
        _log.value = "Tanzfläche leer – wechsle früher"
        player?.service?.crossfadeToNext()
    }

    private fun publish() {
        _wishes.value = brain.wishes(_floor.value, currentArtist)
    }

    private val handler = object : WishServer.Handler {
        override fun wish(query: String, guest: String): String {
            val song = runBlocking {
                YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    ?.items?.filterIsInstance<SongItem>()?.firstOrNull()
            } ?: return "Nichts gefunden zu „$query“."
            songs[song.id] = song
            val artist = song.artists.joinToString { it.name }
            val result = brain.addWish(song.id, song.title, artist, song.thumbnail, guest)
            scope?.launch { queueBestWish() }
            return when (result) {
                WishResult.ADDED -> "Gewünscht: ${song.title} – $artist"
                WishResult.VOTED -> "Stimme für ${song.title} gezählt"
                WishResult.ALREADY_VOTED -> "Hast du schon gewünscht"
                WishResult.LIMIT_REACHED -> "Du hast schon 3 offene Wünsche"
            }
        }

        override fun vote(id: String, guest: String): Boolean =
            brain.vote(id, guest).also { if (it) scope?.launch { publish() } }

        override fun wishesJson(): String =
            _wishes.value.joinToString(",", "[", "]") {
                "{\"id\":${WishServer.jsonString(it.id)},\"title\":${WishServer.jsonString(it.title)}," +
                    "\"artist\":${WishServer.jsonString(it.artist)},\"thumbnail\":${WishServer.jsonString(it.thumbnail)}," +
                    "\"votes\":${it.votes}}"
            }
    }

    private fun localIp(): String? =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
}
