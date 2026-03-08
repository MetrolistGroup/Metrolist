package com.metrolist.shared.utils

import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

actual suspend fun clearWebCookies() =
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { continuation ->
            CookieManager.getInstance().removeAllCookies { continuation.resume(Unit) }
        }
        CookieManager.getInstance().flush()
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
        }
    }
