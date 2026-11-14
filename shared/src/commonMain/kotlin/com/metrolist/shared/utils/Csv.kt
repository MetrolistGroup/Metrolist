package com.metrolist.shared.utils

fun csvCell(value: String): String {
    val firstContent = value.indexOfFirst { !it.isWhitespace() }
    val dangerous =
        value.firstOrNull()?.let { it in "\t\r\n" } == true ||
            (firstContent >= 0 && value[firstContent] in "=+-@")
    val safeValue = if (dangerous) "'$value" else value
    return "\"${safeValue.replace("\"", "\"\"")}\""
}
