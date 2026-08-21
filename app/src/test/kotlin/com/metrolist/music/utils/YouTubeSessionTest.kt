package com.metrolist.music.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSessionTest {
    @Test
    fun `a cookie carrying SAPISID is a session that can write`() {
        assertTrue(isSignedInToYouTube("VISITOR_INFO1_LIVE=abc; SAPISID=secret; SID=xyz"))
    }

    @Test
    fun `a cookie without SAPISID is not`() {
        // The half signed in state the dialog used to accept: a cookie is there, but nothing in it
        // can sign a write, so a playlist marked for syncing would never reach YouTube.
        assertFalse(isSignedInToYouTube("VISITOR_INFO1_LIVE=abc; SID=xyz"))
    }

    @Test
    fun `no cookie at all is not a session`() {
        assertFalse(isSignedInToYouTube(null))
        assertFalse(isSignedInToYouTube(""))
    }

    @Test
    fun `a cookie that does not parse is not a session`() {
        assertFalse(isSignedInToYouTube("not a cookie"))
    }

    @Test
    fun `a name that merely contains SAPISID is not SAPISID`() {
        assertFalse(isSignedInToYouTube("__Secure-3PAPISIDTS=abc"))
    }
}
