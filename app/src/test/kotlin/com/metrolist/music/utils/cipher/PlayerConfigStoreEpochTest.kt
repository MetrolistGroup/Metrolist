package com.metrolist.music.utils.cipher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [PlayerConfigStore.configEpoch] must advance exactly when [PlayerConfigStore.applyRemote] changes
 * the table, and never when an identical table is re-applied. That epoch is the sole signal the
 * cipher uses to decide a cached WebView was built from a now-superseded config and must be rebuilt
 * — the fix for playback staying broken (a wrong-but-non-throwing signature) until an app restart.
 * A spurious bump would force needless WebView rebuilds; a missed bump would reproduce the bug.
 */
class PlayerConfigStoreEpochTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun configs(json: String) =
        (PlayerConfigParser.parse(json) as PlayerConfigParser.ParseResult.Success).configs

    // Same hash, different (sig, nClass, sts) — a real player rotation / config correction.
    private val tableA =
        """{"schemaVersion":1,"players":{"abcd1234":{"sig":"mP(4,155,INPUT)","nClass":"Yx","sts":20613}}}"""
    private val tableB =
        """{"schemaVersion":1,"players":{"abcd1234":{"sig":"Tl(48,5831,INPUT)","nClass":"W_","sts":20620}}}"""

    @Before
    fun setUp() {
        PlayerConfigStore.cacheDirForTest = tmp.newFolder("cipher_cache")
        PlayerConfigStore.setTableForTest(emptyMap())
    }

    @After
    fun tearDown() {
        PlayerConfigStore.cacheDirForTest = null
        PlayerConfigStore.setTableForTest(emptyMap())
    }

    @Test
    fun `epoch advances on a real change and holds on a no-op re-apply`() {
        // configEpoch is process-global (object), so assert relative to the starting value.
        val start = PlayerConfigStore.configEpoch

        PlayerConfigStore.applyRemote(configs(tableA), tableA, "\"e1\"")
        val afterFirst = PlayerConfigStore.configEpoch
        assertEquals("first apply changes the table", start + 1, afterFirst)

        PlayerConfigStore.applyRemote(configs(tableA), tableA, "\"e1\"")
        assertEquals(
            "re-applying the identical table must NOT advance the epoch (no needless rebuild)",
            afterFirst,
            PlayerConfigStore.configEpoch,
        )

        PlayerConfigStore.applyRemote(configs(tableB), tableB, "\"e2\"")
        assertEquals(
            "a corrected entry for the same hash advances the epoch (triggers WebView rebuild)",
            afterFirst + 1,
            PlayerConfigStore.configEpoch,
        )
    }
}
