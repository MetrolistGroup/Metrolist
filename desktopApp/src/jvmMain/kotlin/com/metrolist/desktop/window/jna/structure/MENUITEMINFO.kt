package com.metrolist.desktop.window.jna.structure

import com.sun.jna.Structure
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR
import com.sun.jna.platform.win32.WinDef.HBITMAP
import com.sun.jna.platform.win32.WinDef.HMENU

@Suppress("SpellCheckingInspection", "unused")
class MENUITEMINFO : Structure() {
    @JvmField
    var cbSize: Int = 0

    @JvmField
    var fMask: Int = 0

    @JvmField
    var fType: Int = 0

    @JvmField
    var fState: Int = 0

    @JvmField
    var wID: Int = 0

    @JvmField
    var hSubMenu: HMENU? = null

    @JvmField
    var hbmpChecked: HBITMAP? = null

    @JvmField
    var hbmpUnchecked: HBITMAP? = null

    @JvmField
    var dwItemData: ULONG_PTR = ULONG_PTR(0)

    @JvmField
    var dwTypeData: String? = null

    @JvmField
    var cch: Int = 0

    @JvmField
    var hbmpItem: HBITMAP? = null

    override fun getFieldOrder(): List<String> =
        listOf(
            "cbSize",
            "fMask",
            "fType",
            "fState",
            "wID",
            "hSubMenu",
            "hbmpChecked",
            "hbmpUnchecked",
            "dwItemData",
            "dwTypeData",
            "cch",
            "hbmpItem",
        )
}
