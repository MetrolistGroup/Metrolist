package com.metrolist.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiddenLibraryItemsTest {
    @Test
    fun typedKeysPreventIdCollisions() {
        var value = "[]"
        val store = HiddenLibraryItemStore({ value }, { value = it })

        store.hide(HiddenLibraryItem(HiddenLibraryItemType.ALBUM, "shared", "Album"))

        assertTrue(store.isHidden(HiddenLibraryItemKey(HiddenLibraryItemType.ALBUM, "shared")))
        assertFalse(store.isHidden(HiddenLibraryItemKey(HiddenLibraryItemType.ARTIST, "shared")))
    }

    @Test
    fun legacyPlaylistJsonDecodesAndNormalizesId() {
        val store =
            HiddenLibraryItemStore(
                read = { """[{"id":"VLPL123","title":"Legacy","thumbnail":"cover","author":"You"}]""" },
                write = {},
            )

        val item = store.all().single()

        assertEquals(HiddenLibraryItemType.PLAYLIST, item.type)
        assertEquals("PL123", item.id)
        assertEquals("Legacy", item.title)
        assertTrue(store.isHidden(HiddenLibraryItemKey(HiddenLibraryItemType.PLAYLIST, "VLPL123")))
    }

    @Test
    fun restoreRemovesOnlyMatchingTypedItem() {
        var value = "[]"
        val store = HiddenLibraryItemStore({ value }, { value = it })
        store.hide(HiddenLibraryItem(HiddenLibraryItemType.PLAYLIST, "VLsame", "Playlist"))
        store.hide(HiddenLibraryItem(HiddenLibraryItemType.ALBUM, "same", "Album"))

        store.restore(HiddenLibraryItemKey(HiddenLibraryItemType.PLAYLIST, "same"))

        assertFalse(store.isHidden(HiddenLibraryItemKey(HiddenLibraryItemType.PLAYLIST, "VLsame")))
        assertTrue(store.isHidden(HiddenLibraryItemKey(HiddenLibraryItemType.ALBUM, "same")))
    }
}
