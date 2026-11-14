package com.metrolist.shared.api.lyrics.romanizer

import com.metrolist.shared.utils.XdgUtils
import java.io.File

internal actual fun japaneseDictionaryStorageDirectory(): File = File(XdgUtils.getXdgDataHome(), "metrolist/japanese-dictionary")
