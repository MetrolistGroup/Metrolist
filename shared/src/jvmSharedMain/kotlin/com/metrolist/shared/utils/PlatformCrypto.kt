package com.metrolist.shared.utils

import java.security.MessageDigest

actual fun md5Hex(input: String): String =
    MessageDigest
        .getInstance("MD5")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
