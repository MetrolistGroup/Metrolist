package com.metrolist.android.ui

import android.content.Intent
import com.metrolist.android.MainActivity
import com.metrolist.android.widget.PlaylistWidgetReceiver
import com.metrolist.shared.model.BuiltinPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WidgetTargetRoutingTest {
    @Test
    fun playlistWidgetTargetsReachTheirCollectionOrPlaylist() {
        val targets =
            mapOf(
                PlaylistWidgetReceiver.TARGET_TYPE_LOCAL to IncomingDestination.Playlist("local-id"),
                PlaylistWidgetReceiver.TARGET_TYPE_ONLINE to IncomingDestination.Playlist("remote-id"),
                PlaylistWidgetReceiver.TARGET_TYPE_LIKED to IncomingDestination.AutoPlaylist("liked"),
                PlaylistWidgetReceiver.TARGET_TYPE_DOWNLOADED to IncomingDestination.AutoPlaylist("downloaded"),
                PlaylistWidgetReceiver.TARGET_TYPE_TOP to IncomingDestination.AutoPlaylist("top100"),
                PlaylistWidgetReceiver.TARGET_TYPE_COLLECTION to IncomingDestination.Playlist(BuiltinPlaylist.CACHED.id),
            )
        for ((type, destination) in targets) {
            val id =
                when (type) {
                    PlaylistWidgetReceiver.TARGET_TYPE_LOCAL -> "local-id"
                    PlaylistWidgetReceiver.TARGET_TYPE_ONLINE -> "remote-id"
                    PlaylistWidgetReceiver.TARGET_TYPE_TOP -> "100"
                    PlaylistWidgetReceiver.TARGET_TYPE_COLLECTION -> BuiltinPlaylist.CACHED.id
                    else -> type
                }
            assertEquals(destination, targetIntent(type, id).toIncomingDestination())
        }
    }

    @Test
    fun uppercaseNotificationTargetsRemainSupported() {
        assertEquals(IncomingDestination.AutoPlaylist("downloaded"), targetIntent("AUTO_PLAYLIST", "downloaded").toIncomingDestination())
        assertEquals(IncomingDestination.Playlist("remote-id"), targetIntent("PLAYLIST", "remote-id").toIncomingDestination())
        assertNull(targetIntent("unknown", "id").toIncomingDestination())
        assertNull(targetIntent(PlaylistWidgetReceiver.TARGET_TYPE_LOCAL, null).toIncomingDestination())
    }

    private fun targetIntent(
        type: String,
        id: String?,
    ): Intent =
        Intent(MainActivity.ACTION_OPEN_WIDGET_TARGET)
            .putExtra(MainActivity.EXTRA_WIDGET_TARGET_TYPE, type)
            .putExtra(MainActivity.EXTRA_WIDGET_TARGET_ID, id)
}
