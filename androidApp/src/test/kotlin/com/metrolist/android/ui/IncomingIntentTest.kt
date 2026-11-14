package com.metrolist.android.ui

import android.content.Intent
import android.nfc.NfcAdapter
import android.text.SpannedString
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IncomingIntentTest {
    @Test
    fun sharedPodcastShowOpensFromViewAndSend() {
        val url = "https://music.youtube.com/browse/MPSP_show?si=shared"
        val expected = IncomingDestination.Podcast("MPSP_show")
        assertEquals(expected, Intent(Intent.ACTION_VIEW, url.toUri()).toIncomingDestination())
        assertEquals(expected, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).toIncomingDestination())
        assertNull(Intent(Intent.ACTION_VIEW, "https://youtube.com.example/browse/MPSP_show".toUri()).toIncomingDestination())
    }

    @Test
    fun sharedTextAndNfcUseTheSameVideoRouteAsView() {
        val url = "https://www.youtube.com/watch?v=R93xV3v718s"
        val expected = IncomingDestination.PlayVideo("R93xV3v718s", null)
        assertEquals(expected, Intent(Intent.ACTION_VIEW, url.toUri()).toIncomingDestination())
        assertEquals(expected, Intent(NfcAdapter.ACTION_NDEF_DISCOVERED, url.toUri()).toIncomingDestination())
        assertEquals(
            expected,
            Intent(
                Intent.ACTION_SEND,
            ).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SpannedString("A song\n$url")).toIncomingDestination(),
        )
        assertNull(Intent(Intent.ACTION_SEND).setType("text/plain").toIncomingDestination())
        assertNull(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Just text").toIncomingDestination())
    }

    @Test
    fun searchLinksDecodeQueryAndRejectUnrelatedHosts() {
        assertEquals(
            IncomingDestination.Search("jazz & blues"),
            Intent(Intent.ACTION_VIEW, "https://music.youtube.com/search?q=jazz+%26+blues".toUri()).toIncomingDestination(),
        )
        listOf(
            "https://youtube.com.example/search?q=jazz",
            "https://example.com/search?q=jazz",
            "https://music.youtube.com/search?q=%20",
            "https://music.youtube.com/search",
        ).forEach { assertNull(Intent(Intent.ACTION_VIEW, it.toUri()).toIncomingDestination()) }
    }

    @Test
    fun previewAndLoopbackLinksDoNotOpenMediaOrRooms() {
        for (host in listOf("127.0.0.1:8080", "localhost:8080", "feat-redesign2-metrosite.metrolist.workers.dev")) {
            for (path in listOf("watch?v=R93xV3v718s", "browse/MPSP_show", "listen?code=ROOM1234", "party?r=ROOM1234")) {
                val url = "https://$host/$path"
                assertNull(Intent(Intent.ACTION_VIEW, url.toUri()).toIncomingDestination())
                assertNull(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).toIncomingDestination())
            }
        }
    }

    @Test
    fun invitationLinksPreserveAllEightCharactersAndRejectInvalidCodes() {
        listOf("https://metrolist.cc/listen?code=room1234", "metrolist://listen-together/room1234").forEach {
            assertEquals(IncomingDestination.ListenTogether("ROOM1234"), Intent(Intent.ACTION_VIEW, it.toUri()).toIncomingDestination())
        }
        listOf(
            "https://metrolist.cc/listen?code=ROOM12",
            "metrolist://listen-together/ROOM12345",
            "metrolist://listen-together/ROOM123!",
            "https://metrolist.cc.example/listen?code=ROOM1234",
        ).forEach { assertNull(Intent(Intent.ACTION_VIEW, it.toUri()).toIncomingDestination()) }
    }
}
