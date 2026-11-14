package com.metrolist.shared.ui

import com.metrolist.innertubex.models.AccountInfo
import com.metrolist.shared.auth.supportedYouTubeAuthCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenLoginParsingTest {
    @Test
    fun plainTextEditsPreserveCombinedTokenFields() {
        val text =
            buildTokenText(
                cookie = "SID=sid; SAPISID=old-secret",
                visitorData = "old-visitor",
                dataSyncId = "old-sync",
                authUser = "0",
                accountName = "Old account",
                accountEmail = "old@example.invalid",
                accountChannelHandle = "@old",
            ).replace("old", "new")
                .replace("Old account", "New account")
                .replace("***AUTH USER*** =0", "***AUTH USER*** =2")
                .replace("\n", "\r\n")
        val parsed = parseToken(text)

        assertTrue(hasAuthCookie(parsed.cookie))
        assertEquals("SID=sid; SAPISID=new-secret", parsed.cookie)
        assertEquals("new-visitor", parsed.visitorData)
        assertEquals("new-sync", parsed.dataSyncId)
        assertEquals("2", parsed.authUser)
        assertEquals("New account", parsed.accountName)
        assertEquals("new@example.invalid", parsed.accountEmail)
        assertEquals("@new", parsed.accountChannelHandle)
    }

    @Test
    fun invalidPlainTextCannotSupplyAuthentication() {
        listOf(
            "",
            "not a token",
            "***INNERTUBE COOKIE*** =SAPISID=\n***VISITOR DATA*** =visitor",
            "***VISITOR DATA*** =SAPISID=secret",
            "***UNKNOWN*** =SAPISID=secret",
        ).forEach { text ->
            assertFalse(hasAuthCookie(parseToken(text).cookie))
        }
    }

    @Test
    fun emptyStructuredFieldsDoNotConsumeFollowingMarkers() {
        val parsed =
            parseToken(
                """
                ***INNERTUBE COOKIE*** =
                ***VISITOR DATA*** =
                ***DATASYNC ID*** =
                ***AUTH USER*** =0
                ***ACCOUNT NAME*** =
                ***ACCOUNT EMAIL*** =
                ***ACCOUNT CHANNEL HANDLE*** =
                """.trimIndent(),
            )

        assertEquals("", parsed.cookie)
        assertEquals("", parsed.visitorData)
        assertEquals("", parsed.dataSyncId)
        assertEquals("0", parsed.authUser)
        assertEquals("", parsed.accountName)
        assertEquals("", parsed.accountEmail)
        assertEquals("", parsed.accountChannelHandle)
    }

    @Test
    fun normalizesPersonalAndChannelDataSyncIdentities() {
        assertEquals("account-id", sanitizeDataSyncId("||account-id"))
        assertEquals("channel-id", sanitizeDataSyncId("channel-id||account-id"))
        assertEquals("account-id", sanitizeDataSyncId("account-id"))
    }

    @Test
    fun selectsOneSupportedCredentialDeterministically() {
        assertEquals(
            "primary",
            supportedYouTubeAuthCredential("__Secure-3PAPISID=three; SAPISID=primary"),
        )
        assertEquals("three", supportedYouTubeAuthCredential("__Secure-3PAPISID=three"))
        assertEquals(null, supportedYouTubeAuthCredential("SID=sid; SAPISID="))
    }

    @Test
    fun parsesCookieHeaderWithoutTreatingOtherHeadersAsCookieData() {
        val parsed =
            parseToken(
                """
                Cookie: SID=sid; SAPISID=secret; __Secure-3PAPISID=secure
                X-Goog-AuthUser: 2
                X-Goog-Visitor-Id: visitor
                """.trimIndent(),
            )

        assertEquals("SID=sid; SAPISID=secret; __Secure-3PAPISID=secure", parsed.cookie)
        assertEquals("2", parsed.authUser)
        assertTrue(hasAuthCookie(parsed.cookie))
    }

    @Test
    fun joinsLineWrappedVisitorData() {
        val parsed =
            parseToken("***INNERTUBE COOKIE*** =SAPISID=secret\r\n***VISITOR DATA*** =Cgt-visitor_\r\npart%3D%3D\r\n")

        assertEquals("Cgt-visitor_part%3D%3D", parsed.visitorData)
    }

    @Test
    fun parsesBrowserCookieJsonExport() {
        val parsed =
            parseToken(
                """[{"domain":".youtube.com","name":"SID","value":"sid"},{"domain":"music.youtube.com","name":"SAPISID","value":"secret"}]""",
            )

        assertEquals("SID=sid; SAPISID=secret", parsed.cookie)
        assertTrue(hasAuthCookie(parsed.cookie))
    }

    @Test
    fun parsesWrappedBrowserCookieExport() {
        val parsed =
            parseToken(
                """{"cookies":[{"domain":"youtube.com","name":"SID","value":"sid"},{"domain":".youtube.com","name":"SAPISID","value":"secret"}]}""",
            )

        assertEquals("SID=sid; SAPISID=secret", parsed.cookie)
        assertTrue(hasAuthCookie(parsed.cookie))
    }

    @Test
    fun rejectsApiSidWithoutASapisidHashCookie() {
        assertFalse(hasAuthCookie("SID=sid; APISID=not-sufficient"))
        assertFalse(hasAuthCookie("sapisid=wrong-case"))
        assertTrue(hasAuthCookie("__Secure-3PAPISID=secure"))
    }

    @Test
    fun browserExportsKeepOnlyValidYouTubeCookies() {
        val parsed =
            parseToken(
                """[{"domain":"example.com","name":"SAPISID","value":"unrelated"},{"domain":".youtube.com","name":"SAPISID","value":"valid"},{"domain":"youtube.com","name":"bad;name","value":"injected"}]""",
            )

        assertEquals("SAPISID=valid", parsed.cookie)
    }

    @Test
    fun netscapeExportsKeepOnlyYouTubeDomains() {
        val parsed =
            parseToken(
                """
                .example.com	TRUE	/	TRUE	0	SAPISID	unrelated
                .youtube.com	TRUE	/	TRUE	0	SAPISID	valid
                """.trimIndent(),
            )

        assertEquals("SAPISID=valid", parsed.cookie)
    }

    @Test
    fun parsesHttpOnlyNetscapeYouTubeCookies() {
        val parsed =
            parseToken(
                """
                #HttpOnly_.youtube.com	TRUE	/	TRUE	0	SID	sid
                #HttpOnly_.youtube.com	TRUE	/	TRUE	0	SAPISID	secret
                """.trimIndent(),
            )

        assertEquals("SID=sid; SAPISID=secret", parsed.cookie)
        assertTrue(hasAuthCookie(parsed.cookie))
    }

    @Test
    fun exportedAccountDetailsSurviveTokenRoundTrip() {
        val parsed =
            parseToken(
                buildTokenText(
                    cookie = "SAPISID=secret",
                    visitorData = "visitor",
                    dataSyncId = "sync",
                    authUser = "2",
                    accountName = "Music account",
                    accountEmail = "listener@example.invalid",
                    accountChannelHandle = "@listener",
                ),
            )

        assertEquals("Music account", parsed.accountName)
        assertEquals("listener@example.invalid", parsed.accountEmail)
        assertEquals("@listener", parsed.accountChannelHandle)
        assertEquals(
            AccountInfo("Music account", "listener@example.invalid", "@listener", "avatar"),
            parsed.resolveAccountInfo(AccountInfo("Old", "old@example.invalid", "@old", "avatar")),
        )
    }

    @Test
    fun rawCookieKeepsExistingSessionDetails() {
        val accountInfo = AccountInfo("Music account", "listener@example.invalid", "@listener", "avatar")
        val resolved =
            parseToken("SAPISID=secret").resolveCredentials(
                existingCookie = "SAPISID=secret",
                existingVisitorData = "visitor",
                existingDataSyncId = "sync",
                existingAuthUser = "2",
                existingAccountInfo = accountInfo,
            )

        assertEquals("visitor", resolved.visitorData)
        assertEquals("sync", resolved.dataSyncId)
        assertEquals("2", resolved.authUser)
        assertEquals(accountInfo, resolved.accountInfo)
    }

    @Test
    fun legacyStructuredTokenOmissionSelectsAuthUserZeroAndClearsStaleMetadata() {
        val existingAccountInfo = AccountInfo("Old account", "old@example.invalid", "@old", "old-avatar")
        val exported =
            buildTokenText(
                cookie = "SAPISID=secret",
                visitorData = "zero-visitor",
                dataSyncId = "zero-sync",
                authUser = "0",
                accountName = existingAccountInfo.name,
                accountEmail = existingAccountInfo.email.orEmpty(),
                accountChannelHandle = existingAccountInfo.channelHandle.orEmpty(),
            )
        assertTrue("***AUTH USER*** =0" in exported)

        val resolved =
            parseToken(exported.lineSequence().filterNot { "***AUTH USER***" in it }.joinToString("\n"))
                .resolveCredentials(
                    existingCookie = "SAPISID=secret",
                    existingVisitorData = "old-visitor",
                    existingDataSyncId = "old-sync",
                    existingAuthUser = "2",
                    existingAccountInfo = existingAccountInfo,
                )

        assertEquals("zero-visitor", resolved.visitorData)
        assertEquals("zero-sync", resolved.dataSyncId)
        assertEquals("0", resolved.authUser)
        assertEquals(AccountInfo("", null, null, null), resolved.accountInfo)
    }

    @Test
    fun changedCookieDiscardsUnchangedGeneratedMetadata() {
        val existingAccountInfo = AccountInfo("Old account", "old@example.invalid", "@old", "old-avatar")
        val resolved =
            parseToken(
                buildTokenText(
                    cookie = "SAPISID=new-secret",
                    visitorData = "old-visitor",
                    dataSyncId = "old-sync",
                    authUser = "2",
                    accountName = existingAccountInfo.name,
                    accountEmail = existingAccountInfo.email.orEmpty(),
                    accountChannelHandle = existingAccountInfo.channelHandle.orEmpty(),
                ),
            ).resolveCredentials(
                existingCookie = "SAPISID=old-secret",
                existingVisitorData = "old-visitor",
                existingDataSyncId = "old-sync",
                existingAuthUser = "2",
                existingAccountInfo = existingAccountInfo,
            )

        assertEquals("SAPISID=new-secret", resolved.cookie)
        assertEquals(AccountInfo("", null, null, null), resolved.accountInfo)
    }

    @Test
    fun changedCookieKeepsOnlyIndividuallyEditedMetadata() {
        val existingAccountInfo = AccountInfo("Old account", "old@example.invalid", "@old", "old-avatar")
        val resolved =
            parseToken(
                buildTokenText(
                    cookie = "SAPISID=new-secret",
                    visitorData = "old-visitor",
                    dataSyncId = "old-sync",
                    authUser = "2",
                    accountName = "New account",
                    accountEmail = existingAccountInfo.email.orEmpty(),
                    accountChannelHandle = existingAccountInfo.channelHandle.orEmpty(),
                ),
            ).resolveCredentials(
                existingCookie = "SAPISID=old-secret",
                existingVisitorData = "old-visitor",
                existingDataSyncId = "old-sync",
                existingAuthUser = "2",
                existingAccountInfo = existingAccountInfo,
            )

        assertEquals(AccountInfo("New account", null, null, null), resolved.accountInfo)
    }

    @Test
    fun sameCookieProfileSwitchUsesImportedMetadataWithoutOldThumbnail() {
        val resolved =
            parseToken(
                buildTokenText(
                    cookie = "SAPISID=secret",
                    visitorData = "new-visitor",
                    dataSyncId = "new-sync",
                    authUser = "3",
                    accountName = "New account",
                    accountEmail = "new@example.invalid",
                    accountChannelHandle = "@new",
                ),
            ).resolveCredentials(
                existingCookie = "SAPISID=secret",
                existingVisitorData = "old-visitor",
                existingDataSyncId = "old-sync",
                existingAuthUser = "2",
                existingAccountInfo = AccountInfo("Old account", "old@example.invalid", "@old", "old-avatar"),
            )

        assertEquals("new-sync", resolved.dataSyncId)
        assertEquals("3", resolved.authUser)
        assertEquals(AccountInfo("New account", "new@example.invalid", "@new", null), resolved.accountInfo)
    }

    @Test
    fun parsesMultipleSetCookieHeadersAndRejectsEmptyAuthCookie() {
        val parsed =
            parseToken(
                """
                Set-Cookie: SID=sid; Path=/; Secure
                Set-Cookie: SAPISID=secret; Path=/; Secure
                """.trimIndent(),
            )

        assertEquals("SID=sid; SAPISID=secret", parsed.cookie)
        assertTrue(hasAuthCookie(parsed.cookie))
        assertFalse(hasAuthCookie("SID=sid; SAPISID="))
    }
}
