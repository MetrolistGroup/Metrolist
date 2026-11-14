/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.deviceconnect

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
fun ByteArray.toBase64(): String = Base64.Default.encode(this)

@OptIn(ExperimentalEncodingApi::class)
fun String.fromBase64(): ByteArray =
    Base64.Default.decode(this).also { decoded ->
        require(decoded.toBase64() == this) { "non-canonical base64 encoding" }
    }
