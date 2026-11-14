/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.extensions

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

fun Context.isInternetConnected(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    return networkCapabilities?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } ?: false
}
