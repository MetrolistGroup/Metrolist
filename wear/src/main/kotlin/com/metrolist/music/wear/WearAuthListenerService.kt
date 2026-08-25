/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.metrolist.music.constants.AuthSyncConstants.AUTH_SYNC_PATH
import com.metrolist.music.constants.AuthSyncConstants.KEY_ACCOUNT_EMAIL
import com.metrolist.music.constants.AuthSyncConstants.KEY_ACCOUNT_HANDLE
import com.metrolist.music.constants.AuthSyncConstants.KEY_ACCOUNT_NAME
import com.metrolist.music.constants.AuthSyncConstants.KEY_AUTH_USER
import com.metrolist.music.constants.AuthSyncConstants.KEY_COOKIE
import com.metrolist.music.constants.AuthSyncConstants.KEY_DATA_SYNC_ID
import com.metrolist.music.constants.AuthSyncConstants.KEY_VISITOR_DATA
import com.metrolist.music.utils.LoginHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class WearAuthListenerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == AUTH_SYNC_PATH) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                
                val cookie = dataMap.getString(KEY_COOKIE)
                val visitorData = dataMap.getString(KEY_VISITOR_DATA)
                val dataSyncId = dataMap.getString(KEY_DATA_SYNC_ID)
                val authUser = dataMap.getString(KEY_AUTH_USER) ?: "0"
                
                if (cookie != null && visitorData != null && dataSyncId != null) {
                    Timber.d("WearAuthListenerService: Received auth sync data, finalizing login...")
                    scope.launch {
                        LoginHelper.finalizeLogin(
                            context = applicationContext,
                            cookie = cookie,
                            visitorData = visitorData,
                            dataSyncId = dataSyncId,
                            authUser = authUser
                        )
                    }
                } else {
                    Timber.d("WearAuthListenerService: Received incomplete auth data (possibly logout)")
                    // If we want to support logout sync, we could call a logout helper here
                }
            }
        }
    }
}
