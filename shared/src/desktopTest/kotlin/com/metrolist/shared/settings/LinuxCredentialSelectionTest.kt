package com.metrolist.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinuxCredentialSelectionTest {
    @Test
    fun headerOnlyDefaultCannotHideSavedAccountsInAnotherCollection() {
        val stored = LinuxCredentialRecord("innerTubeCookie=SAPISID=saved\nlastFmSession=saved-lastfm", 1, "/login/1")
        val truncated = LinuxCredentialRecord("#Metrolist desktop secrets\n", 2, "/default/1")
        assertEquals(stored, selectLinuxCredentialRecord(listOf(truncated, stored)))
        assertEquals(stored, selectLinuxCredentialRecord(listOf(stored, truncated)))
    }

    @Test
    fun populatedDuplicatesUseModificationTimeAndStablePathInsteadOfSearchOrder() {
        val old = LinuxCredentialRecord("innerTubeCookie=SAPISID=old", 1, "/login/3")
        val current = LinuxCredentialRecord("innerTubeCookie=SAPISID=current", 2, "/login/2")
        val tied = LinuxCredentialRecord("innerTubeCookie=SAPISID=tied", 2, "/default/1")
        assertEquals(current, selectLinuxCredentialRecord(listOf(old, current, tied)))
        assertEquals(current, selectLinuxCredentialRecord(listOf(tied, current, old)))
    }

    @Test
    fun explicitClearOutranksOlderPopulatedDuplicates() {
        val old = LinuxCredentialRecord("innerTubeCookie=SAPISID=old", 1, "/login/1")
        val cleared = LinuxCredentialRecord("__metrolist_clear__=true", 2, "/default/1")
        assertEquals(cleared, selectLinuxCredentialRecord(listOf(old, cleared)))
    }

    @Test
    fun malformedRecordCannotSilentlyBecomeAnEmptySession() {
        assertFailsWith<IllegalArgumentException> {
            LinuxCredentialRecord("innerTubeCookie=\\uXYZW", 1, "/login/1")
        }
    }

    @Test
    fun malformedUnlockedDuplicateDoesNotBlockAValidRecord() {
        val stored = parseLinuxCredentialRecord("innerTubeCookie=SAPISID=saved", 1, "/default/1")
        val malformed = parseLinuxCredentialRecord("innerTubeCookie=\\uXYZW", 3, "/login/1")
        assertNotNull(stored)
        assertNull(malformed)
        assertEquals(
            LinuxCredentialSelection.Ready(stored),
            resolveLinuxCredentialSelection(listOfNotNull(malformed, stored), emptyList(), true, false),
        )
    }

    @Test
    fun onlyUnreadableRecordsLeaveSelectionUnresolved() {
        val records =
            listOfNotNull(
                parseLinuxCredentialRecord("innerTubeCookie=\\u12", 1, "/login/1"),
                parseLinuxCredentialRecord(null, 2, "/login/2"),
            )
        assertEquals(LinuxCredentialSelection.Unreadable, resolveLinuxCredentialSelection(records, emptyList(), true, false))
        assertEquals(LinuxCredentialSelection.Ready(null), resolveLinuxCredentialSelection(records, emptyList(), false, false))
    }

    @Test
    fun headerOnlyDefaultWaitsForLockedPopulatedLoginEvenWhenDefaultIsNewer() {
        val header = LinuxCredentialRecord("#Metrolist desktop secrets\n", 3, "/default/1")
        val login = LinuxCredentialRecord("innerTubeCookie=SAPISID=saved", 1, "/login/1")
        val locked = listOf(LinuxLockedCredentialItem(login.modified, login.path))
        assertEquals(LinuxCredentialSelection.Unlock, resolveLinuxCredentialSelection(listOf(header), locked, true, false))
        assertEquals(LinuxCredentialSelection.Unreadable, resolveLinuxCredentialSelection(listOf(header), locked, true, true))
        assertEquals(
            LinuxCredentialSelection.Ready(login),
            resolveLinuxCredentialSelection(listOf(header, login), emptyList(), true, true),
        )
    }

    @Test
    fun newerLockedLoginPreventsReadingOrUpdatingAnOlderDefault() {
        val old = LinuxCredentialRecord("innerTubeCookie=SAPISID=old", 1, "/default/1")
        val login = LinuxCredentialRecord("innerTubeCookie=SAPISID=saved", 3, "/login/1")
        val locked = listOf(LinuxLockedCredentialItem(login.modified, login.path))
        assertEquals(LinuxCredentialSelection.Unlock, resolveLinuxCredentialSelection(listOf(old), locked, true, false))
        assertEquals(LinuxCredentialSelection.Unreadable, resolveLinuxCredentialSelection(listOf(old), locked, true, true))
        assertEquals(
            LinuxCredentialSelection.Ready(login),
            resolveLinuxCredentialSelection(listOf(old, login), emptyList(), true, true),
        )
    }

    @Test
    fun unlockedItemWithNoLoadedSecretCannotHideNewerOrPopulatedCredentials() {
        val login = LinuxCredentialRecord("innerTubeCookie=SAPISID=saved", 3, "/login/1")
        val item = LinuxLockedCredentialItem(login.modified, login.path)
        for (current in listOf(
            LinuxCredentialRecord("innerTubeCookie=SAPISID=old", 1, "/default/1"),
            LinuxCredentialRecord("#Metrolist desktop secrets\n", 4, "/default/1"),
        )) {
            val lockedItems = mutableListOf<LinuxLockedCredentialItem>()
            assertNull(loadLinuxCredentialSecret<LinuxCredentialRecord>(item, false, lockedItems) { null })
            assertEquals(LinuxCredentialSelection.Unlock, resolveLinuxCredentialSelection(listOf(current), lockedItems, true, false))
            assertEquals(LinuxCredentialSelection.Unreadable, resolveLinuxCredentialSelection(listOf(current), lockedItems, true, true))
            lockedItems.clear()
            val recovered = loadLinuxCredentialSecret(item, false, lockedItems) { login }
            assertEquals(
                LinuxCredentialSelection.Ready(login),
                resolveLinuxCredentialSelection(listOfNotNull(current, recovered), lockedItems, true, true),
            )
        }
    }

    @Test
    fun lockedItemWaitsForUnlockWithoutLoadingItsSecret() {
        val item = LinuxLockedCredentialItem(3, "/login/1")
        val lockedItems = mutableListOf<LinuxLockedCredentialItem>()
        assertNull(loadLinuxCredentialSecret<LinuxCredentialRecord>(item, true, lockedItems) { error("Item is locked") })
        assertEquals(listOf(item), lockedItems)
    }

    @Test
    fun olderLockedDuplicateDoesNotPromptOrBlockPopulatedOrClearedRecords() {
        for (payload in listOf("innerTubeCookie=SAPISID=saved", "__metrolist_clear__=true")) {
            val current = LinuxCredentialRecord(payload, 3, "/default/1")
            val locked = listOf(LinuxLockedCredentialItem(1, "/login/1"))
            for (unlockAttempted in listOf(false, true)) {
                assertEquals(
                    LinuxCredentialSelection.Ready(current),
                    resolveLinuxCredentialSelection(listOf(current), locked, true, unlockAttempted),
                )
            }
        }
    }

    @Test
    fun lockedItemsWithUnknownTimestampsCannotBeIgnored() {
        for ((modified, lockedModified) in listOf(3L to 0L, 0L to 1L)) {
            val current = LinuxCredentialRecord("innerTubeCookie=SAPISID=saved", modified, "/default/1")
            val locked = listOf(LinuxLockedCredentialItem(lockedModified, "/login/1"))
            assertEquals(LinuxCredentialSelection.Unlock, resolveLinuxCredentialSelection(listOf(current), locked, true, false))
            assertEquals(LinuxCredentialSelection.Unreadable, resolveLinuxCredentialSelection(listOf(current), locked, true, true))
        }
    }

    @Test
    fun tiedLockedItemsUseTheSamePathOrderAsReadableRecords() {
        val current = LinuxCredentialRecord("innerTubeCookie=SAPISID=saved", 3, "/login/2")
        val lower = LinuxLockedCredentialItem(3, "/login/1")
        val higher = LinuxLockedCredentialItem(3, "/login/3")
        assertEquals(
            LinuxCredentialSelection.Ready(current),
            resolveLinuxCredentialSelection(listOf(current), listOf(lower), true, false),
        )
        for (locked in listOf(listOf(lower, higher), listOf(higher, lower))) {
            assertEquals(LinuxCredentialSelection.Unlock, resolveLinuxCredentialSelection(listOf(current), locked, true, false))
            assertEquals(LinuxCredentialSelection.Unreadable, resolveLinuxCredentialSelection(listOf(current), locked, true, true))
        }
    }

    @Test
    fun onlyLockedMatchesRequireUnlockAndFailIfTheyStayLocked() {
        val locked = listOf(LinuxLockedCredentialItem(1, "/login/1"))
        assertEquals(LinuxCredentialSelection.Unlock, resolveLinuxCredentialSelection(emptyList(), locked, true, false))
        assertEquals(LinuxCredentialSelection.Unreadable, resolveLinuxCredentialSelection(emptyList(), locked, true, true))
    }
}
