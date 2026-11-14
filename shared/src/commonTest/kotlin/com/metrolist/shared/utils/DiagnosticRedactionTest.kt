package com.metrolist.shared.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticRedactionTest {
    @Test
    fun urlsKeepHostAndPathButLoseSecrets() {
        assertEquals(
            "https://lrclib.net/api/get?<redacted>",
            redactDiagnosticUrl("https://user:pass@lrclib.net/api/get?track_name=Song&key=abc#frag"),
        )
        assertEquals(
            "https://rr3---sn-abc.googlevideo.com/videoplayback/id/<redacted>/itag/251",
            redactDiagnosticUrl("https://rr3---sn-abc.googlevideo.com/videoplayback/id/o-AJtq0B1xZbYxQ2mN3pL4kR5sT6uV7wX8/itag/251"),
        )
        assertEquals("http://<redacted-ip>:8080/token", redactDiagnosticUrl("http://192.168.1.20:8080/token"))
    }

    @Test
    fun websocketUrlsRemoveAuthorityCredentialsQueriesAndFragments() {
        for (scheme in listOf("ws", "wss", "WSS")) {
            val url = "$scheme://name:secret@host.example/ws?access_token=private&custom=value#fragment"
            assertEquals("$scheme://host.example/ws?<redacted>", redactDiagnosticUrl(url))
            val report = redactDiagnosticText("Server: $url")
            listOf("name", "secret", "private", "value", "fragment").forEach { assertFalse(it in report, report) }
        }
    }

    @Test
    fun textKeepsContextAndRemovesCredentials() {
        val redacted =
            redactDiagnosticText(
                "HTTP 503 from https://api.example.test/v1/lyrics?q=song Authorization: SAPISIDHASH 1700_abcdef " +
                    "cookie=SID=secret; token=\"abc\" visitor CgtJdlNhb2ZFdWpyZyiN8ZK3BjIKCgJQTBIEGgAgYA%3D%3D from 10.0.0.7",
            )
        assertTrue(redacted.startsWith("HTTP 503 from https://api.example.test/v1/lyrics?<redacted>"), redacted)
        listOf("q=song", "abcdef", "SID=secret", "\"abc\"", "CgtJdlNhb2ZFdWpyZyiN8ZK3BjIKCgJQTBIEGgAgYA", "10.0.0.7").forEach {
            assertFalse(it in redacted, "$it leaked in $redacted")
        }
        assertEquals(
            "Failed to allocate a 8388616 byte allocation with 4194304 free bytes",
            redactDiagnosticText("Failed to allocate a 8388616 byte allocation with 4194304 free bytes"),
        )
    }

    @Test
    fun wholeCookieHeadersAreRedactedIncludingQuotedValues() {
        for (header in listOf(
            "Cookie: SID=first; HSID=second; other=third",
            "Set-Cookie: HSID=second; Path=/; Secure",
            "\"Cookie\": \"SID=first; HSID=second; other=third\"",
            "'cookie': 'SID=first; HSID=second; other=third'",
            "Cookie: SID=\"first\"; HSID=second; other=third",
            """{"Cookie":"SID=\"first\"; HSID=second; other=third","status":403}""",
        )) {
            val output = redactDiagnosticText("HTTP failure\n$header\nStatus: 403")
            for (secret in listOf("first", "second", "third")) assertFalse(secret in output, output)
            assertTrue("HTTP failure" in output, output)
            assertTrue("Status: 403" in output, output)
        }
    }

    @Test
    fun r8MapIdsInStackFramesStayReadable() {
        val frame =
            "\tat com.metrolist.android.playback.MusicService.onCreate(" +
                "r8-map-id-7ab53e885c2be230db2ad2aaaf45fea28a363f6296aa9187380523604c373c4a:12)"
        assertEquals(frame, redactDiagnosticText(frame))
    }
}
