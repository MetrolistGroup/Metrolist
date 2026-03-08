package com.metrolist.shared.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieStorage
import platform.WebKit.WKWebsiteDataStore
import kotlin.coroutines.resume

actual suspend fun clearWebCookies() =
    withContext(Dispatchers.Main) {
        val cookieStorage = NSHTTPCookieStorage.sharedHTTPCookieStorage
        cookieStorage.cookies?.filterIsInstance<NSHTTPCookie>()?.forEach(cookieStorage::deleteCookie)
        val store = WKWebsiteDataStore.defaultDataStore().httpCookieStore
        val cookies =
            suspendCancellableCoroutine<List<NSHTTPCookie>> { continuation ->
                store.getAllCookies { continuation.resume(it.orEmpty().filterIsInstance<NSHTTPCookie>()) }
            }
        for (cookie in cookies) {
            suspendCancellableCoroutine<Unit> { continuation ->
                store.deleteCookie(cookie) { continuation.resume(Unit) }
            }
        }
    }
