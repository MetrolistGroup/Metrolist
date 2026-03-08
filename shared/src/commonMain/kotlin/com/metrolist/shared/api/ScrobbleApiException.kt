package com.metrolist.shared.api

class ScrobbleApiException(
    message: String,
    val disposition: ScrobbleFailureDisposition,
    val statusCode: Int? = null,
    val retryAfterMs: Long? = null,
    val apiErrorCode: Int? = null,
) : Exception(message) {
    val retryable: Boolean get() = disposition == ScrobbleFailureDisposition.RETRY

    constructor(message: String, retryable: Boolean) : this(
        message,
        if (retryable) ScrobbleFailureDisposition.RETRY else ScrobbleFailureDisposition.PERMANENT,
    )
}

enum class ScrobbleFailureDisposition { RETRY, PERMANENT, AUTHENTICATION }
