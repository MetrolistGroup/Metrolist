package com.metrolist.music.kidj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjBrainTest {
    @Test
    fun `same song from another guest counts as vote, limit per guest`() {
        val b = DjBrain(maxOpenPerGuest = 2)
        assertEquals(WishResult.ADDED, b.addWish("a", "A", "X", null, "g1"))
        assertEquals(WishResult.VOTED, b.addWish("a", "A", "X", null, "g2"))
        assertEquals(WishResult.ALREADY_VOTED, b.addWish("a", "A", "X", null, "g1"))
        assertEquals(WishResult.ADDED, b.addWish("b", "B", "Y", null, "g1"))
        assertEquals(WishResult.LIMIT_REACHED, b.addWish("c", "C", "Z", null, "g1"))
    }

    @Test
    fun `most votes wins on a full floor and pick removes the wish`() {
        val b = DjBrain()
        b.addWish("a", "A", "X", null, "g1")
        b.addWish("b", "B", "Y", null, "g1")
        b.vote("b", "g2")
        assertEquals("b", b.pickNext(0.6, null)?.id)
        assertEquals("a", b.pickNext(0.6, null)?.id)
        assertNull(b.pickNext(0.6, null))
    }

    @Test
    fun `empty floor prefers an artist that filled it before`() {
        val b = DjBrain()
        repeat(5) { b.learn("Proven", 0.9) }
        b.addWish("a", "A", "Unknown", null, "g1")
        b.vote("a", "g2")
        b.addWish("p", "P", "Proven", null, "g3")
        assertEquals("p", b.pickNext(0.05, null)?.id)
    }

    @Test
    fun `rescue fires after a long empty floor and respects cooldown`() {
        val r = RescueWatch(lowForMs = 1000, cooldownMs = 5000)
        assertFalse(r.feed(0.0, 10_000))
        assertTrue(r.feed(0.0, 11_000))
        assertFalse(r.feed(0.0, 12_500))
        assertFalse(r.feed(0.5, 13_000))
        assertFalse(r.feed(0.0, 14_000))
        assertTrue(r.feed(0.0, 16_100))
    }

    @Test
    fun `motion energy grows with pixel change`() {
        val a = ByteArray(64) { 10 }
        val b = ByteArray(64) { 60 }
        assertEquals(0.0, motionEnergy(a, a, 10.0), 1e-9)
        assertEquals(50.0 / 255 * 2, motionEnergy(a, b, 2.0), 1e-9)
        assertEquals(1.0, motionEnergy(a, b, 100.0), 1e-9)
    }

    @Test
    fun `wish server parses form posts and escapes json`() {
        val raw = "POST /api/wish?x=1 HTTP/1.1\r\nHost: x\r\nContent-Length: 14\r\n\r\nq=Daft+Punk%21"
        val req = WishServer.readRequest(raw.byteInputStream())!!
        assertEquals("POST", req.method)
        assertEquals("/api/wish", req.path)
        assertEquals("Daft Punk!", req.form["q"])
        assertNull(WishServer.readRequest("POST / HTTP/1.1\r\nContent-Length: 99999\r\n\r\n".byteInputStream()))
        assertEquals("\"a\\\"b\\u003cscript\\u003e\"", WishServer.jsonString("a\"b<script>"))
    }

    @Test
    fun `wish server routes to handler`() {
        var got = ""
        val server = WishServer(0, object : WishServer.Handler {
            override fun wish(query: String, guest: String) = "ok".also { got = "$query@$guest" }
            override fun vote(id: String, guest: String) = true
            override fun wishesJson() = "[]"
        })
        val r = server.route(WishServer.Request("POST", "/api/wish", mapOf("q" to "  Song  ")), "1.2.3.4")
        assertEquals(200, r.status)
        assertEquals("Song@1.2.3.4", got)
        assertEquals(400, server.route(WishServer.Request("POST", "/api/wish", mapOf("q" to "x")), "g").status)
        assertEquals("[]", server.route(WishServer.Request("GET", "/api/wishes", emptyMap()), "g").body)
        assertTrue(server.route(WishServer.Request("GET", "/", emptyMap()), "g").body.contains("Musikwunsch"))
    }
}
