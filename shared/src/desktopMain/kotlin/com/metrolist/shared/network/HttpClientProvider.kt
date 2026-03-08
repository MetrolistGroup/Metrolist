package com.metrolist.shared.network

import okhttp3.OkHttpClient

internal actual fun apiOkHttpClient(): OkHttpClient = sharedOkHttpClient
