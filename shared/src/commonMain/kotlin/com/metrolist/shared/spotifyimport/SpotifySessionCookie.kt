package com.metrolist.shared.spotifyimport

fun spotifySessionCookie(cookieHeader: String?): String? =
    cookieHeader
        ?.split(';')
        ?.firstNotNullOfOrNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0 || part.substring(0, separator).trim() != "sp_dc") {
                null
            } else {
                part.substring(separator + 1).trim().takeIf(String::isNotEmpty)
            }
        }
