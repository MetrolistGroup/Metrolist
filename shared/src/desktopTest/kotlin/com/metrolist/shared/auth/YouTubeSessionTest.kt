package com.metrolist.shared.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeSessionTest {
    @Test
    fun onlyUnauthorizedAccountResponseExpiresSession() {
        assertTrue(isExpiredYouTubeSessionResponse(401, hasAuthenticatedSession = true))
        assertFalse(isExpiredYouTubeSessionResponse(401, hasAuthenticatedSession = false))
        assertFalse(isExpiredYouTubeSessionResponse(400, hasAuthenticatedSession = true))
        assertFalse(isExpiredYouTubeSessionResponse(403, hasAuthenticatedSession = true))
        assertFalse(isExpiredYouTubeSessionResponse(429, hasAuthenticatedSession = true))
        assertFalse(isExpiredYouTubeSessionResponse(500, hasAuthenticatedSession = true))
    }

    @Test
    fun forbiddenCanExpireEndpointSpecificAuthenticatedSession() {
        assertTrue(
            isExpiredYouTubeSessionResponse(
                403,
                hasAuthenticatedSession = true,
                forbiddenExpiresSession = true,
            ),
        )
        assertFalse(
            isExpiredYouTubeSessionResponse(
                403,
                hasAuthenticatedSession = false,
                forbiddenExpiresSession = true,
            ),
        )
    }
}
