package com.metrolist.music.utils

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.metrolist.music.constants.AccountChannelHandleKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.InnerTubeAuthUserKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.LastFMSessionKey
import com.metrolist.music.constants.LastFMUsernameKey
import com.metrolist.music.constants.ListenTogetherIsHostKey
import com.metrolist.music.constants.ListenTogetherRoomCodeKey
import com.metrolist.music.constants.ListenTogetherSessionTimestampKey
import com.metrolist.music.constants.ListenTogetherSessionTokenKey
import com.metrolist.music.constants.ListenTogetherUserIdKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.ProxyPasswordKey
import com.metrolist.music.constants.ProxyUsernameKey
import com.metrolist.music.constants.VisitorDataKey
import okio.buffer
import okio.sink
import java.io.OutputStream

internal suspend fun writeBackupPreferences(preferences: Preferences, output: OutputStream) {
    // Filter a copy so exporting never signs the user out of the running app.
    val backupPreferences = preferences.toMutablePreferences().apply {
        remove(InnerTubeCookieKey)
        remove(VisitorDataKey)
        remove(DataSyncIdKey)
        remove(InnerTubeAuthUserKey)
        remove(AccountNameKey)
        remove(AccountEmailKey)
        remove(AccountChannelHandleKey)
        remove(ListenTogetherSessionTokenKey)
        remove(ListenTogetherRoomCodeKey)
        remove(ListenTogetherUserIdKey)
        remove(ListenTogetherIsHostKey)
        remove(ListenTogetherSessionTimestampKey)
        remove(LastFMSessionKey)
        remove(LastFMUsernameKey)
        remove(OpenRouterApiKey)
        remove(DeeplApiKey)
        remove(ProxyUsernameKey)
        remove(ProxyPasswordKey)
    }
    // The serializer flushes the sink; leave the surrounding ZIP stream open.
    PreferencesSerializer.writeTo(backupPreferences, output.sink().buffer())
}
