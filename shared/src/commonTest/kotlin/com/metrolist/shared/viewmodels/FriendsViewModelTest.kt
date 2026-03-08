package com.metrolist.shared.viewmodels

import com.metrolist.shared.api.lastfm.LastFmFriend
import com.metrolist.shared.api.lastfm.LastFmNowPlayingAttr
import com.metrolist.shared.api.lastfm.LastFmRecentTrack
import com.metrolist.shared.api.lastfm.LastFmTrackDate
import com.metrolist.shared.api.lastfm.LastFmUserImage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FriendsViewModelTest {
    private val nowSeconds = 1_700_000_000L

    private fun track(
        name: String,
        artist: String,
        isNowPlaying: Boolean = false,
        playedAtSeconds: Long? = null,
    ) = LastFmRecentTrack(
        name = name,
        artist = buildJsonObject { put("#text", artist) },
        attr = LastFmNowPlayingAttr(nowplaying = if (isNowPlaying) "true" else ""),
        date = playedAtSeconds?.let { LastFmTrackDate(uts = it.toString()) },
    )

    private fun friend(
        name: String,
        realname: String = "",
        image: List<LastFmUserImage> = emptyList(),
    ) = LastFmFriend(name = name, realname = realname, image = image)

    @Test
    fun encodeFriendsCache_roundTripsAllFields() {
        val entries =
            listOf(
                friend("alice", realname = "Alice")
                    .toFriendEntry(track("Karma Police", "Radiohead", isNowPlaying = true), nowSeconds),
                friend("bob").toFriendEntry(track("Roygbiv", "Boards of Canada", playedAtSeconds = 100), nowSeconds),
                friend("carol").toFriendEntry(null, nowSeconds),
            )

        val decoded = decodeFriendsCache(encodeFriendsCache(entries))

        assertEquals(entries, decoded)
    }

    @Test
    fun decodeFriendsCache_returnsEmptyForMalformedInput() {
        assertTrue(decodeFriendsCache("not json").isEmpty())
        assertTrue(decodeFriendsCache("").isEmpty())
        assertTrue(decodeFriendsCache("""{"not": "an array"}""").isEmpty())
    }

    @Test
    fun decodeFriendsCache_ignoresMissingOptionalFields() {
        val decoded = decodeFriendsCache("""[{"name":"alice"}]""")

        assertEquals(1, decoded.size)
        assertEquals("alice", decoded[0].name)
        assertEquals("", decoded[0].realName)
        assertNull(decoded[0].artist)
        assertNull(decoded[0].lastPlayedAtSeconds)
        assertFalse(decoded[0].isNowPlaying)
    }
}
