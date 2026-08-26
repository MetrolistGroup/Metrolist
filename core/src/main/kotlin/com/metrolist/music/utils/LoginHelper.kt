/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import com.metrolist.innertube.YouTube
import com.metrolist.music.constants.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LoginHelper {
    suspend fun finalizeLogin(
        context: Context,
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        authUser: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            YouTube.cookie = cookie
            YouTube.visitorData = visitorData
            YouTube.dataSyncId = dataSyncId
            YouTube.authUser = authUser

            val accountInfo = YouTube.accountInfo().getOrThrow()
            
            val saved = context.safeDataStoreEdit { settings ->
                settings[InnerTubeCookieKey] = cookie
                settings[VisitorDataKey] = visitorData
                settings[DataSyncIdKey] = dataSyncId
                settings[InnerTubeAuthUserKey] = authUser
                settings[AccountNameKey] = accountInfo.name
                settings[AccountEmailKey] = accountInfo.email.orEmpty()
                settings[AccountChannelHandleKey] = accountInfo.channelHandle.orEmpty()
            }
            if (!saved) throw Exception("Failed to persist account data")

            withContext(Dispatchers.Main) {
                context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
                    ?.let(context::startActivity)
                Runtime.getRuntime().exit(0)
            }
        }
    }
}
