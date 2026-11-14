package com.metrolist.shared.utils

fun logIosApplicationError(
    tag: String,
    message: String,
) {
    Logger.e(tag, message, null)
}
