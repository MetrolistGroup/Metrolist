/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics.romanizer

import com.metrolist.shared.utils.XdgUtils
import java.io.File

internal actual fun glyphmindStorageDirectory(): String = File(XdgUtils.getXdgDataHome(), "metrolist/glyphmind").absolutePath

internal actual fun removeLegacyJapaneseDictionary() {
    File(XdgUtils.getXdgDataHome(), "metrolist/japanese-dictionary").deleteRecursively()
}
