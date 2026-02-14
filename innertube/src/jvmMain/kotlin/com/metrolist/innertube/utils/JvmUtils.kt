package com.metrolist.innertube.utils

import java.security.MessageDigest

actual fun sha1(str: String): String = MessageDigest.getInstance("SHA-1").digest(str.toByteArray()).toHex()
