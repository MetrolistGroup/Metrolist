package com.metrolist.shared.youtube.innertube

import com.metrolist.shared.ui.parseToken
import kotlin.test.Test
import kotlin.test.assertEquals

class LoginTokenParsingRegressionTest {
    @Test
    fun tokenParserKeepsBrowserAuthUserIndex() {
        val headerToken = parseToken("x-goog-authuser: 2")
        val ytcfgToken = parseToken("\"SESSION_INDEX\": \"3\"")

        assertEquals("2", headerToken.authUser)
        assertEquals("3", ytcfgToken.authUser)
    }
}
