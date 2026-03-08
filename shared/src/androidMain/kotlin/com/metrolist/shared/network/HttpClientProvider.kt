package com.metrolist.shared.network

import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

internal actual fun apiOkHttpClient(): OkHttpClient =
    sharedOkHttpClient
        .newBuilder()
        .cache(Cache(File(System.getProperty("java.io.tmpdir"), "http_cache"), 50L * 1024L * 1024L))
        .build()
