package com.metrolist.shared.utils

object LogBridge {
    var sink: (
        (level: String, tag: String, message: String, throwable: Throwable?) -> Unit
    )? = null
}
