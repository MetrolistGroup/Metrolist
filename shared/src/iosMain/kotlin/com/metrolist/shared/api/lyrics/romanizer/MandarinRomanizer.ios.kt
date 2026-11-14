package com.metrolist.shared.api.lyrics.romanizer

import platform.Foundation.NSString
import platform.Foundation.NSStringTransformMandarinToLatin
import platform.Foundation.NSStringTransformStripDiacritics
import platform.Foundation.stringByApplyingTransform

internal actual fun romanizeMandarin(text: String): String {
    if (text.isBlank()) return text
    return runCatching {
        val ns = text as Any as NSString
        val latin = ns.stringByApplyingTransform(NSStringTransformMandarinToLatin, reverse = false) ?: return@runCatching text
        val stripped = (latin as Any as NSString).stringByApplyingTransform(NSStringTransformStripDiacritics, reverse = false) ?: latin
        stripped
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("\\s+([，。！？,.!?;:])"), "$1")
            .replace(Regex("([，。！？,.!?;:])\\s+"), "$1")
            .trim()
    }.getOrDefault(text)
}
