package com.metrolist.shared.backup

internal fun normalizeAbsoluteBackupPath(path: String): String {
    require(path.startsWith('/') && '\u0000' !in path) { "Backup path is invalid" }
    return path
        .split('/')
        .fold(mutableListOf<String>()) { parts, segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(segment)
            }
            parts
        }.joinToString("/", prefix = "/")
}
